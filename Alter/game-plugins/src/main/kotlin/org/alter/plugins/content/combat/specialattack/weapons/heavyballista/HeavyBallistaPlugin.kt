package org.alter.plugins.content.combat.specialattack.weapons.heavyballista

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Heavy ballista (19481) special attack: "Concentrated Shot".
 * 65% energy, +25% accuracy and +25% damage, single shot.
 */
class HeavyBallistaPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.heavy_ballista", 65) {
            player.animate(id = 7218)
            player.fireAmmoProjectile(target)

            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.25)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 2)
        }
    }
}
