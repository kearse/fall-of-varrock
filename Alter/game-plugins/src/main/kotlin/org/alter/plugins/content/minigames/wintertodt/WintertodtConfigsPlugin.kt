package org.alter.plugins.content.minigames.wintertodt

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Pyromancers and the Wintertodt's flame are passive npcs; they need a combat def only so the
 * engine treats them as valid (hp / anims). Their real health is tracked by [WintertodtPlugin]
 * (the donor transforms rather than kills them).
 */
class WintertodtConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        listOf(Wintertodt.PYROMANCER_KEY, Wintertodt.INCAPACITATED_KEY).forEach { key ->
            setCombatDef(key) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 0; searchDelay = 1 }
                stats { hitpoints = Wintertodt.PYRO_MAX_HP; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 1 }
                anims { attack = 4425; block = 4430; death = 4427 }
            }
        }
    }
}
