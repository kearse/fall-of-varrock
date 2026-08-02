package org.alter.plugins.content.combat.specialattack.weapons.magicshortbow

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy

/**
 * Magic shortbow (861) special attack: "Snapshot".
 * 55% energy. Fires two arrows in quick succession.
 */
class MagicShortbowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.magic_shortbow", 55) {
            player.animate(id = 1074)
            player.fireAmmoProjectile(target)

            // OSRS Snapshot: two arrows with 10% reduced accuracy, landing on the normal
            // ranged hit-delay tick for the distance (both together).
            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            for (i in 0 until 2) {
                val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
                val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 0.9)
                player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = delay)
            }
        }
    }
}
