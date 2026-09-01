package org.alter.plugins.content.combat.specialattack.weapons.dragonscimitar

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.mechanics.prayer.Prayers

/**
 * Dragon scimitar (4587) special attack: "Sever".
 * 55% energy, +25% accuracy, single hit. On a successful hit it disables the
 * target's active protection/overhead prayers.
 */
class DragonScimitarPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_scimitar", 55) {
            player.animate(id = 1872)
            player.graphic(id = 347, height = 96)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val victim = target
                if (victim is Player) {
                    // Disables ONLY overhead/protection prayers and blocks re-activation for
                    // 5 ticks — the old deactivateAll() also killed Piety/Rigour/etc., which
                    // the OSRS spec does not touch.
                    Prayers.disableOverheads(victim, cycles = 5)
                }
            }
        }
    }
}
