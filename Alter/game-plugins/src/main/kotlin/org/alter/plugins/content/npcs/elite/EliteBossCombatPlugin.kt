package org.alter.plugins.content.npcs.elite

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.*
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.bosses.bossSummon
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.mechanics.poison.poison

/**
 * **Elite / endgame boss combat** (the KBD attack-loop + shared `BossCombat.kt` primitives,
 * with **HP-driven phases**):
 *  - **Vorkath** — dragonfire / ranged / melee, an ice attack that **freezes** you, and the
 *    occasional **zombified spawn** add.
 *  - **Alchemical Hydra** — four phases that **alternate the prayer you need** (ranged ↔ magic)
 *    plus acid (venom).
 *  - **Phantom Muspah** — rotates ranged / magic / melee (pray-switch).
 *  - **Abyssal Sire** — melee + magic, **scion** spawns per phase, miasma (venom).
 *  - **Nex** — four elemental phases, each summoning its minion (Fumus→Umbra→Cruor→Glacies),
 *    the ice phase freezing you.
 *  - **Grotesque Guardians** — **Dusk** (melee) + **Dawn** (ranged) fight side by side.
 *
 * Protection prayers block the matching style ([bossMelee]/[bossProjectile] via `isProtectedFrom`).
 * Faithful-but-simplified vs OSRS (documented): exact phase scripts, acid/lightning pool tiles,
 * the Sire stun-the-vent mechanic, Hydra's flame wall, and Nex's full special set are deferred.
 * Projectile gfx are TUNE.
 */
class EliteBossCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val reachedPhase = AttributeKey<Int>()

    init {
        EliteBosses.all.forEach { boss ->
            onNpcCombat(boss.key) { npc.queue { npc.combat(this, boss) } }
        }
    }

    private suspend fun Npc.combat(it: QueueTask, boss: EliteBosses.EliteBoss) {
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

    private fun Npc.attack(target: Pawn, boss: EliteBosses.EliteBoss) {
        ATTACK_ANIMS[boss.key]?.let { if (it > 0) animate(it) }
        when (boss.key) {
            "npc.vorkath" -> vorkath(target, boss)
            "npc.alchemical_hydra" -> hydra(target, boss)
            "npc.phantom_muspah" -> muspah(target, boss)
            "npc.abyssal_sire" -> sire(target, boss)
            "npc.nex" -> nex(target, boss)
            "npc.dusk" -> bossMelee(target, boss.maxHit)
            "npc.dawn" -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
        }
    }

    private fun Npc.vorkath(target: Pawn, boss: EliteBosses.EliteBoss) {
        when (world.random(5)) {
            0, 1 -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = DRAGONFIRE_GFX)
            2 -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            3 -> bossMelee(target, boss.maxHit)
            4 -> if (bossProjectile(target, CombatClass.MAGIC, boss.maxHit - 6, gfx = ICE_GFX) && target is Player) {
                target.freeze(cycles = 8) { target.message("Vorkath's icy breath freezes you solid!") }
            }
            else -> {
                bossSummon("npc.zombified_spawn", 1)
                if (target is Player) target.message("<col=8f0000>Vorkath spits out a zombified spawn!</col>")
            }
        }
    }

    private fun Npc.hydra(target: Pawn, boss: EliteBosses.EliteBoss) {
        val ph = phaseOf(4)
        if (enteredNewPhase(ph) && target is Player) target.message("<col=8f0000>The Hydra shifts — switch your prayer!</col>")
        val style = if (ph % 2 == 0) CombatClass.RANGED else CombatClass.MAGIC
        bossProjectile(target, style, boss.maxHit, gfx = if (style == CombatClass.RANGED) RANGE_GFX else MAGIC_GFX)
        if (world.chance(1, 4) && target is Player) target.poison(initialDamage = 8) { target.message("Hydra's acid burns you.") }
    }

    private fun Npc.muspah(target: Pawn, boss: EliteBosses.EliteBoss) {
        when (world.random(3)) {
            0, 1 -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            2 -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
            else -> bossMelee(target, boss.maxHit)
        }
    }

    private fun Npc.sire(target: Pawn, boss: EliteBosses.EliteBoss) {
        val ph = phaseOf(3)
        if (enteredNewPhase(ph)) {
            bossSummon("npc.scion", 2)
            if (target is Player) target.message("<col=8f0000>The Abyssal Sire summons its spawn!</col>")
        }
        if (world.random(1) == 0) bossMelee(target, boss.maxHit)
        else bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
        if (world.chance(1, 3) && target is Player) target.poison(initialDamage = 6) { target.message("The miasma seeps into you.") }
    }

    private fun Npc.nex(target: Pawn, boss: EliteBosses.EliteBoss) {
        val ph = phaseOf(4)
        if (enteredNewPhase(ph)) {
            bossSummon(NEX_MINIONS[ph], 1)
            if (target is Player) target.message("<col=8f0000>Nex: ${NEX_PHASE_NAMES[ph]}!</col>")
        }
        when (ph) {
            0 -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
            1 -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            2 -> bossMelee(target, boss.maxHit)
            else -> if (bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = ICE_GFX) && target is Player && world.chance(1, 3)) {
                target.freeze(cycles = 6) { target.message("Nex: Frozen in your tracks!") }
            }
        }
    }

    /** Current 0..n-1 phase by HP lost (0 = full, n-1 = nearly dead). */
    private fun Npc.phaseOf(n: Int): Int {
        val max = getMaxHp().coerceAtLeast(1)
        return ((max - getCurrentHp()) * n / max).coerceIn(0, n - 1)
    }

    /** True the first time this boss reaches [ph] (one-time phase-entry trigger). */
    private fun Npc.enteredNewPhase(ph: Int): Boolean {
        val prev = attr[reachedPhase] ?: -1
        if (ph > prev) { attr[reachedPhase] = ph; return true }
        return false
    }

    private companion object {
        const val MAGIC_GFX = 159
        const val RANGE_GFX = 1466
        const val DRAGONFIRE_GFX = 393
        const val ICE_GFX = 369
        val NEX_MINIONS = listOf("npc.fumus", "npc.umbra", "npc.cruor", "npc.glacies")
        val NEX_PHASE_NAMES = listOf("Fear the shadow!", "Darken the sky!", "Drain your life!", "Embrace darkness!")

        // Per-boss attack animation (OSRS npc defs store none → set here). Best-known; TUNE in-game.
        val ATTACK_ANIMS = mapOf(
            "npc.vorkath" to 7952,
            "npc.alchemical_hydra" to 8261,
            "npc.phantom_muspah" to 9963,
            "npc.abyssal_sire" to 4532,
            "npc.nex" to 9181,
            "npc.dusk" to 7799,
            "npc.dawn" to 7794,
        )
    }
}
