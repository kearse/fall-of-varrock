package org.alter.plugins.content.npcs

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * The small Lumbridge cattle-field rats (`npc.rat_2854`) — the ones that don't
 * even show on the minimap. They should be trivial 2 HP kills. This is scoped to
 * the small rat id only; giant/zombie/angry rats elsewhere keep their own defs.
 */
class RatPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        setCombatDef("npc.rat_2854") {
            configs {
                attackSpeed = 4
                respawnDelay = 25
                poisonChance = 0.0
                venomChance = 0.0
            }
            stats {
                hitpoints = 2
                attack = 1
                strength = 1
                defence = 1
                magic = 1
                ranged = 1
            }
            // The combat-def builder requires a death animation; this override previously omitted
            // it (masked by stale incremental compilation). 836 is the engine default — swap in the
            // real rat death anim if a nicer one is known.
            anims {
                death = 836
            }
        }
    }
}
