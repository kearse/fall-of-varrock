package org.alter.plugins.content.bosses.slayer

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the Slayer bosses and their adds — Kronos `data/npcs/combat/<Name>.json`
 * verbatim. Slayer gates ride `slayerData` (the engine refuses the attack below the level).
 * Every managed form (whirlpools, tentacles, altars, gorilla prayer forms, Skotizo) has
 * `respawnDelay = 0` — the plugin owns their life cycles.
 */
class SlayerBossesConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Kraken (291): hp 255, magic def 130 / ranged def 300, 4 ticks; anims 3992/3990/3993; poison+venom immune; Slayer 87.
        listOf(SlayerBosses.KRAKEN_WHIRLPOOL, SlayerBosses.KRAKEN).forEach { key ->
            setCombatDef(key) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 10; searchDelay = 1 }
                stats { hitpoints = 255; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 1 }
                bonuses { defenceMagic = 130; defenceRanged = 300 }
                anims { attack = 3992; block = 3990; death = 3993 }
                slayerData { levelRequirement = 87; xp = 255.0 }
            }
        }
        // Enormous tentacle (112): hp 120, ranged 150, magic def -15 / ranged def 270; anims 3618/3617/3620.
        listOf(SlayerBosses.TENTACLE_WHIRLPOOL, SlayerBosses.TENTACLE).forEach { key ->
            setCombatDef(key) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 10; searchDelay = 1 }
                stats { hitpoints = 120; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 150 }
                bonuses { defenceMagic = -15; defenceRanged = 270 }
                anims { attack = 3618; block = 3617; death = 3620 }
            }
        }

        // Cerberus (318): hp 600, 220/220/100/mag 220/rng 220, 4 ticks, respawn 50; anims 4491/4489/4495; Slayer 91.
        setCombatDef(SlayerBosses.CERBERUS) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 50 }
            aggro { radius = 10; searchDelay = 1 }
            stats { hitpoints = 600; attack = 220; strength = 220; defence = 100; magic = 220; ranged = 220 }
            bonuses {
                attackStab = 50; attackSlash = 50; attackCrush = 50; attackMagic = 50; attackRanged = 50
                defenceStab = 50; defenceSlash = 100; defenceCrush = 25; defenceMagic = 200; defenceRanged = 200
            }
            anims { attack = 4491; block = 4489; death = 4495 }
            slayerData { levelRequirement = 91; xp = 690.0 }
        }
        // Summoned souls: scripted, never attacked (no options), respawnDelay 0.
        listOf(SlayerBosses.SOUL_RANGED, SlayerBosses.SOUL_MAGIC, SlayerBosses.SOUL_MELEE).forEach { key ->
            setCombatDef(key) {
                configs { attackSpeed = 4; respawnDelay = 0 }
                stats { hitpoints = 1 }
                anims { death = 836 }
            }
        }

        // Thermonuclear smoke devil (301): hp 240, 230/220/360/rng 310, 2 ticks, respawn 50; anims 3847/3848/3849; Slayer 93.
        setCombatDef(SlayerBosses.THERMY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 2; respawnDelay = 50 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 240; attack = 230; strength = 220; defence = 360; magic = 1; ranged = 310 }
            bonuses { defenceStab = 11; defenceSlash = 4; defenceCrush = 9; defenceMagic = 800; defenceRanged = 900 }
            anims { attack = 3847; block = 3848; death = 3849 }
            slayerData { levelRequirement = 93; xp = 240.0 }
        }

        // Skotizo (321, catacombs form): hp 450, 240/250/200/mag 280, 6 ticks; anims 4680/4676/4624; poison+venom immune.
        setCombatDef(SlayerBosses.SKOTIZO) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 6; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 450; attack = 240; strength = 250; defence = 200; magic = 280; ranged = 1 }
            bonuses {
                attackStab = 160; attackSlash = 160; attackCrush = 160
                defenceStab = 80; defenceSlash = 80; defenceCrush = 80; defenceMagic = 130; defenceRanged = 130
            }
            anims { attack = 4680; block = 4676; death = 4624 }
        }
        // Altars: dormant (1 hp, no options) and awakened (100 hp, attackable) — Skotizo_altar.json.
        SlayerBosses.ALTARS.forEach { a ->
            setCombatDef(a.dormantKey) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 10; respawnDelay = 0 }
                stats { hitpoints = 1 }
                anims { death = 1473 }
            }
            // The awakened altars carry an `Attack` action at combat level 0 in the cache, which
            // `NpcType.isAttackable()` refuses — opt them in server-side or the "kill the altars to
            // weaken him" mechanic is dead and SKOTIZO_AWAKE only ever climbs.
            setCombatDef(a.awakenedKey) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 10; respawnDelay = 0; forceAttackable = true }
                stats { hitpoints = 100; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 1 }
                anims { death = 1473 }
            }
        }

        // Demonic gorilla (275): hp 380, 205/195/200/mag 195/rng 195, 5 ticks; anims 7226/7224/7229; venom immune.
        // Three prayer forms: each is near-immune to the style it "prays" against (the Kree'arra
        // sky-high-defence pattern) and open to the other two.
        gorilla(SlayerBosses.GORILLA_MELEE, meleeDef = 9999, rangedDef = 50, magicDef = 50)
        gorilla(SlayerBosses.GORILLA_RANGED, meleeDef = 50, rangedDef = 9999, magicDef = 50)
        gorilla(SlayerBosses.GORILLA_MAGIC, meleeDef = 50, rangedDef = 50, magicDef = 9999)
    }

    private fun gorilla(key: String, meleeDef: Int, rangedDef: Int, magicDef: Int) {
        setCombatDef(key) {
            species { +NpcSpecies.DEMON }
            immunities { venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = 380; attack = 205; strength = 195; defence = 200; magic = 195; ranged = 195 }
            bonuses {
                attackStab = 43; attackSlash = 43; attackCrush = 43; attackMagic = 40; attackRanged = 43
                defenceStab = meleeDef; defenceSlash = meleeDef; defenceCrush = meleeDef; defenceMagic = magicDef; defenceRanged = rangedDef
            }
            anims { attack = 7226; block = 7224; death = 7229 }
        }
    }
}
