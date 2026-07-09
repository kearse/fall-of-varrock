package org.alter.plugins.content.combat.specialattack.weapons.zamorakgodsword

import org.alter.api.ext.secondsToTicks
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Zamorak godsword (11808) special attack: "Ice Cleave".
 * 50% energy, +100% accuracy, +10% damage. On a successful hit it freezes the
 * target in place for 20 seconds.
 */
class ZamorakGodswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.zamorak_godsword", 50) {
            player.animate(id = 7638)
            player.graphic(id = 1221, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.10)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                target.graphic(id = 369)
                target.timers[FROZEN_TIMER] = 20.secondsToTicks()
            }
        }
    }
}
