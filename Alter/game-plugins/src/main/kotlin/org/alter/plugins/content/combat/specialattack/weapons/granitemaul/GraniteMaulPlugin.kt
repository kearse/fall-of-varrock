package org.alter.plugins.content.combat.specialattack.weapons.granitemaul

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Granite maul (4153) special attack: "Quick Smash".
 * 50% energy, single hit. Fires on the next attack once the spec orb is enabled. (50% — the real
 * OSRS cost — so an AGS 50% + maul 50% combo fits a single energy bar.)
 */
class GraniteMaulPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.granite_maul", 50) {
            player.animate(id = 1667)
            player.graphic(id = 340, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
        }
    }
}
