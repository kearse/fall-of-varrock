package org.alter.plugins.content.npcs.godwars

import org.alter.game.model.Tile
import org.alter.game.model.combat.CombatClass
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **God Wars Dungeon** roster (Phase F): the four generals, each with their three
 * **bodyguards**, in their real throne rooms. Shared plugins read this registry:
 * [GodWarsPlugin] spawns the general + bodyguards, registers their combat defs, and handles
 * the general's death/loot; [GodWarsCombatPlugin] runs each general's dual-style rotation.
 *
 * **Adding a GWD boss = adding a [GwdBoss] here.** Lairs are real coords (TUNE); all multi-way
 * (force-loaded by [org.alter.plugins.content.bosses.BossLairs]). The OSRS **killcount door**
 * that gates each room is deferred — for now the teleport drops you straight in.
 */
object GodWarsBosses {

    data class GwdBoss(
        val key: String,
        val name: String,
        val lair: Tile,
        val hp: Int,
        val att: Int,
        val str: Int,
        val def: Int,
        val mag: Int,
        val rng: Int,
        val attackSpeed: Int,
        val maxHit: Int,
        val approach: Int,
        val style1: CombatClass,
        val style2: CombatClass,
        val bossPoints: Int,
        /** The three bodyguard npc keys, spawned around the general. */
        val bodyguards: List<String>,
        val loot: DropTable,
    )

    private fun coins(min: Int, max: Int, w: Int) = DropEntry("item.coins_995", min, max, weight = w)

    val all: List<GwdBoss> = listOf(
        GwdBoss(
            key = "npc.general_graardor", name = "General Graardor",
            lair = Tile(2864, 5354, 2),
            hp = 255, att = 280, str = 350, def = 250, mag = 80, rng = 150,
            attackSpeed = 5, maxHit = 60, approach = 2, style1 = CombatClass.MELEE, style2 = CombatClass.RANGED, bossPoints = 35,
            bodyguards = listOf("npc.sergeant_strongstack", "npc.sergeant_steelwill", "npc.sergeant_grimspike"),
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.coins_995", 80_000, 200_000, weight = 12),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                ),
                rare = listOf(
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.bandos_boots", 1, 1, oneInN = 120, log = true),
                    DropEntry("item.bandos_chestplate", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.bandos_tassets", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.bandos_hilt", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.pet_general_graardor", 1, 1, oneInN = 5000, announce = true, log = true),
                ),
            ),
        ),
        GwdBoss(
            key = "npc.kril_tsutsaroth", name = "K'ril Tsutsaroth",
            lair = Tile(2925, 5333, 2),
            hp = 255, att = 280, str = 350, def = 250, mag = 300, rng = 1,
            attackSpeed = 5, maxHit = 49, approach = 2, style1 = CombatClass.MELEE, style2 = CombatClass.MAGIC, bossPoints = 35,
            bodyguards = listOf("npc.tstanon_karlak", "npc.balfrug_kreeyath", "npc.zakln_gritch"),
            loot = DropTable(
                always = listOf(DropEntry("item.ensouled_demon_head", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.death_rune", 150, 400, weight = 16),
                    DropEntry("item.rune_platelegs", 1, 1, weight = 10),
                ),
                rare = listOf(
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.steam_battlestaff", 1, 1, oneInN = 128, log = true),
                    DropEntry("item.zamorakian_spear", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.staff_of_the_dead", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.zamorak_hilt", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.pet_kril_tsutsaroth", 1, 1, oneInN = 5000, announce = true, log = true),
                ),
            ),
        ),
        GwdBoss(
            key = "npc.kreearra_3162", name = "Kree'arra",
            lair = Tile(2839, 5295, 2),
            hp = 255, att = 1, str = 1, def = 250, mag = 200, rng = 300,
            attackSpeed = 5, maxHit = 31, approach = 8, style1 = CombatClass.RANGED, style2 = CombatClass.MAGIC, bossPoints = 35,
            bodyguards = listOf("npc.wingman_skree", "npc.flockleader_geerin", "npc.flight_kilisa"),
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.rune_arrow", 200, 500, weight = 16),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                ),
                rare = listOf(
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.armadyl_helmet", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.armadyl_chestplate", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.armadyl_chainskirt", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.armadyl_crossbow", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.armadyl_hilt", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.pet_kreearra", 1, 1, oneInN = 5000, announce = true, log = true),
                ),
            ),
        ),
        GwdBoss(
            key = "npc.commander_zilyana", name = "Commander Zilyana",
            lair = Tile(2907, 5266, 2),
            hp = 255, att = 280, str = 300, def = 300, mag = 300, rng = 1,
            attackSpeed = 4, maxHit = 31, approach = 2, style1 = CombatClass.MELEE, style2 = CombatClass.MAGIC, bossPoints = 35,
            bodyguards = listOf("npc.starlight", "npc.growler", "npc.bree"),
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.blood_rune", 100, 300, weight = 16),
                    DropEntry("item.rune_kiteshield", 1, 1, weight = 10),
                ),
                rare = listOf(
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 90, log = true),
                    DropEntry("item.saradomin_sword", 1, 1, oneInN = 254, announce = true, log = true),
                    DropEntry("item.armadyl_crossbow", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.saradomins_light", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.saradomin_hilt", 1, 1, oneInN = 508, announce = true, log = true),
                    DropEntry("item.pet_zilyana", 1, 1, oneInN = 5000, announce = true, log = true),
                ),
            ),
        ),
    )

    fun byKey(key: String): GwdBoss? = all.firstOrNull { it.key == key }
}
