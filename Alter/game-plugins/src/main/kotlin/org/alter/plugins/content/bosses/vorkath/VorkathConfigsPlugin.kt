package org.alter.plugins.content.bosses.vorkath

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the **Vorkath** encounter — the reboot's PILOT PORT from the
 * owner-released Kronos rev-184 source (`bosses/vorkath/Vorkath.java` + the per-npc
 * combat JSON), translated onto Alter's DSL. Stats are Kronos's `Vorkath.json` /
 * `Zombified_spawn.json` verbatim; anim ids are cache facts and carry over 1:1.
 *
 * Npc forms (Kronos id table):
 *  - 8059 — sleeping, "Poke" option (the form we spawn; scenery-passive)
 *  - 8061 — the post-quest fighting form (level 732)
 *  - 8063 — Zombified spawn (the ice-phase add)
 */
class VorkathConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The sleeping form never fights, but a registered def keeps any stray attack
        // from hitting an undefined-combat npc. No aggro, no respawn.
        setCombatDef("npc.vorkath_8059") {
            species { +NpcSpecies.DRACONIC; +NpcSpecies.UNDEAD; +NpcSpecies.FIERY }
            immunities { poison = true; venom = true } // Vorkath: 100% poison/venom resist (wiki)
            configs {
                attackSpeed = 5
                respawnDelay = 0 // never engine-respawned: every form spawns with respawns=false
            }
            stats {
                hitpoints = 750
                defence = 214
            }
            anims {
                death = 7949
            }
        }

        // The awake fight form — Kronos Vorkath.json: hp 750, atk 560 / str 308 /
        // def 214 / ranged 308 / magic 150; 5-tick attacks; death anim 7949.
        setCombatDef("npc.vorkath_8061") {
            // Vorkath is Draconic, Undead AND Fiery (wiki) — the missing UNDEAD tag meant
            // salve amulet(ei), the standard Vorkath amulet, gave no bonus.
            species { +NpcSpecies.DRACONIC; +NpcSpecies.UNDEAD; +NpcSpecies.FIERY }
            immunities { poison = true; venom = true } // 100% poison/venom resist (wiki + donor)
            configs {
                attackSpeed = 5
                respawnDelay = 0 // never engine-respawned: every form spawns with respawns=false
            }
            aggro {
                radius = 16
                searchDelay = 1
            }
            stats {
                hitpoints = 750
                attack = 560
                strength = 308
                defence = 214
                magic = 150
                ranged = 308
            }
            bonuses {
                // Kronos aggressive/defensive stats: magic_attack 150, ranged_attack 78;
                // stab 26 / slash 108 / crush 108 / magic 240 / ranged 26 defence.
                attackMagic = 150
                attackRanged = 78
                defenceStab = 26
                defenceSlash = 108
                defenceCrush = 108
                defenceMagic = 240
                defenceRanged = 26
            }
            anims {
                block = 7954
                death = 7949
            }
        }

        // Zombified spawn — Kronos Zombified_spawn.json: hp 38, near-zero stats,
        // magic defence -100 (dies to any spell), death anim 7891. Its "attack" is the
        // scripted self-destruct in [VorkathCombatPlugin].
        setCombatDef("npc.zombified_spawn_8063") {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = true; venom = true } // donor Zombified_spawn.json
            configs {
                attackSpeed = 5
                respawnDelay = 0 // never engine-respawned: every form spawns with respawns=false
            }
            stats {
                hitpoints = 38
                attack = 1
                strength = 1
                defence = 6
                magic = 1
            }
            bonuses {
                defenceStab = 3
                defenceSlash = 3
                defenceCrush = 3
                defenceMagic = -100
                defenceRanged = 3
            }
            anims {
                death = 7891
            }
        }
    }
}
