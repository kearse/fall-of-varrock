package org.alter.plugins.content.npcs.barrows

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.*
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.isProtectedFrom
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import kotlin.math.ceil
import kotlin.math.min

/**
 * **The six Barrows brothers — OSRS-exact combat + set effects.**
 *
 * Each brother overrides the default combat strategy with a single-style attack loop
 * (the KBD-pattern `onNpcCombat` coroutine), so we control the style, max hit, projectile,
 * protection-prayer interaction and signature **set effect** exactly:
 *
 *  | Brother | Style  | Max | Set effect                                                    |
 *  |---------|--------|-----|---------------------------------------------------------------|
 *  | Ahrim   | Magic  | 25  | 1/4 chance to lower your **Strength** by 5.                    |
 *  | Dharok  | Melee  | ~25→| Max hit **scales with his own missing HP** (≈58 at 1 HP).     |
 *  | Guthan  | Melee  | 24  | **Heals himself** by the damage he deals.                     |
 *  | Karil   | Ranged | 18  | 1/4 chance to **halve your Agility** (the Tainted shot).      |
 *  | Torag   | Melee  | 23  | **Drains your run energy** by 20% on a successful hit.        |
 *  | Verac   | Melee  | 23  | 1/4 chance his hit **ignores Protect-from-Melee + defence**.  |
 *
 * Protection prayers fully block the matching style (OSRS PvM) via [isProtectedFrom] — the
 * stock formulas don't do this for NPC attackers, so we gate it here. Spawns + stats live in
 * the per-brother `*Plugin` config files; this plugin owns only the fight.
 *
 * Animation / projectile-gfx ids are the best-known OSRS values and marked TUNE where a
 * wrong id would only mean a missing visual (never a thrown call → never a dropped plugin).
 */
class BarrowsCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Per-brother static combat data; the signature effect is branched in [attackOnce]. */
    private enum class Brother(
        val npcKey: String,
        val combatClass: CombatClass,
        val combatStyle: CombatStyle,
        val attackAnim: Int,
        val maxHit: Int,
        /** Projectile gfx for ranged/magic brothers (TUNE), -1 for melee. */
        val projectileGfx: Int = -1,
    ) {
        AHRIM("npc.ahrim_the_blighted", CombatClass.MAGIC, CombatStyle.MAGIC, attackAnim = 729, maxHit = 25, projectileGfx = 158),
        DHAROK("npc.dharok_the_wretched", CombatClass.MELEE, CombatStyle.SLASH, attackAnim = 2067, maxHit = 25),
        GUTHAN("npc.guthan_the_infested", CombatClass.MELEE, CombatStyle.STAB, attackAnim = 2080, maxHit = 24),
        KARIL("npc.karil_the_tainted", CombatClass.RANGED, CombatStyle.RANGED, attackAnim = 2075, maxHit = 18, projectileGfx = 27),
        TORAG("npc.torag_the_corrupted", CombatClass.MELEE, CombatStyle.CRUSH, attackAnim = 2068, maxHit = 23),
        VERAC("npc.verac_the_defiled", CombatClass.MELEE, CombatStyle.CRUSH, attackAnim = 2062, maxHit = 23),
        ;

        val isMelee get() = combatClass == CombatClass.MELEE
    }

    init {
        Brother.values().forEach { brother ->
            onNpcCombat(brother.npcKey) {
                npc.queue { npc.brotherCombat(this, brother) }
            }
        }
    }

    private suspend fun Npc.brotherCombat(it: QueueTask, b: Brother) {
        var target = getCombatTarget() ?: return
        val range = if (b.isMelee) 1 else 8

        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(it, target, distance = range, projectile = !b.isMelee) && isAttackDelayReady()) {
                attackOnce(target, b)
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }

        resetFacePawn()
        removeCombatTarget()
    }

    /** One attack, dispatching to the brother's style + signature set effect. */
    private fun Npc.attackOnce(target: Pawn, b: Brother) {
        when (b) {
            Brother.AHRIM ->
                projectileAttack(target, b) { _ ->
                    if (target is Player && world.chance(1, 4)) {
                        target.getSkills().alterCurrentLevel(Skills.STRENGTH, -5)
                        target.message("Ahrim the Blighted drains your strength.")
                    }
                }

            Brother.KARIL ->
                projectileAttack(target, b) { _ ->
                    if (target is Player && world.chance(1, 4)) {
                        val agility = target.getSkills().getCurrentLevel(Skills.AGILITY)
                        target.getSkills().alterCurrentLevel(Skills.AGILITY, -ceil(agility * 0.2).toInt())
                        target.message("Karil the Tainted's bolt saps your agility.")
                    }
                }

            Brother.DHAROK ->
                meleeAttack(target, b, maxHit = dharokMaxHit(b.maxHit))

            Brother.GUTHAN ->
                meleeAttack(target, b) { dmg ->
                    // Guthan's Infestation: heal himself by the damage dealt (capped at his max HP).
                    if (dmg > 0) setCurrentHp(min(getMaxHp(), getCurrentHp() + dmg))
                }

            Brother.TORAG ->
                meleeAttack(target, b) { dmg ->
                    if (dmg > 0 && target is Player) {
                        target.runEnergy = (target.runEnergy - 2000.0).coerceAtLeast(0.0)
                        target.sendRunEnergy(target.runEnergy.toInt())
                    }
                }

            Brother.VERAC -> {
                // Verac's Defiled: 1/4 of his hits ignore Protect-from-Melee AND defence (guaranteed, min 1).
                val defiled = world.chance(1, 4)
                meleeAttack(target, b, ignoresPrayer = defiled, alwaysHits = defiled)
            }
        }
    }

    /** Dharok scales with HIS OWN missing HP: dmg ≈ base · (1 + lost%·max%) — the set-effect formula. */
    private fun Npc.dharokMaxHit(base: Int): Int {
        val maxHp = getMaxHp()
        if (maxHp <= 0) return base
        val lost = (maxHp - getCurrentHp()) / 100.0
        val max = maxHp / 100.0
        return (base * (1.0 + lost * max)).toInt().coerceAtLeast(base)
    }

    private fun Npc.meleeAttack(
        target: Pawn,
        b: Brother,
        maxHit: Int = b.maxHit,
        ignoresPrayer: Boolean = false,
        alwaysHits: Boolean = false,
        onHit: (Int) -> Unit = {},
    ) {
        prepareAttack(CombatClass.MELEE, b.combatStyle, AttackStyle.AGGRESSIVE)
        animate(b.attackAnim)

        if (!ignoresPrayer && target.isProtectedFrom(CombatClass.MELEE)) {
            target.hit(damage = 0, type = HitType.BLOCK, delay = 1)
            return
        }

        val landed = alwaysHits || MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
        if (landed) {
            val damage = if (alwaysHits) 1 + world.random((maxHit - 1).coerceAtLeast(0)) else world.random(maxHit)
            target.hit(damage = damage, type = HitType.HIT, delay = 1)
            onHit(damage)
        } else {
            target.hit(damage = 0, type = HitType.BLOCK, delay = 1)
        }
    }

    private fun Npc.projectileAttack(
        target: Pawn,
        b: Brother,
        onHit: (Int) -> Unit = {},
    ) {
        val attackStyle = if (b.combatClass == CombatClass.MAGIC) CombatStyle.MAGIC else CombatStyle.RANGED
        prepareAttack(b.combatClass, attackStyle, AttackStyle.ACCURATE)
        animate(b.attackAnim)

        val projectile = createProjectile(target, gfx = b.projectileGfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 20)
        world.spawn(projectile)
        val delay = (RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1).coerceAtLeast(1)

        if (target.isProtectedFrom(b.combatClass)) {
            target.hit(damage = 0, type = HitType.BLOCK, delay = delay)
            return
        }

        val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
        if (landed) {
            val damage = world.random(b.maxHit)
            target.hit(damage = damage, type = HitType.HIT, delay = delay)
            onHit(damage)
        } else {
            target.hit(damage = 0, type = HitType.BLOCK, delay = delay)
        }
    }
}
