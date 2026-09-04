package org.alter.plugins.content.bosses.wilderness

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the Wilderness bosses and their adds — Kronos
 * `data/npcs/combat/<Name>.json` verbatim.
 */
class WildernessBossesConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Callisto (470): hp 255, 350/370/440, 4 ticks, respawn 50; anims 4925/4927/4929; poison+venom immune.
        // Keyed on npc 6503 (classic model, archive 1287 — the skeleton these anims belong to); see
        // WildernessBosses.CALLISTO_KEY for why not the rework's 6609.
        setCombatDef(WildernessBosses.CALLISTO_KEY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 50 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 350; strength = 370; defence = 440; magic = 1; ranged = 1 }
            bonuses { defenceStab = 135; defenceSlash = 104; defenceCrush = 175; defenceMagic = 900; defenceRanged = 230 }
            anims { attack = 4925; block = 4927; death = 4929 }
        }

        // Vet'ion (454) + Vet'ion Reborn: hp 255, 430/430/395/mag 300, 4 ticks; anims 5499/5508/5503.
        // Both forms hand-managed (form 1 dies into the reborn form; the reborn pays out) — respawnDelay 0.
        listOf(WildernessBosses.VETION_KEY, WildernessBosses.VETION_REBORN_KEY).forEach { key ->
            setCombatDef(key) {
                species { +NpcSpecies.UNDEAD }
                immunities { venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 10; searchDelay = 1 }
                stats { hitpoints = 255; attack = 430; strength = 430; defence = 395; magic = 300; ranged = 1 }
                bonuses { defenceStab = 201; defenceSlash = 200; defenceCrush = -10; defenceMagic = 250; defenceRanged = 270 }
                anims { attack = 5499; block = 5508; death = 5503 }
            }
        }
        // Skeleton hellhounds (Vet'ion's pets): 110 hp / 190 hp, melee, anims 6559/6557/6558.
        setCombatDef(WildernessBosses.HELLHOUND_KEY) {
            species { +NpcSpecies.UNDEAD }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 110; attack = 210; strength = 250; defence = 150; magic = 1; ranged = 1 }
            bonuses { defenceStab = 101; defenceSlash = 103; defenceCrush = 10; defenceMagic = 180; defenceRanged = 266 }
            anims { attack = 6559; block = 6557; death = 6558 }
        }
        setCombatDef(WildernessBosses.GREATER_HELLHOUND_KEY) {
            species { +NpcSpecies.UNDEAD }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 190; attack = 240; strength = 310; defence = 220; magic = 1; ranged = 1 }
            bonuses { defenceStab = 150; defenceSlash = 163; defenceCrush = 20; defenceMagic = 210; defenceRanged = 275 }
            anims { attack = 6559; block = 6557; death = 6558 }
        }

        // Venenatis (464): hp 255, 270/290/290/mag 75, 4 ticks, respawn 16; anims 5319/5320/5321; poison+venom immune.
        setCombatDef(WildernessBosses.VENENATIS_KEY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 16 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 270; strength = 290; defence = 290; magic = 75; ranged = 1 }
            bonuses { defenceStab = 160; defenceSlash = 160; defenceCrush = 160; defenceMagic = 550; defenceRanged = 50 }
            anims { attack = 5319; block = 5320; death = 5321 }
        }

        // Scorpia (225): hp 200, 250/150/180, 4 ticks, respawn 16; anims 6254/6255/6256; venom immune.
        setCombatDef(WildernessBosses.SCORPIA_KEY) {
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 16 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 200; attack = 250; strength = 150; defence = 180; magic = 1; ranged = 1 }
            bonuses {
                attackStab = 60; attackSlash = 60; attackCrush = 60; attackMagic = 60; attackRanged = 60
                defenceStab = 246; defenceSlash = 284; defenceCrush = 284; defenceMagic = 44; defenceRanged = 284
            }
            anims { attack = 6254; block = 6255; death = 6256 }
        }
        // Scorpia's guardians (47): 70 hp healers that never attack; anims 6254/6255/6256.
        setCombatDef(WildernessBosses.GUARDIAN_KEY) {
            immunities { venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            stats { hitpoints = 70; attack = 1; strength = 1; defence = 60; magic = 30; ranged = 30 }
            anims { attack = 6254; block = 6255; death = 6256 }
        }

        // Chaos Elemental (305): hp 250, 270 across the board, 5 ticks, respawn 150; anims 3146/3145/3147; venom immune.
        setCombatDef(WildernessBosses.CHAOS_ELEMENTAL_KEY) {
            immunities { venom = true }
            configs { attackSpeed = 5; respawnDelay = 150 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 250; attack = 270; strength = 270; defence = 270; magic = 270; ranged = 270 }
            bonuses { defenceStab = 70; defenceSlash = 70; defenceCrush = 70; defenceMagic = 70; defenceRanged = 70 }
            anims { attack = 3146; block = 3145; death = 3147 }
        }

        // Chaos Fanatic (202): hp 225, def 220 / mag 200, 2 ticks (donor), respawn 16; anims 1979/425/2304.
        setCombatDef(WildernessBosses.CHAOS_FANATIC_KEY) {
            configs { attackSpeed = 2; respawnDelay = 16 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 225; attack = 1; strength = 1; defence = 220; magic = 200; ranged = 1 }
            bonuses { attackRanged = 75; defenceStab = 260; defenceSlash = 260; defenceCrush = 250; defenceMagic = 280; defenceRanged = 80 }
            anims { attack = 1979; block = 425; death = 2304 }
        }

        // Crazy Archaeologist (204): hp 225, 160/90/240/rng 180, 4 ticks, respawn 50; anims 3353/425/2304.
        setCombatDef(WildernessBosses.CRAZY_ARCHAEOLOGIST_KEY) {
            configs { attackSpeed = 4; respawnDelay = 50 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 225; attack = 160; strength = 90; defence = 240; magic = 1; ranged = 180 }
            bonuses {
                attackStab = 250; attackSlash = 250; attackCrush = 250; attackMagic = 250; attackRanged = 75
                defenceStab = 5; defenceSlash = 5; defenceCrush = 30; defenceMagic = 250; defenceRanged = 250
            }
            anims { attack = 3353; block = 425; death = 2304 }
        }
    }
}
