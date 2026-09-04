package org.alter.plugins.content.combat.specialattack.weapons.webweaverbow

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.mechanics.poison.Poison

/**
 * Webweaver bow (27655) special attack: "Swarm" — OSRS Wiki.
 * 50% energy. Four consecutive shots, each up to 40% (rounded up) of the max hit, at DOUBLE
 * accuracy; each landed shot has a chance to poison (starting at 4). No ammunition — the bow
 * generates its own, so nothing is drawn from the quiver. Missing until 2026-09-03.
 */
class WebweaverBowPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.webweaver_bow", 50) {
            player.animate(id = 426)
            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            repeat(SHOTS) { i ->
                val full = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
                val maxHit = (full * SHOT_FRACTION_PCT + 99) / 100 // ceil(40%)
                val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
                val landed = accuracy >= world.randomDouble()
                player.dealHit(target = target, maxHit = maxHit, landHit = landed, delay = delay + i / 2) { hit ->
                    WeaponEffects.applyOnHit(player, target, hit, combatClass = CombatClass.RANGED)
                    if (hit.landed && world.chance(1, POISON_ONE_IN)) Poison.poison(target, initialDamage = POISON_DAMAGE)
                }
            }
        }
    }

    private companion object {
        const val SHOTS = 4
        const val SHOT_FRACTION_PCT = 40
        const val POISON_ONE_IN = 4
        const val POISON_DAMAGE = 4
    }
}
