package org.alter.plugins.content.npcs.wilderness

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.*
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.isProtectedFrom
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.mechanics.poison.poison
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.max

/**
 * **Wilderness boss combat — OSRS-exact rotations + signature mechanics** (Phase C).
 *
 * One plugin overrides the default strategy (`onNpcCombat`) for every [WildernessBosses] boss
 * and runs its attack loop (the KBD pattern), keyed by npc:
 *  - **Callisto** — melee; 1/6 *roar* clears your overhead protection prayers then hits through.
 *  - **Vet'ion** — melee + lightning (magic); at half HP summons **skeletal hellhounds** (phase 2).
 *  - **Venenatis** — melee + a ranged **web** that binds you + a **prayer-sapping** bite.
 *  - **Scorpia** — melee + venom; at half HP calls **guardians** that heal her until slain.
 *  - **Chaos Elemental** — random tri-style; 1/6 *teleport-disarm* (warps you + unequips an item).
 *  - **Chaos Fanatic** — magic blasts; 1/8 "Burn!" knocks your **weapon** off.
 *  - **Crazy Archaeologist** — ranged books; 1/5 "Rain of Knowledge" AoE.
 *
 * Protection prayers fully block the matching style ([isProtectedFrom]); prayer-ignoring specials
 * skip it. Projectile gfx + per-boss attack animations are best-known and marked TUNE (a wrong
 * id is a cosmetic miss, never a thrown call). Stats/lairs/loot live in [WildernessBosses].
 */
class WildernessBossCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val phase2 = AttributeKey<Boolean>()
    private val minionsOut = AttributeKey<Boolean>()
    private val scorpiaGuardians = HashMap<Int, MutableList<Npc>>()

    init {
        WildernessBosses.all.forEach { boss ->
            onNpcCombat(boss.key) { npc.queue { npc.combat(this, boss) } }
        }
    }

    private suspend fun Npc.combat(it: QueueTask, boss: WildernessBosses.WildBoss) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(it, target, distance = boss.approach, projectile = boss.approach > 1) && isAttackDelayReady()) {
                attack(target, boss)
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.attack(target: Pawn, boss: WildernessBosses.WildBoss) {
        ATTACK_ANIMS[boss.key]?.let { if (it > 0) animate(it) }
        when (boss.key) {
            "npc.callisto" -> callisto(target, boss)
            "npc.vetion" -> vetion(target, boss)
            "npc.venenatis" -> venenatis(target, boss)
            "npc.scorpia" -> scorpia(target, boss)
            "npc.chaos_elemental_2054" -> chaosElemental(target, boss)
            "npc.chaos_fanatic" -> chaosFanatic(target, boss)
            "npc.crazy_archaeologist" -> crazyArch(target, boss)
        }
    }

    // ── Per-boss rotations ──────────────────────────────────────────────────────

    private fun Npc.callisto(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (world.chance(1, 6) && target is Player) {
            Prayers.deactivate(target, Prayer.PROTECT_FROM_MELEE)
            Prayers.deactivate(target, Prayer.PROTECT_FROM_MISSILES)
            Prayers.deactivate(target, Prayer.PROTECT_FROM_MAGIC)
            target.message("Callisto lets out a deafening roar!")
            meleeHit(target, boss.maxHit, ignoresPrayer = true)
        } else {
            meleeHit(target, boss.maxHit)
        }
    }

    private fun Npc.vetion(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (attr[phase2] != true && getCurrentHp() <= getMaxHp() / 2) {
            attr[phase2] = true
            summon("npc.skeleton_hellhound", 2)
            if (target is Player) target.message("<col=8f0000>Vet'ion: Come, my pets! Tear them apart!</col>")
        }
        if (world.random(1) == 0) meleeHit(target, boss.maxHit)
        else projectileHit(target, CombatClass.MAGIC, boss.maxHit, gfx = LIGHTNING_GFX)
    }

    private fun Npc.venenatis(target: Pawn, boss: WildernessBosses.WildBoss) {
        when (world.random(3)) {
            0, 1 -> meleeHit(target, boss.maxHit)
            2 -> if (projectileHit(target, CombatClass.RANGED, boss.maxHit, gfx = WEB_GFX) && target is Player) {
                target.freeze(cycles = 8) { target.message("Venenatis' web binds you to the spot!") }
            }
            else -> if (meleeHit(target, boss.maxHit - 6) && target is Player) {
                val cur = target.getSkills().getCurrentLevel(Skills.PRAYER)
                if (cur > 0) target.getSkills().alterCurrentLevel(Skills.PRAYER, -(cur / 2))
                target.message("Venenatis saps your prayer!")
            }
        }
    }

    private fun Npc.scorpia(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (attr[minionsOut] != true && getCurrentHp() <= getMaxHp() / 2) {
            attr[minionsOut] = true
            scorpiaGuardians[index] = spawnGuardians()
            if (target is Player) target.message("<col=8f0000>Scorpia calls her guardians to her side!</col>")
        }
        scorpiaGuardians[index]?.let { guards ->
            guards.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
            if (guards.isNotEmpty()) setCurrentHp(minOf(getMaxHp(), getCurrentHp() + GUARDIAN_HEAL))
        }
        if (meleeHit(target, boss.maxHit) && target is Player && world.chance(1, 3)) {
            target.poison(initialDamage = 12) { target.message("Scorpia's sting fills you with venom.") }
        }
    }

    private fun Npc.chaosElemental(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (world.chance(1, 6) && target is Player) {
            val nt = Tile(target.tile.x + world.random(4) - 2, target.tile.z + world.random(4) - 2, target.tile.height)
            target.moveTo(nt)
            forceUnequipRandom(target)
            target.message("The Chaos Elemental's magic disorients you!")
            return
        }
        when (world.random(2)) {
            0 -> meleeHit(target, boss.maxHit)
            1 -> projectileHit(target, CombatClass.RANGED, boss.maxHit, gfx = MAGIC_GFX)
            else -> projectileHit(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
        }
    }

    private fun Npc.chaosFanatic(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (world.chance(1, 8) && target is Player) {
            forceUnequipWeapon(target)
            target.message("<col=8f0000>Chaos Fanatic: Burn!</col>")
        }
        projectileHit(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
    }

    private fun Npc.crazyArch(target: Pawn, boss: WildernessBosses.WildBoss) {
        if (world.chance(1, 5) && target is Player) {
            target.message("<col=8f0000>Crazy Archaeologist: Rain of Knowledge!</col>")
            projectileHit(target, CombatClass.RANGED, boss.maxHit + 6, gfx = BOOK_GFX)
            return
        }
        projectileHit(target, CombatClass.RANGED, boss.maxHit, gfx = BOOK_GFX)
    }

    // ── Shared hit + mechanic helpers ───────────────────────────────────────────

    private fun Npc.meleeHit(target: Pawn, maxHit: Int, ignoresPrayer: Boolean = false): Boolean {
        prepareAttack(CombatClass.MELEE, CombatStyle.CRUSH, AttackStyle.AGGRESSIVE)
        if (!ignoresPrayer && target.isProtectedFrom(CombatClass.MELEE)) {
            target.hit(damage = 0, type = HitType.BLOCK, delay = 1)
            return false
        }
        val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
        target.hit(damage = if (landed) world.random(maxHit) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = 1)
        return landed
    }

    private fun Npc.projectileHit(target: Pawn, combatClass: CombatClass, maxHit: Int, gfx: Int, ignoresPrayer: Boolean = false): Boolean {
        val style = if (combatClass == CombatClass.MAGIC) CombatStyle.MAGIC else CombatStyle.RANGED
        prepareAttack(combatClass, style, AttackStyle.ACCURATE)
        world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 20))
        val delay = max(1, RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1)
        if (!ignoresPrayer && target.isProtectedFrom(combatClass)) {
            target.hit(damage = 0, type = HitType.BLOCK, delay = delay)
            return false
        }
        val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
        target.hit(damage = if (landed) world.random(maxHit) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = delay)
        return landed
    }

    private fun Npc.summon(key: String, count: Int) {
        repeat(count) {
            val t = Tile(tile.x + world.random(2) - 1, tile.z + world.random(2) - 1, tile.height)
            val n = Npc(getRSCM(key), t, world)
            n.respawns = false
            world.spawn(n)
            n.setActive(true)
        }
    }

    private fun Npc.spawnGuardians(): MutableList<Npc> {
        val out = mutableListOf<Npc>()
        repeat(2) {
            val t = Tile(tile.x + world.random(2) - 1, tile.z + world.random(2) - 1, tile.height)
            val n = Npc(getRSCM("npc.scorpias_guardian"), t, world)
            n.respawns = false
            world.spawn(n)
            n.setActive(true)
            out += n
        }
        return out
    }

    private fun forceUnequipWeapon(p: Player) {
        val item = p.getEquipment(EquipmentType.WEAPON) ?: return
        runCatching {
            p.equipment.remove(item.id, item.amount)
            p.inventory.add(item = item.id, amount = item.amount, assureFullInsertion = false)
        }
    }

    private fun forceUnequipRandom(p: Player) {
        val worn = EquipmentType.values().mapNotNull { p.getEquipment(it) }
        if (worn.isEmpty()) return
        val item = worn[world.random(worn.size - 1)]
        runCatching {
            p.equipment.remove(item.id, item.amount)
            p.inventory.add(item = item.id, amount = item.amount, assureFullInsertion = false)
        }
    }

    private companion object {
        const val GUARDIAN_HEAL = 8
        // Projectile gfx — best-known OSRS ids; TUNE (a wrong id is a cosmetic miss, never a throw).
        const val LIGHTNING_GFX = 280
        const val WEB_GFX = 1601
        const val MAGIC_GFX = 159
        const val BOOK_GFX = 1466

        // Per-boss attack animation (OSRS npc defs store none → set here). Best-known; TUNE in-game.
        val ATTACK_ANIMS = mapOf(
            "npc.callisto" to 4925,
            "npc.vetion" to 7660,
            "npc.venenatis" to 5322,
            "npc.scorpia" to 6256,
            "npc.chaos_elemental_2054" to 3146,
            "npc.chaos_fanatic" to 811,
            "npc.crazy_archaeologist" to 7071,
        )
    }
}
