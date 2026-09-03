package org.alter.plugins.content.bosses.lairs

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the lair bosses — Kronos `data/npcs/combat/<Name>.json` verbatim
 * (levels, bonuses, attack ticks, respawn ticks, anims). Attack anims for the scripted
 * fights are applied per attack in [LairBossesCombatPlugin]; the def carries the default.
 */
class LairBossesConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // King Black Dragon — King_black_dragon.json (276): hp 240, 240/240/240/mag 240, 7 ticks,
        // respawn 50, attack 80 / block 89 / death 92. Poisonable (donor).
        setCombatDef("npc.king_black_dragon") {
            species { +NpcSpecies.DRACONIC; +NpcSpecies.BASIC_DRAGON }
            configs { attackSpeed = 7; respawnDelay = 50 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 240; attack = 240; strength = 240; defence = 240; magic = 240; ranged = 1 }
            bonuses { defenceStab = 70; defenceSlash = 90; defenceCrush = 90; defenceMagic = 80; defenceRanged = 70 }
            anims { attack = 80; block = 89; death = 92 }
        }

        // Giant Mole — Giant_mole.json (230): hp 200, 200/200/200/mag 200, 4 ticks, respawn 15,
        // attack 3312 / block 3311 / death 3310; venom-immune.
        setCombatDef("npc.giant_mole") {
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 15 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 200; attack = 200; strength = 200; defence = 200; magic = 200; ranged = 1 }
            bonuses { defenceStab = 60; defenceSlash = 80; defenceCrush = 100; defenceMagic = 80; defenceRanged = 60 }
            anims { attack = 3312; block = 3311; death = 3310 }
        }

        // Kalphite Queen — Kalphite_queen.json (333), two forms. Form 1 (963): flying, weak to
        // crush only (stab/slash 50, crush 10, magic/ranged 100 — the donor's "1.5× defence vs
        // ranged/magic" preDefend is folded into the bonuses); form 2 (965): melee-armoured,
        // magic/ranged 10. hp 255 each, 4 ticks, venom-immune; death 6242 / 6233.
        setCombatDef(LairBosses.KQ_FORM_1) {
            species { +NpcSpecies.KALPHITE }
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 300; strength = 300; defence = 300; magic = 150; ranged = 1 }
            bonuses { defenceStab = 50; defenceSlash = 50; defenceCrush = 10; defenceMagic = 150; defenceRanged = 150 }
            anims { attack = 6241; death = 6242 }
        }
        setCombatDef(LairBosses.KQ_FORM_2) {
            species { +NpcSpecies.KALPHITE }
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 300; strength = 300; defence = 300; magic = 150; ranged = 1 }
            bonuses { defenceStab = 150; defenceSlash = 150; defenceCrush = 150; defenceMagic = 10; defenceRanged = 10 }
            anims { attack = 1178; block = 6237; death = 6233 }
        }

        // Dagannoth Kings — Dagannoth_{rex,prime,supreme}.json (303): hp 255, 4 ticks, respawn
        // 100, block 2852 / death 2856. Each king is near-immune to two styles and open to one:
        // Rex (melee) is weak to magic (magic def 10); Prime (magic) to ranged (ranged def 10);
        // Supreme (ranged) to melee (melee defs 10, ranged def 550).
        setCombatDef("npc.dagannoth_rex") {
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 100 }
            aggro { radius = 6; searchDelay = 1 }
            stats { hitpoints = 255; attack = 255; strength = 255; defence = 255; magic = 1; ranged = 255 }
            bonuses { defenceStab = 255; defenceSlash = 255; defenceCrush = 255; defenceMagic = 10; defenceRanged = 255 }
            anims { attack = 2851; block = 2852; death = 2856 }
        }
        setCombatDef("npc.dagannoth_prime") {
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 100 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 255; strength = 255; defence = 255; magic = 255; ranged = 1 }
            bonuses { defenceStab = 255; defenceSlash = 255; defenceCrush = 255; defenceMagic = 255; defenceRanged = 10 }
            anims { attack = 2854; block = 2852; death = 2856 }
        }
        setCombatDef("npc.dagannoth_supreme") {
            immunities { venom = true }
            configs { attackSpeed = 4; respawnDelay = 100 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 255; attack = 255; strength = 255; defence = 128; magic = 255; ranged = 255 }
            bonuses { defenceStab = 10; defenceSlash = 10; defenceCrush = 10; defenceMagic = 255; defenceRanged = 550 }
            anims { attack = 2855; block = 2852; death = 2856 }
        }

        // Spinolyp — Spinolyp.json (76): hp 100, 4 ticks, respawn 50, attack 2868 / block 2869 /
        // death 2865. Ranged + magic pest ringing the kings' chamber.
        setCombatDef(LairBosses.SPINOLYP_KEY) {
            configs { attackSpeed = 4; respawnDelay = 50 }
            aggro { radius = 10; searchDelay = 2 }
            stats { hitpoints = 100; attack = 10; strength = 10; defence = 10; magic = 1; ranged = 2 }
            bonuses { defenceStab = 100; defenceSlash = 100; defenceCrush = 100; defenceMagic = 50; defenceRanged = 50 }
            anims { attack = 2868; block = 2869; death = 2865 }
        }
    }
}
