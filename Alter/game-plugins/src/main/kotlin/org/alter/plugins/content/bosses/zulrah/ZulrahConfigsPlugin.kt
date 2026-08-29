package org.alter.plugins.content.bosses.zulrah

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for **Zulrah** — port #2 from the Kronos rev-184 source
 * (`bosses/zulrah/Zulrah.java` + `Zulrah.json`/`Snakeling.json`), per
 * docs/kronos-port-guide.md. Stats are the donor's verbatim; each form is its own npc
 * id, which is exactly what gives the fight its form weaknesses and per-form attack
 * speeds through the ordinary combat-def pipeline:
 *
 *  - 2042 serpentine (green, ranged): 3-tick; melee-proof (1000s), WEAK to magic (-45)
 *  - 2043 magma (red, melee): 5-tick; ranged-proof (300), open to melee/magic (0s)
 *  - 2044 tanzanite (blue, magic): 3-tick; magic-proof (300), open to ranged (0)
 *  - 2045 snakeling: 1 hp, 4-tick, hits up to 15
 */
class ZulrahConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        setCombatDef("npc.zulrah") {
            configs {
                attackSpeed = 3
                respawnDelay = 0 // never engine-respawned: forms spawn with respawns=false
            }
            aggro {
                radius = 20
                searchDelay = 1
            }
            stats {
                hitpoints = 500
                attack = 1
                strength = 1
                defence = 300
                magic = 300
                ranged = 300
            }
            bonuses {
                attackMagic = 50
                attackRanged = 50
                defenceStab = 1000
                defenceSlash = 1000
                defenceCrush = 1000
                defenceMagic = -45
                defenceRanged = 50
            }
            anims {
                death = 5804
            }
        }

        setCombatDef("npc.zulrah_2043") {
            configs {
                attackSpeed = 5
                respawnDelay = 0
            }
            aggro {
                radius = 20
                searchDelay = 1
            }
            stats {
                hitpoints = 500
                attack = 1
                strength = 1
                defence = 300
                magic = 300
                ranged = 300
            }
            bonuses {
                attackMagic = 50
                attackRanged = 50
                defenceStab = 0
                defenceSlash = 0
                defenceCrush = 0
                defenceMagic = 0
                defenceRanged = 300
            }
            anims {
                death = 5804
            }
        }

        setCombatDef("npc.zulrah_2044") {
            configs {
                attackSpeed = 3
                respawnDelay = 0
            }
            aggro {
                radius = 20
                searchDelay = 1
            }
            stats {
                hitpoints = 500
                attack = 1
                strength = 1
                defence = 300
                magic = 300
                ranged = 300
            }
            bonuses {
                defenceStab = 0
                defenceSlash = 0
                defenceCrush = 0
                defenceMagic = 300
                defenceRanged = 0
            }
            anims {
                death = 5804
            }
        }

        setCombatDef("npc.snakeling") {
            configs {
                attackSpeed = 4
                respawnDelay = 0
            }
            stats {
                hitpoints = 1
                attack = 140
                strength = 138
                defence = 1
                magic = 1
                ranged = 1
            }
            bonuses {
                attackStab = 120
                attackSlash = 120
                attackCrush = 120
                attackMagic = 120
                attackRanged = 120
                defenceStab = -40
                defenceSlash = -40
                defenceCrush = -40
                defenceMagic = -40
                defenceRanged = -40
            }
            anims {
                block = 1742
                death = 2408
            }
        }
    }
}
