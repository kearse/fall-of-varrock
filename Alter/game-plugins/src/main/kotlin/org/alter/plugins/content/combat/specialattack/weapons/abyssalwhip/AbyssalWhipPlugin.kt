package org.alter.plugins.content.combat.specialattack.weapons.abyssalwhip

import org.alter.api.ext.sendRunEnergy
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Abyssal whip (4151) special attack: "Energy Drain".
 * 50% energy, +25% accuracy, normal damage. Against a player it also transfers
 * 10% of the target's run energy to the attacker (OSRS Wiki, Abyssal whip).
 */
class AbyssalWhipPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.abyssal_whip", 50) {
            player.animate(id = 1658)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            val landHit = accuracy >= world.randomDouble()
            val victim = target
            val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = 0)

            if (landHit && victim is Player) {
                pawnHit.hit.addAction {
                    val stolen = victim.runEnergy * 0.10
                    if (stolen > 0) {
                        victim.runEnergy = (victim.runEnergy - stolen).coerceAtLeast(0.0)
                        victim.sendRunEnergy(victim.runEnergy.toInt())
                        player.runEnergy = (player.runEnergy + stolen).coerceAtMost(10000.0)
                        player.sendRunEnergy(player.runEnergy.toInt())
                    }
                }
            }
        }
    }
}
