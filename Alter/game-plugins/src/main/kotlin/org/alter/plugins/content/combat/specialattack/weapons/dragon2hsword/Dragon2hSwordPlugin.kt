package org.alter.plugins.content.combat.specialattack.weapons.dragon2hsword

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
 * Dragon 2h sword (7158) special attack: "Powerstab".
 * 60% energy. Hits the target plus every other entity within one tile of the
 * attacker (up to a sane cap).
 */
class Dragon2hSwordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_2h_sword", 60) {
            player.animate(id = 3157)
            player.graphic(id = 559, height = 96)

            val victims = LinkedHashSet<Pawn>()
            victims.add(target)
            world.npcs.forEach { npc ->
                if (npc != null && !npc.isDead() && max(abs(player.tile.x - npc.tile.x), abs(player.tile.z - npc.tile.z)) <= 1) {
                    victims.add(npc)
                }
            }
            world.players.forEach { other ->
                if (other != null && other != player && !other.isDead() && max(abs(player.tile.x - other.tile.x), abs(player.tile.z - other.tile.z)) <= 1) {
                    victims.add(other)
                }
            }

            victims.take(11).forEach { victim ->
                val maxHit = MeleeCombatFormula.getMaxHit(player, victim, specialAttackMultiplier = 1.0)
                val accuracy = MeleeCombatFormula.getAccuracy(player, victim, specialAttackMultiplier = 1.0)
                player.dealHit(target = victim, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
            }
        }
    }
}
