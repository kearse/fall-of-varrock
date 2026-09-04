package org.alter.plugins.content.bosses.hydra

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the **Alchemical Hydra** — port #3 from the Kronos rev-184
 * source (`bosses/hydra/AlchemicalHydra.java` + `Alchemical_hydra.json`), per
 * docs/kronos-port-guide.md.
 *
 * One stat block serves every fighting form (the donor registers all eight ids on one
 * entry): 1100 hp shared across the phases (HP carries over each form swap), 6-tick
 * attacks, max 35 before the vent-power scaling. Only the four FIGHTING forms get defs —
 * the headless transition npcs (8616/8617/8618) and the corpse (8622) are cosmetic
 * swap-ins that never fight.
 *
 * Forms: 8615 green (poison) → 8619 blue (lightning) → 8620 red (fire) → 8621 grey (enraged).
 */
class HydraConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (key in listOf(
            "npc.alchemical_hydra",       // 8615 green
            "npc.alchemical_hydra_8619",  // blue
            "npc.alchemical_hydra_8620",  // red
            "npc.alchemical_hydra_8621",  // grey
        )) {
            setCombatDef(key) {
                configs {
                    attackSpeed = 6
                    respawnDelay = 0 // never engine-respawned: forms spawn with respawns=false
                }
                aggro {
                    radius = 16
                    searchDelay = 1
                }
                stats {
                    hitpoints = 1100
                    attack = 100
                    strength = 100
                    defence = 100
                    magic = 260
                    ranged = 260
                }
                bonuses {
                    defenceStab = 75
                    defenceSlash = 150
                    defenceCrush = 150
                    defenceMagic = 150
                    defenceRanged = 45
                }
                anims {
                    death = 8257 // the collapse the donor plays at death-start (corpse transform skipped)
                }
                // OSRS: 95 Slayer to wound it (Combat.canEngage enforces slayerReq; SlayerPlugin
                // only pays slayer xp when the def carries one). Missing until 2026-09-03.
                slayerData { levelRequirement = 95; xp = 1100.0 }
            }
        }
    }
}
