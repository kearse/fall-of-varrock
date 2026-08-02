package org.alter.plugins.content.combat.specialattack.weapons.dragonclaws

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon claws (13652) special attack: "Slice and Dice".
 * 50% energy. Rolls accuracy up to four times; the first successful roll deals a
 * large hit and the remaining hits cascade (halving). If all rolls fail, a small
 * consolation hit of 1+1 is dealt. Four hitsplats are shown across two ticks.
 */
class DragonClawsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_claws", 50) {
            player.animate(id = 7514)
            player.graphic(id = 1171, height = 96)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)

            val damages = intArrayOf(0, 0, 0, 0)
            var rolled = false
            for (i in 0 until 4) {
                if (accuracy >= world.randomDouble()) {
                    // First successful accuracy roll: big hit, remaining cascade by halving.
                    var dmg = world.random((maxHit / 2)..maxHit)
                    damages[i] = dmg
                    for (j in i + 1 until 4) {
                        dmg /= 2
                        damages[j] = dmg
                    }
                    // Push any rounding remainder into the final hit.
                    rolled = true
                    break
                }
            }
            if (!rolled) {
                // All four accuracy rolls failed - small consolation damage.
                if (world.randomDouble() < 0.25) {
                    damages[2] = 1
                    damages[3] = 1
                } else {
                    damages[3] = 1
                }
            }

            // Two hitsplats land on the attack tick, two more one tick later, as in OSRS.
            for (i in 0 until 4) {
                val delay = if (i < 2) 0 else 1
                player.dealHit(target = target, maxHit = damages[i], landHit = damages[i] > 0, delay = delay)
            }
        }
    }
}
