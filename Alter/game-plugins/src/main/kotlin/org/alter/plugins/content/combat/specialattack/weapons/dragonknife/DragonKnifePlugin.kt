package org.alter.plugins.content.combat.specialattack.weapons.dragonknife

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon knife (22804) special attack: "Rapid Fire".
 * 25% energy. Throws two dragon knives in quick succession.
 */
class DragonKnifePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_knife", 25) {
            player.animate(id = 8291)
            player.fireAmmoProjectile(target)

            for (i in 0 until 2) {
                val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
                val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
                player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1 + i)
            }
        }
    }
}
