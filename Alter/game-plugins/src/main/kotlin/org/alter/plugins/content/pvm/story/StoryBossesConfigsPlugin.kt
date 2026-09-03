package org.alter.plugins.content.pvm.story

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for Zemouregal, the Convergence and Arrav the ally. Zemouregal's rev-228
 * model carries only stand/walk and one gesture (9876) — it doubles as attack and death (a
 * Mahjarrat withdraws rather than dies). The Nightmare form 9425 likewise has one spare
 * sequence (8634).
 */
class StoryBossesConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        setCombatDef(StoryBosses.ZEMOUREGAL_KEY) {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 14; searchDelay = 1 }
            stats { hitpoints = StoryBosses.ZEMOUREGAL_HP; attack = 220; strength = 200; defence = 200; magic = 260; ranged = 1 }
            bonuses { defenceStab = 120; defenceSlash = 120; defenceCrush = 80; defenceMagic = 180; defenceRanged = 150 }
            anims { attack = 9876; block = 9874; death = 9876 }
        }

        setCombatDef(StoryBosses.CONVERGENCE_KEY) {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 16; searchDelay = 1 }
            stats { hitpoints = StoryBosses.CONVERGENCE_HP; attack = 1; strength = 1; defence = 220; magic = 300; ranged = 1 }
            bonuses { defenceStab = 160; defenceSlash = 160; defenceCrush = 100; defenceMagic = 220; defenceRanged = 200 }
            anims { attack = 8634; block = 8593; death = 8634 }
        }

        // Arrav, freed: fights beside the player. Human archive anims.
        setCombatDef(StoryBosses.ARRAV_ALLY_KEY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 0; searchDelay = 1 }
            stats { hitpoints = StoryBosses.ARRAV_ALLY_HP; attack = 150; strength = 150; defence = 150; magic = 1; ranged = 1 }
            bonuses { defenceStab = 100; defenceSlash = 100; defenceCrush = 100; defenceMagic = 100; defenceRanged = 100 }
            anims { attack = 390; block = 823; death = 820 }
        }
    }
}
