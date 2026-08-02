package org.alter.plugins.content.combat

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.formula.RangedCombatFormula

/**
 * ::combatdebug — prints the live accuracy and max hit against your current combat
 * target, for spot-checking the formulas against the OSRS Wiki DPS calculator.
 */
class CombatDebugPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("combatdebug", description = "Show accuracy/max hit vs your current target") {
            val target = player.getCombatTarget()
            if (target == null) {
                player.message("You have no combat target — attack something first.")
                return@onCommand
            }
            val combatClass = CombatConfigs.getCombatClass(player)
            val formula =
                when (combatClass) {
                    CombatClass.MELEE -> MeleeCombatFormula
                    CombatClass.RANGED -> RangedCombatFormula
                    CombatClass.MAGIC -> MagicCombatFormula
                }
            val accuracy = formula.getAccuracy(player, target)
            val maxHit = formula.getMaxHit(player, target)
            player.message(
                "[$combatClass vs ${target.entityType}] hit chance: ${"%.2f".format(accuracy * 100)}%, max hit: $maxHit",
            )
        }
    }
}
