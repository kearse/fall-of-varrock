package org.alter.plugins.content.combat.specialattack.weapons.dragonmace

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon mace (1434) special attack: "Shatter".
 * 25% energy, +25% accuracy, +50% damage, single hit.
 */
class DragonMacePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_mace", 25) {
            player.animate(id = 6147)
            player.graphic(id = 6148, height = 96)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.5)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
        }
    }
}
