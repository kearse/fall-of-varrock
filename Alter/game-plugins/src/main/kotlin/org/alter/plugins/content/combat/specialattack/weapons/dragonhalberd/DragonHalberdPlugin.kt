package org.alter.plugins.content.combat.specialattack.weapons.dragonhalberd

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Pawn
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import kotlin.math.abs
import kotlin.math.max

/**
 * Dragon halberd (3204) special attack: "Sweep".
 * 30% energy, +10% damage. Hits the target plus any other entity within one
 * tile of the target (up to a sane cap).
 */
class DragonHalberdPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_halberd", 30) {
            player.animate(id = 1203)

            val victims = LinkedHashSet<Pawn>()
            victims.add(target)
            world.npcs.forEach { npc ->
                if (npc != null && !npc.isDead() && max(abs(target.tile.x - npc.tile.x), abs(target.tile.z - npc.tile.z)) <= 1) {
                    victims.add(npc)
                }
            }

            victims.take(10).forEach { victim ->
                val maxHit = MeleeCombatFormula.getMaxHit(player, victim, specialAttackMultiplier = 1.1)
                val accuracy = MeleeCombatFormula.getAccuracy(player, victim, specialAttackMultiplier = 1.0)
                player.dealHit(target = victim, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
            }
        }
    }
}
