package org.alter.plugins.content.combat.specialattack.weapons.voidwaker

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import kotlin.math.abs
import kotlin.math.max

/**
 * Voidwaker (item 27690) special attack: "Disrupt".
 *
 * Custom mechanics (LOF):
 *  - Costs 1% special energy.
 *  - Always hits (the accuracy roll is bypassed entirely - ignores defence).
 *  - Player/bot target (bots are real [Player]s): instantly kills ONLY the single
 *    target you are attacking (deals damage equal to its current HP). No AoE on
 *    players.
 *  - NPC target: AoE — instantly kills every *attackable* NPC within a 5-tile
 *    radius of the target, EXCEPT Knights of Lumbridge (the Saradomin defenders,
 *    npc ids 2213/2214). Non-combat NPCs (shopkeepers, bankers, etc.) are spared.
 *
 * Visual IDs sourced from the rsmod symbol tables (cache name -> id):
 *  - seq 11275      = human_special02_voidwaker (player animation)
 *  - spotanim 2834  = fx_voidwaker02_special (cast graphic on the player)
 *  - spotanim 2363  = fx_voidwaker_impact (impact graphic on the target)
 */
class VoidwakerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {

        val SPECIAL_REQUIREMENT = 1
        val RADIUS = 5

        // Knights of Lumbridge (Saradomin defenders) are immune to the AoE.
        val IMMUNE_NPC_IDS = setOf(2213, 2214)

        SpecialAttacks.register("item.voidwaker", SPECIAL_REQUIREMENT) {
            player.animate(id = 11275)
            player.graphic(id = 2834, height = 124)

            val victim = target
            if (victim is Player) {
                // Player or bot: single-target instakill on the one you're attacking.
                player.dealExactHit(target = victim, damage = victim.getCurrentHp(), delay = 1)
                victim.graphic(id = 2363)
            } else {
                // NPC target: 5-tile AoE instakill of attackable NPCs (Knights spared).
                world.npcs.forEach { npc ->
                    if (npc != null &&
                        !npc.isDead() &&
                        npc.def.isAttackable() &&
                        npc.id !in IMMUNE_NPC_IDS &&
                        max(abs(victim.tile.x - npc.tile.x), abs(victim.tile.z - npc.tile.z)) <= RADIUS
                    ) {
                        player.dealExactHit(target = npc, damage = npc.getCurrentHp(), delay = 1)
                        npc.graphic(id = 2363)
                    }
                }
            }
        }
    }
}
