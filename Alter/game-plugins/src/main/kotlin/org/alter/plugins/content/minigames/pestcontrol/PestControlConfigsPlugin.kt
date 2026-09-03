package org.alter.plugins.content.minigames.pestcontrol

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * Combat definitions for the pests (OSRS-tiered by id: each kind climbs in level across its
 * five ids), the portals (shielded = untouchable, open = attackable) and the Void Knights.
 * Anims are each skeleton's own (npcDef anims).
 */
class PestControlConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Kind(val prefix: String, val baseHp: Int, val hpStep: Int, val baseMax: Int, val maxStep: Int, val speed: Int, val attack: Int, val block: Int, val death: Int, val aggro: Int)

    private val kinds = listOf(
        Kind("npc.splatter", 20, 18, 3, 2, 4, 3886, 3886, 3888, 6),
        Kind("npc.shifter", 50, 13, 5, 2, 4, 3902, 3901, 3904, 8),
        Kind("npc.ravager", 60, 15, 6, 2, 4, 3915, 3916, 3917, 8),
        Kind("npc.spinner", 50, 13, 4, 1, 4, 3908, 3909, 3910, 6),
        Kind("npc.torcher", 40, 15, 6, 2, 4, 3880, 3882, 3881, 8),
        Kind("npc.defiler", 50, 13, 6, 2, 4, 3921, 3920, 3922, 8),
        Kind("npc.brawler", 100, 25, 8, 2, 5, 3897, 3895, 3894, 6),
    )

    init {
        PestControl.ALL_PESTS.forEach { key ->
            val kind = kinds.first { key.startsWith(it.prefix) }
            val id = getRSCM(key)
            val tier = tierOf(kind.prefix, id)
            setCombatDef(key) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = kind.speed; respawnDelay = 0 }
                aggro { radius = kind.aggro; searchDelay = 1 }
                stats {
                    hitpoints = kind.baseHp + kind.hpStep * tier
                    attack = 40 + 20 * tier; strength = 40 + 20 * tier; defence = 30 + 15 * tier
                    magic = if (kind.prefix == "npc.torcher") 40 + 20 * tier else 1
                    ranged = if (kind.prefix == "npc.defiler") 40 + 20 * tier else 1
                }
                bonuses { defenceStab = 10 * tier; defenceSlash = 10 * tier; defenceCrush = 10 * tier; defenceMagic = 10 * tier; defenceRanged = 10 * tier }
                anims { attack = kind.attack; block = kind.block; death = kind.death }
            }
        }

        PestControl.PORTALS.forEach { def ->
            setCombatDef(def.shielded) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 0; searchDelay = 1 }
                stats { hitpoints = 250; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 1 }
                anims { attack = 6881; block = 6882; death = 6884 }
            }
            setCombatDef(def.open) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 0; searchDelay = 1 }
                stats { hitpoints = 250; attack = 1; strength = 1; defence = 60; magic = 1; ranged = 1 }
                bonuses { defenceStab = 20; defenceSlash = 20; defenceCrush = 20; defenceMagic = 20; defenceRanged = 20 }
                anims { attack = 3934; block = 3937; death = 3935 }
            }
        }

        PestControl.Lander.values().forEach { lander ->
            setCombatDef(lander.knightKey) {
                immunities { poison = true; venom = true }
                configs { attackSpeed = 4; respawnDelay = 0 }
                aggro { radius = 0; searchDelay = 1 }
                stats { hitpoints = 250; attack = 1; strength = 1; defence = 1; magic = 1; ranged = 1 }
                anims { attack = 3926; block = 3926; death = 3926 }
            }
        }
    }

    /** 0..4 — the pest's rank within its five-id family (ids are contiguous per kind). */
    private fun tierOf(prefix: String, id: Int): Int {
        val base = when (prefix) {
            "npc.splatter" -> 1689; "npc.shifter" -> 1694; "npc.ravager" -> 1704; "npc.spinner" -> 1709
            "npc.torcher" -> 1714; "npc.defiler" -> 1724; "npc.brawler" -> 1734; else -> id
        }
        val span = if (prefix == "npc.shifter" || prefix == "npc.torcher" || prefix == "npc.defiler") 10 else 5
        return ((id - base).coerceIn(0, span - 1) * 5 / span)
    }
}
