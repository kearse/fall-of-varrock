package org.alter.plugins.content.minigames.barrows

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.deathAnimFor

/**
 * Combat definitions for the six Barrows brothers — Kronos `data/npcs/combat/<Name>.json`
 * verbatim (all 100 hp; per-brother attack ticks, levels and bonuses; death anim 4167) — and
 * for the crypt vermin in the tunnels (OSRS levels, modest stats; no donor JSON, so death
 * anims fall back to each npc's own stand animation via [deathAnimFor]).
 */
class BarrowsConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Ahrim — Ahrim_the_Blighted.json: atk 1 / str 1 / def 100 / rng 1 / mag 100;
        // stab 12, crush 65, magic 73 attack; 103/85/117/73/0 defence.
        brother(Barrows.Brother.AHRIM, atk = 1, str = 1, def = 100, mag = 100, rng = 1) {
            attackStab = 12; attackCrush = 65; attackMagic = 73
            defenceStab = 103; defenceSlash = 85; defenceCrush = 117; defenceMagic = 73; defenceRanged = 0
        }
        // Dharok — Dharok_the_Wretched.json.
        brother(Barrows.Brother.DHAROK, atk = 100, str = 100, def = 100, mag = 1, rng = 1) {
            attackSlash = 103; attackCrush = 95
            defenceStab = 252; defenceSlash = 250; defenceCrush = 244; defenceMagic = -11; defenceRanged = 249
        }
        // Guthan — Guthan_the_Infested.json.
        brother(Barrows.Brother.GUTHAN, atk = 100, str = 100, def = 100, mag = 1, rng = 1) {
            attackStab = 75; attackSlash = 75; attackCrush = 75
            defenceStab = 259; defenceSlash = 257; defenceCrush = 241; defenceMagic = -11; defenceRanged = 250
        }
        // Karil — Karil_the_Tainted.json.
        brother(Barrows.Brother.KARIL, atk = 1, str = 1, def = 100, mag = 1, rng = 100) {
            attackRanged = 134
            defenceStab = 79; defenceSlash = 71; defenceCrush = 90; defenceMagic = 106; defenceRanged = 100
        }
        // Torag — Torag_the_Corrupted.json.
        brother(Barrows.Brother.TORAG, atk = 100, str = 100, def = 100, mag = 1, rng = 1) {
            attackStab = 68; attackCrush = 82
            defenceStab = 221; defenceSlash = 235; defenceCrush = 222; defenceMagic = 0; defenceRanged = 221
        }
        // Verac — Verac_the_Defiled.json.
        brother(Barrows.Brother.VERAC, atk = 100, str = 100, def = 100, mag = 1, rng = 1) {
            attackStab = 68; attackCrush = 82
            defenceStab = 227; defenceSlash = 230; defenceCrush = 221; defenceMagic = 0; defenceRanged = 225
        }

        // Tunnel vermin — one def per distinct key (two crypt rats share one).
        Barrows.TUNNEL_MONSTERS.distinctBy { it.npcKey }.forEach { m ->
            setCombatDef(m.npcKey) {
                species { +NpcSpecies.UNDEAD }
                configs {
                    attackSpeed = m.attackSpeed
                    respawnDelay = 50
                }
                aggro {
                    radius = 4
                    searchDelay = 2
                }
                stats {
                    hitpoints = m.hp
                    attack = m.level / 2
                    strength = m.level / 2
                    defence = m.level / 3
                }
                anims {
                    death = deathAnimFor(m.npcKey)
                }
            }
        }
    }

    private fun brother(
        b: Barrows.Brother,
        atk: Int,
        str: Int,
        def: Int,
        mag: Int,
        rng: Int,
        bonusInit: NpcCombatDsl.BonusBuilder.() -> Unit,
    ) {
        setCombatDef(b.npcKey) {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = false; venom = true } // donor: venom-immune, poisonable
            configs {
                attackSpeed = b.attackSpeed
                respawnDelay = 0 // brothers never engine-respawn — every one is spawned for an owner
            }
            aggro {
                radius = 8
                searchDelay = 1
            }
            stats {
                hitpoints = 100
                attack = atk
                strength = str
                defence = def
                magic = mag
                ranged = rng
            }
            bonuses(bonusInit)
            anims {
                attack = b.attackAnim
                if (b.blockAnim > 0) block = b.blockAnim
                death = 4167
            }
        }
    }
}
