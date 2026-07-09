package org.alter.plugins.content.combat.specialattack.weapons.darkbow

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dark bow (11235) special attack: "Descent of Dragons".
 * 55% energy. Fires two arrows at +50% damage; each arrow that lands deals a
 * minimum of 8 damage (the dragon-arrow floor).
 */
class DarkBowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dark_bow", 55) {
            player.animate(id = 2890)
            player.fireAmmoProjectile(target)

            for (i in 0 until 2) {
                val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.5)
                val accuracy = RangedCombatFormula.getAccuracy(player, target)
                val landHit = accuracy >= world.randomDouble()
                val delay = 2 + i
                if (landHit) {
                    val damage = maxOf(8, world.random(maxHit))
                    player.dealExactHit(target = target, damage = damage, delay = delay)
                } else {
                    player.dealHit(target = target, maxHit = maxHit, landHit = false, delay = delay)
                }
            }
        }
    }
}
