package org.alter.plugins.content.bosses.godwars

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat definitions for the **God Wars Dungeon generals and their bodyguards** —
 * port #5 from the Kronos rev-184 source (`activities/godwars/`), the first
 * donor-PACKAGE port (all four throne rooms ship together because the donor builds
 * them on one shared `General` base). Stats/speeds/anims are the donor JSONs verbatim.
 *
 * Notables straight from the donor:
 *  - Zilyana attacks every 2 TICKS (her infamous speed);
 *  - Graardor's magic defence is 298 (mage him at your peril);
 *  - Kree'arra is melee-IMMUNE — the donor nullifies melee hits in a preDefend hook
 *    our engine doesn't have, so his melee defences are raised sky-high instead
 *    (guide-documented approximation: melee always splashes, which reads the same).
 */
class GodwarsConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // ── Bandos ──────────────────────────────────────────────────────────
        general("npc.general_graardor", hp = 255, att = 280, str = 350, def = 250, rng = 350, mag = 80,
            defStab = 90, defSlash = 90, defCrush = 90, defMagic = 298, defRanged = 90,
            speed = 6, atkAnim = 7018, blockAnim = 7019, deathAnim = 7020)
        guard("npc.sergeant_strongstack", hp = 128, att = 124, str = 118, def = 125, atkAnim = 6154, blockAnim = 6155, deathAnim = 6156)
        guard("npc.sergeant_steelwill", hp = 127, att = 80, str = 50, def = 150, mag = 150, atkAnim = 7071, blockAnim = 6155, deathAnim = 6156)
        guard("npc.sergeant_grimspike", hp = 146, att = 80, str = 80, def = 132, rng = 150, atkAnim = 7073, blockAnim = 6155, deathAnim = 6156)

        // ── Saradomin ───────────────────────────────────────────────────────
        general("npc.commander_zilyana", hp = 255, att = 280, str = 196, def = 300, rng = 250, mag = 300,
            defStab = 100, defSlash = 100, defCrush = 100, defMagic = 100, defRanged = 100,
            speed = 2, atkAnim = 6967, blockAnim = 6969, deathAnim = 6968)
        guard("npc.starlight", hp = 160, att = 120, str = 125, def = 120, mag = 125, atkAnim = 6376, blockAnim = 6375, deathAnim = 6377)
        guard("npc.growler", hp = 146, att = 100, str = 101, def = 120, mag = 150, atkAnim = 7037, blockAnim = 7035, deathAnim = 7034)
        guard("npc.bree", hp = 162, att = 162, str = 80, def = 130, rng = 150, atkAnim = 7026, blockAnim = 7027, deathAnim = 7028)

        // ── Zamorak ─────────────────────────────────────────────────────────
        general("npc.kril_tsutsaroth", hp = 255, att = 340, str = 300, def = 270, rng = 1, mag = 200,
            defStab = 80, defSlash = 80, defCrush = 80, defMagic = 130, defRanged = 80,
            speed = 6, atkAnim = 6948, blockAnim = 6947, deathAnim = 6949)
        guard("npc.tstanon_karlak", hp = 142, att = 124, str = 118, def = 125, defMagic = -5, atkAnim = 64, blockAnim = 65, deathAnim = 68)
        guard("npc.balfrug_kreeyath", hp = 161, att = 115, str = 60, def = 153, mag = 150, defMagic = 10, atkAnim = 4630, blockAnim = 65, deathAnim = 67)
        guard("npc.zakln_gritch", hp = 150, att = 83, str = 76, def = 127, rng = 150, defMagic = -5, atkAnim = 4630, blockAnim = 65, deathAnim = 68)

        // ── Armadyl ─────────────────────────────────────────────────────────
        // Melee defences 9999 = the flying immunity (see class doc).
        general("npc.kreearra_3162", hp = 255, att = 300, str = 200, def = 260, rng = 380, mag = 200,
            defStab = 9999, defSlash = 9999, defCrush = 9999, defMagic = 120, defRanged = 120,
            speed = 3, atkAnim = 6981, blockAnim = 6980, deathAnim = 6979)
        guard("npc.flight_kilisa", hp = 159, att = 124, str = 118, def = 175, rng = 169, atkAnim = 6957, blockAnim = 6958, deathAnim = 6959)
        guard("npc.wingman_skree", hp = 121, att = 80, str = 50, def = 160, rng = 100, mag = 150, atkAnim = 6955, blockAnim = 6958, deathAnim = 6959)
        guard("npc.flockleader_geerin", hp = 132, att = 80, str = 80, def = 175, rng = 150, atkAnim = 6956, blockAnim = 6958, deathAnim = 6959)
    }

    private fun general(
        key: String, hp: Int, att: Int, str: Int, def: Int, rng: Int, mag: Int,
        defStab: Int, defSlash: Int, defCrush: Int, defMagic: Int, defRanged: Int,
        speed: Int, atkAnim: Int, blockAnim: Int, deathAnim: Int,
    ) {
        setCombatDef(key) {
            configs {
                attackSpeed = speed
                respawnDelay = RESPAWN_TICKS
            }
            aggro {
                radius = 15
                searchDelay = 1
            }
            stats {
                hitpoints = hp
                attack = att
                strength = str
                defence = def
                ranged = rng
                magic = mag
            }
            bonuses {
                defenceStab = defStab
                defenceSlash = defSlash
                defenceCrush = defCrush
                defenceMagic = defMagic
                defenceRanged = defRanged
            }
            anims {
                attack = atkAnim
                block = blockAnim
                death = deathAnim
            }
        }
    }

    private fun guard(
        key: String, hp: Int, att: Int, str: Int, def: Int,
        rng: Int = 1, mag: Int = 1, defMagic: Int = 0,
        atkAnim: Int, blockAnim: Int, deathAnim: Int,
    ) {
        setCombatDef(key) {
            configs {
                attackSpeed = 5 // every bodyguard is 5-tick in the donor JSONs
                respawnDelay = RESPAWN_TICKS
            }
            aggro {
                radius = 15
                searchDelay = 1
            }
            stats {
                hitpoints = hp
                attack = att
                strength = str
                defence = def
                ranged = rng
                magic = mag
            }
            bonuses {
                defenceMagic = defMagic
            }
            anims {
                attack = atkAnim
                block = blockAnim
                death = deathAnim
            }
        }
    }

    companion object {
        /** Donor `respawn_ticks: 50` (~30s) for every room npc; minions respawn on the
         *  same clock independently rather than being tied to the general's respawn
         *  (guide-documented deviation — closer to OSRS than the donor's coupling). */
        const val RESPAWN_TICKS = 50
    }
}
