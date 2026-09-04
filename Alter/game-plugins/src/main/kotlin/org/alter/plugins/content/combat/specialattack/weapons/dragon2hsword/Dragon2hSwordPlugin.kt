package org.alter.plugins.content.combat.specialattack.weapons.dragon2hsword

import org.alter.api.ext.isMulti
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.isPlayerAttackable
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import kotlin.math.abs
import kotlin.math.max

/**
 * Dragon 2h sword (7158) special attack: "Powerstab".
 * 60% energy. In MULTI-combat it hits the target plus every other attackable entity
 * within one tile of the attacker (up to 14 per OSRS wiki); in single-way it is a
 * normal single-target hit. Every extra victim goes through [Combat.canEngage] so the
 * spec can never damage players outside PvP zones or NPCs that aren't attackable.
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
            // The sweep only exists in multi-combat, and every extra victim must pass the
            // same engagement rules as a normal attack (PvP zoning, attackable flag) —
            // otherwise the spec is a way to damage bystanders anywhere in the world.
            if (PvpZones.isMulti(player.tile) || player.tile.isMulti(world)) {
                world.npcs.forEach { npc ->
                    if (npc != null && !npc.isDead() &&
                        npc.isPlayerAttackable() && npc.combatDef.hitpoints != -1 &&
                        max(abs(player.tile.x - npc.tile.x), abs(player.tile.z - npc.tile.z)) <= 1 &&
                        Combat.canEngage(player, npc)
                    ) {
                        victims.add(npc)
                    }
                }
                world.players.forEach { other ->
                    if (other != null && other != player && !other.isDead() &&
                        max(abs(player.tile.x - other.tile.x), abs(player.tile.z - other.tile.z)) <= 1 &&
                        Combat.canEngage(player, other)
                    ) {
                        victims.add(other)
                    }
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
