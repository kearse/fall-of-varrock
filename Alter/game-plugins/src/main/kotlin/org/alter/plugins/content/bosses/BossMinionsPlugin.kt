package org.alter.plugins.content.bosses

import org.alter.api.dsl.setCombatDef
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Combat defs for the **boss minions/adds** (Scorpia's guardians, Cerberus's Summoned Souls,
 * Vet'ion's hellhounds, the Sire's scions, Nex's nihils, Zulrah's snakelings, Vorkath's
 * zombified spawn, …). They're spawned at runtime via [bossSummon], so without a registered
 * def they'd fall back to [org.alter.game.model.combat.NpcCombatDef.DEFAULT] (10 HP, 0 stats)
 * and be harmless — defeating the mechanic. This gives each a sensible stat block.
 *
 * Each registration is **guarded**: `setCombatDef` throws if the id already has a def (e.g.
 * the npc is used elsewhere too), so a clash is skipped rather than crashing the plugin.
 */
class BossMinionsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Minion(val key: String, val hp: Int, val att: Int, val str: Int, val def: Int)

    private val minions = listOf(
        Minion("npc.snakeling", 30, 90, 90, 40),
        Minion("npc.scorpias_guardian", 70, 120, 120, 80),
        Minion("npc.summoned_soul", 60, 140, 140, 80),
        Minion("npc.skeleton_hellhound", 90, 150, 150, 90),
        Minion("npc.scion", 50, 120, 120, 70),
        Minion("npc.respiratory_system", 40, 100, 100, 60),
        Minion("npc.zombified_spawn", 20, 80, 80, 30),
        // Nex's nihils — genuinely threatening.
        Minion("npc.fumus", 150, 180, 180, 120),
        Minion("npc.umbra", 150, 180, 180, 120),
        Minion("npc.cruor", 150, 180, 180, 120),
        Minion("npc.glacies", 150, 180, 180, 120),
    )

    init {
        for (m in minions) {
            runCatching {
                setCombatDef(m.key) {
                    configs {
                        attackSpeed = 4
                        respawnDelay = 0
                    }
                    aggro {
                        radius = 8
                        searchDelay = 1
                    }
                    stats {
                        hitpoints = m.hp
                        attack = m.att
                        strength = m.str
                        defence = m.def
                    }
                    bonuses {
                        attackBonus = 70
                        strengthBonus = 60
                        defenceStab = 50
                        defenceSlash = 50
                        defenceCrush = 50
                    }
                    anims {
                        death = deathAnimFor(m.key)
                    }
                }
            }
        }
    }
}
