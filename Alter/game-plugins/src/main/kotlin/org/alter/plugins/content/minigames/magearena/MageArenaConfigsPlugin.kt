package org.alter.plugins.content.minigames.magearena

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * Combat definitions for Kolodion's five forms.
 *
 * None of them has an ambient world spawn, so `WorldSpawnsPlugin` never builds them a def and each
 * would otherwise fight with `NpcCombatDef.DEFAULT` — 10 hp and the human animation set. The
 * ladder below is the cache's own `npc_combat.json` shape (a magic-class mage climbing to a
 * 107 hp final form) with the first form's placeholder 3 hp raised to something worth swinging at.
 *
 * None of them is aggressive: the fight starts when Kolodion sends you in, and a form that wandered
 * over and opened on a player standing at the bank would be a bug. They also never respawn — the
 * plugin spawns each one deliberately, one per stage. (The combat-def DSL has no attack-class
 * setter, so the forms fight on the generic melee path like every other minigame npc here; their
 * magic LEVEL still drives the defensive side of the formulas.)
 */
class MageArenaConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Form(val key: String, val hp: Int, val atk: Int, val str: Int, val def: Int, val magic: Int)

    /** Kolodion 1605 → 1609, in fight order. Stats follow the cache ladder; form 1's 3 hp is a
     *  cache placeholder and is raised so the opening stage isn't a single click. */
    private val forms = listOf(
        Form("npc.kolodion_1605", hp = 40, atk = 15, str = 10, def = 20, magic = 30),
        Form("npc.kolodion_1606", hp = 65, atk = 55, str = 70, def = 72, magic = 50),
        Form("npc.kolodion_1607", hp = 78, atk = 69, str = 78, def = 47, magic = 70),
        Form("npc.kolodion_1608", hp = 78, atk = 28, str = 23, def = 58, magic = 30),
        Form("npc.kolodion_1609", hp = 107, atk = 85, str = 98, def = 105, magic = 80),
    )

    init {
        forms.forEach { f ->
            if (runCatching { getRSCM(f.key) }.isFailure) return@forEach
            setCombatDef(f.key) {
                immunities { poison = true; venom = true }
                // respawnDelay 0: the plugin owns every spawn, one per stage.
                configs { attackSpeed = 5; respawnDelay = 0 }
                // Never aggressive — Kolodion's trial begins when he says it does.
                aggro { radius = 0; searchDelay = 1 }
                stats {
                    hitpoints = f.hp; attack = f.atk; strength = f.str
                    defence = f.def; magic = f.magic; ranged = 1
                }
                bonuses {
                    defenceStab = 20; defenceSlash = 20; defenceCrush = 20
                    defenceMagic = 30; defenceRanged = 20
                }
            }
        }
    }
}
