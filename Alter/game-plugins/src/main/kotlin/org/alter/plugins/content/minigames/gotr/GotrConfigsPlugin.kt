package org.alter.plugins.content.minigames.gotr

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Abyssal creatures for the rift (the Abyss models — attackable in 228). Leeches are weak and
 * many, walkers are the barrier-breakers, guardians hit players. The Great Guardian and the
 * Rewards Guardian are passive (no combat def needed beyond hp).
 */
class GotrConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        setCombatDef(Gotr.LEECH_KEY) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 0; searchDelay = 1 }
            stats { hitpoints = 30; attack = 40; strength = 40; defence = 20; magic = 1; ranged = 1 }
            anims { attack = 2182; block = 2181; death = 2183 }
        }
        setCombatDef(Gotr.WALKER_KEY) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 0; searchDelay = 1 }
            stats { hitpoints = 80; attack = 70; strength = 80; defence = 50; magic = 1; ranged = 1 }
            anims { attack = 2192; block = 2193; death = 2194 }
        }
        setCombatDef(Gotr.GUARDIAN_KEY) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 60; attack = 60; strength = 60; defence = 40; magic = 1; ranged = 1 }
            anims { attack = 2186; block = 2187; death = 2189 }
        }
    }
}
