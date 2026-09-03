package org.alter.plugins.content.bosses.wilderness

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bosses.lairs.LairBosses.Spawn

/**
 * The **Wilderness bosses** — Kronos port #8, the seven-boss package from
 * `activities/wilderness/bosses`: Callisto, Vet'ion, Venenatis, Scorpia, the Chaos Elemental,
 * the Chaos Fanatic and the Crazy Archaeologist. Shared-world, multi-way, at the donor's
 * surface lairs (its `wilderness.json` spawns) — the pre-2023 layout every RSPS player knows.
 *
 * Stats from `data/npcs/combat/<Name>.json`; loot from `data/npcs/drops/eco/<Name>.json`
 * folded flat (each donor sub-table rescaled to roughly equal weight so the fold keeps the
 * sub-table odds). Uniques keep OSRS-era odds (rings 1/512, dragon pickaxe 1/256, ward shards
 * 1/256, fedora 1/128); pets use the Vorkath-pilot 1/1000 scale (Chaos Elemental pet keeps
 * OSRS's 1/300).
 */
object WildernessBosses {

    data class WildBoss(
        val key: String,
        val name: String,
        val lootKey: String,
        val spawns: List<Spawn>,
        val landing: Tile,
        val wildLevel: Int,
        val regions: IntArray,
        val drops: DropTable,
        val tickets: Int,
        val pet: String?,
        val petOneIn: Int,
    )

    private fun e(key: String, min: Int, max: Int, w: Int) = DropEntry(key, min, max, weight = w)
    private fun one(key: String, w: Int) = DropEntry(key, 1, 1, weight = w)
    private fun rare(key: String, oneIn: Int, log: Boolean = true) = DropEntry(key, 1, 1, oneInN = oneIn, announce = true, log = log)

    // ───────────────────────────── Callisto ─────────────────────────────

    const val CALLISTO_KEY = "npc.callisto_6609"

    val CALLISTO = WildBoss(
        key = "callisto", name = "Callisto", lootKey = CALLISTO_KEY,
        spawns = listOf(Spawn(CALLISTO_KEY, Tile(3291, 3847, 0), walkRadius = 8)),
        landing = Tile(3287, 3840, 0), wildLevel = 41, regions = intArrayOf(13115, 13116),
        drops = DropTable(
            always = listOf(DropEntry("item.big_bones", 1, 1)),
            main = listOf(
                one("item.rune_pickaxe", 34), one("item.rune_2h_sword", 17), one("item.dragon_2h_sword", 9),
                e("item.soul_rune", 250, 250, 22), e("item.death_rune", 300, 300, 22), e("item.chaos_rune", 400, 400, 8), e("item.blood_rune", 200, 200, 8),
                e("item.snapdragon_seed", 3, 3, 16), e("item.ranarr_seed", 5, 5, 16), one("item.yew_seed", 16), one("item.magic_seed", 6), one("item.palm_tree_seed", 6),
                e("item.dark_crab", 8, 8, 45), e("item.super_restore4", 3, 3, 15),
                e("item.uncut_ruby_noted", 20, 20, 45), e("item.uncut_diamond_noted", 10, 10, 15),
                e("item.coins_995", 12_000, 20_000, 8), e("item.coconut_noted", 60, 60, 8), e("item.supercompost_noted", 100, 100, 8),
                e("item.crushed_nest_noted", 75, 75, 8), e("item.cannonball", 250, 250, 8), e("item.magic_logs_noted", 100, 100, 4),
                e("item.limpwurt_root_noted", 50, 50, 4), e("item.red_dragonhide_noted", 75, 75, 4), e("item.mahogany_logs_noted", 400, 400, 4),
                one("item.clue_scroll_elite", 2), one("item.uncut_dragonstone", 2), e("item.grimy_toadflax_noted", 100, 100, 1),
            ),
            rare = listOf(rare("item.dragon_pickaxe", 256), rare("item.tyrannical_ring", 512)),
        ),
        tickets = 25, pet = "item.callisto_cub", petOneIn = 1000,
    )

    // ───────────────────────────── Vet'ion ─────────────────────────────

    const val VETION_KEY = "npc.vetion"
    const val VETION_REBORN_KEY = "npc.vetion_6612"
    const val HELLHOUND_KEY = "npc.skeleton_hellhound_6613"
    const val GREATER_HELLHOUND_KEY = "npc.greater_skeleton_hellhound"
    const val VETION_RESPAWN_TICKS = 50

    val VETION = WildBoss(
        key = "vetion", name = "Vet'ion", lootKey = VETION_REBORN_KEY,
        spawns = listOf(Spawn(VETION_KEY, Tile(3224, 3790, 0), walkRadius = 10, engineRespawn = false)),
        landing = Tile(3218, 3782, 0), wildLevel = 34, regions = intArrayOf(12859),
        drops = DropTable(
            always = listOf(DropEntry("item.big_bones", 1, 1)),
            main = listOf(
                one("item.rune_pickaxe", 19), one("item.ancient_staff", 19), one("item.rune_2h_sword", 19), one("item.dragon_2h_sword", 4),
                e("item.chaos_rune", 400, 400, 20), e("item.death_rune", 300, 300, 20), e("item.blood_rune", 200, 200, 20),
                e("item.uncut_ruby_noted", 20, 20, 30), e("item.uncut_diamond_noted", 10, 10, 30),
                e("item.dark_crab", 8, 8, 30), e("item.super_restore4", 3, 3, 30),
                e("item.coins_995", 15_000, 20_000, 6), e("item.ogre_coffin_key_noted", 10, 10, 6), e("item.limpwurt_root_noted", 50, 50, 6),
                e("item.magic_logs_noted", 100, 100, 4), e("item.gold_ore_noted", 300, 300, 4), e("item.dragon_bones_noted", 100, 100, 4),
                one("item.uncut_dragonstone", 4), e("item.oak_plank_noted", 300, 300, 4), e("item.supercompost_noted", 100, 100, 4),
                e("item.cannonball", 250, 250, 2), one("item.magic_seed", 2), one("item.palm_tree_seed", 2), one("item.yew_seed", 2),
                e("item.mort_myre_fungus_noted", 200, 200, 3), one("item.clue_scroll_elite", 1),
                e("item.grimy_ranarr_weed_noted", 100, 100, 3), e("item.sanfew_serum4_noted", 10, 10, 3),
            ),
            rare = listOf(rare("item.dragon_pickaxe", 256), rare("item.ring_of_the_gods", 512)),
        ),
        tickets = 25, pet = "item.vetion_jr", petOneIn = 1000,
    )

    // ───────────────────────────── Venenatis ─────────────────────────────

    const val VENENATIS_KEY = "npc.venenatis_6610"

    val VENENATIS = WildBoss(
        key = "venenatis", name = "Venenatis", lootKey = VENENATIS_KEY,
        spawns = listOf(Spawn(VENENATIS_KEY, Tile(3339, 3741, 0), walkRadius = 9)),
        landing = Tile(3332, 3734, 0), wildLevel = 28, regions = intArrayOf(13370),
        drops = DropTable(
            main = listOf(
                one("item.rune_pickaxe", 14), e("item.diamond_bolts_e", 100, 100, 14), one("item.rune_2h_sword", 14), e("item.rune_knife", 60, 60, 14), one("item.dragon_2h_sword", 4),
                e("item.uncut_ruby_noted", 20, 20, 30), e("item.uncut_diamond_noted", 10, 10, 30),
                e("item.chaos_rune", 400, 400, 7), e("item.death_rune", 300, 300, 7), e("item.blood_rune", 200, 200, 7),
                e("item.supercompost_noted", 100, 100, 7), e("item.unicorn_horn_noted", 100, 100, 7), e("item.red_spiders_eggs_noted", 500, 500, 4),
                e("item.gold_ore_noted", 300, 300, 4), e("item.cannonball", 250, 250, 4), e("item.magic_logs_noted", 100, 100, 4),
                e("item.limpwurt_root_noted", 50, 50, 2), one("item.uncut_dragonstone", 2), one("item.yew_seed", 2), one("item.magic_seed", 2),
                one("item.palm_tree_seed", 2), e("item.grimy_snapdragon_noted", 100, 100, 2),
                e("item.dark_crab", 8, 8, 28), e("item.super_restore4", 3, 3, 28), e("item.antidote4_noted", 10, 10, 4),
                e("item.coins_995", 15_000, 20_000, 29), e("item.onyx_bolt_tips", 60, 60, 29), one("item.clue_scroll_elite", 3),
            ),
            rare = listOf(rare("item.dragon_pickaxe", 256), rare("item.treasonous_ring", 512)),
        ),
        tickets = 25, pet = "item.venenatis_spiderling", petOneIn = 1000,
    )

    // ───────────────────────────── Scorpia ─────────────────────────────

    const val SCORPIA_KEY = "npc.scorpia"
    const val GUARDIAN_KEY = "npc.scorpias_guardian"

    val SCORPIA = WildBoss(
        key = "scorpia", name = "Scorpia", lootKey = SCORPIA_KEY,
        spawns = listOf(Spawn(SCORPIA_KEY, Tile(3234, 10340, 0), walkRadius = 8)),
        landing = Tile(3232, 10335, 0), wildLevel = 54, regions = intArrayOf(12961),
        drops = DropTable(
            main = listOf(
                one("item.phoenix_necklace", 12), one("item.rune_chainbody", 12), one("item.rune_pickaxe", 12), one("item.rune_scimitar", 12),
                one("item.rune_spear", 12), one("item.rune_sword", 12), one("item.rune_warhammer", 12), one("item.rune_2h_sword", 12), one("item.dragon_scimitar", 4),
                e("item.coins_995", 10, 10, 12), e("item.coins_995", 500, 3987, 12), e("item.admiral_pie", 3, 3, 12), e("item.anchovy_pizza_noted", 8, 8, 12),
                e("item.bucket_of_sand_noted", 25, 25, 8), e("item.cactus_spine_noted", 10, 10, 8), one("item.prayer_potion4", 8), one("item.shark", 8),
                e("item.uncut_sapphire_noted", 4, 4, 4), e("item.uncut_emerald_noted", 6, 6, 4), e("item.dust_rune", 30, 30, 4),
                e("item.grimy_kwuarm_noted", 4, 4, 2), one("item.superantipoison4", 2), one("item.ensouled_scorpion_head", 2), one("item.clue_scroll_hard", 1),
            ),
            rare = listOf(rare("item.odium_shard_3", 256), rare("item.malediction_shard_3", 256)),
        ),
        tickets = 20, pet = "item.scorpias_offspring", petOneIn = 1000,
    )

    // ───────────────────────────── Chaos Elemental ─────────────────────────────

    const val CHAOS_ELEMENTAL_KEY = "npc.chaos_elemental_2054"

    val CHAOS_ELEMENTAL = WildBoss(
        key = "chaos_elemental", name = "Chaos Elemental", lootKey = CHAOS_ELEMENTAL_KEY,
        spawns = listOf(Spawn(CHAOS_ELEMENTAL_KEY, Tile(3253, 3925, 0), walkRadius = 23)),
        landing = Tile(3248, 3918, 0), wildLevel = 51, regions = intArrayOf(12861),
        drops = DropTable(
            main = listOf(
                one("item.dragon_dagger", 8), one("item.dragon_2h_sword", 3),
                e("item.mithril_dart", 300, 300, 12), e("item.rune_arrow", 150, 150, 12), e("item.chaos_rune", 250, 250, 6), e("item.air_rune", 500, 500, 6),
                e("item.death_rune", 125, 125, 2), e("item.blood_rune", 75, 75, 2),
                e("item.grimy_guam_leaf", 1, 5, 6), e("item.grimy_marrentill", 1, 5, 6), e("item.grimy_tarromin", 1, 5, 6), e("item.grimy_harralander", 1, 5, 6),
                e("item.grimy_ranarr_weed", 1, 5, 6), e("item.grimy_irit_leaf", 1, 5, 6), e("item.grimy_avantoe", 1, 5, 2), e("item.grimy_kwuarm", 1, 5, 2),
                e("item.grimy_cadantine", 1, 5, 2), e("item.grimy_lantadyme", 1, 5, 2), e("item.grimy_dwarf_weed", 1, 5, 2),
                one("item.super_strength4", 6), one("item.super_attack4", 6), one("item.super_defence4", 6), e("item.anchovy_pizza", 3, 3, 6),
                e("item.bones", 4, 4, 3), e("item.bat_bones", 5, 5, 3), e("item.big_bones", 3, 3, 3), e("item.tuna", 5, 5, 3),
                one("item.dragon_bones", 1), e("item.babydragon_bones", 2, 2, 1),
                e("item.strange_fruit_noted", 10, 10, 10), one("item.weapon_poison", 10), one("item.antidote4", 10),
                e("item.coins_995", 7500, 7500, 4), one("item.clue_scroll_elite", 4),
            ),
            rare = listOf(rare("item.dragon_pickaxe", 256)),
        ),
        tickets = 20, pet = "item.pet_chaos_elemental", petOneIn = 300,
    )

    // ───────────────────────────── Chaos Fanatic ─────────────────────────────

    const val CHAOS_FANATIC_KEY = "npc.chaos_fanatic"

    val CHAOS_FANATIC = WildBoss(
        key = "chaos_fanatic", name = "Chaos Fanatic", lootKey = CHAOS_FANATIC_KEY,
        spawns = listOf(Spawn(CHAOS_FANATIC_KEY, Tile(2980, 3847, 0), walkRadius = 8)),
        landing = Tile(2976, 3840, 0), wildLevel = 41, regions = intArrayOf(11835, 11836),
        drops = DropTable(
            always = listOf(DropEntry("item.bones", 1, 1)),
            main = listOf(
                one("item.zamorak_monk_top", 10), one("item.zamorak_monk_bottom", 10), e("item.battlestaff_noted", 5, 5, 6), one("item.ring_of_life", 6),
                one("item.splitbark_body", 6), one("item.splitbark_legs", 2), one("item.ancient_staff", 2),
                e("item.smoke_rune", 30, 30, 25), e("item.fire_rune", 250, 250, 8), e("item.chaos_rune", 175, 175, 8), e("item.blood_rune", 50, 50, 2),
                e("item.grimy_lantadyme_noted", 4, 4, 42),
                e("item.anchovy_pizza_noted", 8, 8, 22), e("item.monkfish", 3, 3, 11), one("item.shark", 7), one("item.prayer_potion4", 2),
                e("item.coins_995", 600, 4000, 5), e("item.uncut_sapphire_noted", 4, 4, 5), e("item.uncut_emerald_noted", 6, 6, 5),
                one("item.chaos_talisman", 5), one("item.nature_talisman", 5), e("item.pure_essence_noted", 250, 250, 3),
                e("item.wine_of_zamorak_noted", 10, 10, 3), one("item.sinister_key", 3), one("item.clue_scroll_hard", 2),
            ),
            rare = listOf(rare("item.odium_shard_1", 256), rare("item.malediction_shard_1", 256)),
        ),
        tickets = 15, pet = null, petOneIn = 0,
    )

    // ───────────────────────────── Crazy Archaeologist ─────────────────────────────

    const val CRAZY_ARCHAEOLOGIST_KEY = "npc.crazy_archaeologist"

    val CRAZY_ARCHAEOLOGIST = WildBoss(
        key = "crazy_archaeologist", name = "Crazy Archaeologist", lootKey = CRAZY_ARCHAEOLOGIST_KEY,
        spawns = listOf(Spawn(CRAZY_ARCHAEOLOGIST_KEY, Tile(2977, 3702, 0), walkRadius = 8)),
        landing = Tile(2972, 3696, 0), wildLevel = 23, regions = intArrayOf(11833),
        drops = DropTable(
            always = listOf(DropEntry("item.bones", 1, 1)),
            main = listOf(
                one("item.amulet_of_power", 12), one("item.red_dhide_body", 12), e("item.rune_knife", 10, 10, 12), e("item.rune_crossbow", 2, 2, 8),
                e("item.mud_rune", 30, 30, 40), e("item.dragon_arrow", 75, 75, 4),
                e("item.grimy_dwarf_weed_noted", 4, 4, 44),
                one("item.shark", 20), e("item.anchovy_pizza_noted", 8, 8, 10), e("item.potato_with_cheese", 3, 3, 10), one("item.prayer_potion4", 3),
                e("item.uncut_sapphire_noted", 4, 4, 21), e("item.uncut_emerald_noted", 6, 6, 21),
                e("item.coins_995", 522, 4000, 5), e("item.silver_ore_noted", 40, 40, 5), one("item.rusty_sword", 5), e("item.cannonball", 150, 150, 5),
                one("item.muddy_key", 4), e("item.red_dragonhide_noted", 10, 10, 4), e("item.white_berries_noted", 10, 10, 4),
                e("item.onyx_bolt_tips", 12, 12, 2), one("item.clue_scroll_hard", 1),
            ),
            rare = listOf(rare("item.fedora", 128), rare("item.odium_shard_2", 256), rare("item.malediction_shard_2", 256)),
        ),
        tickets = 15, pet = null, petOneIn = 0,
    )

    val all: List<WildBoss> = listOf(CALLISTO, VETION, VENENATIS, SCORPIA, CHAOS_ELEMENTAL, CHAOS_FANATIC, CRAZY_ARCHAEOLOGIST)

    /** Every lair region: force-loaded at boot and flagged multi-way (all seven are multi in OSRS). */
    val REGIONS: IntArray = all.flatMap { it.regions.toList() }.distinct().toIntArray()
}
