package org.alter.plugins.content.combat.specialattack.weapons.armadylcrossbow

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
 * Armadyl crossbow (11785) special attack: "Armadyl Eye".
 * 40% energy, double accuracy shot (OSRS Wiki, Armadyl crossbow). The guaranteed
 * enchanted-bolt proc is not yet modelled — the shot uses the normal proc roll.
 */
class ArmadylCrossbowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.armadyl_crossbow", 40) {
            player.animate(id = 4230)
            player.fireAmmoProjectile(target)

            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = delay)
        }
    }
}
