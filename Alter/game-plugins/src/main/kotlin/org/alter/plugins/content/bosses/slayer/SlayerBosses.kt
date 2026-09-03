package org.alter.plugins.content.bosses.slayer

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **Slayer bosses** — Kronos port #9: Kraken, Cerberus, the Thermonuclear Smoke Devil,
 * Skotizo and the Demonic Gorillas (`activities/bosses/slayer`, `bosses/Skotizo.java`,
 * `bosses/DemonicGorilla.java` + the `CaveKraken`/`SmokeDevil` bases they extend).
 *
 * Stats from `data/npcs/combat/<Name>.json`; loot from `data/npcs/drops/eco/<Name>.json`
 * folded flat (sub-tables rescaled to ≈ equal weight). Uniques keep OSRS odds (trident 1/512,
 * tentacle 1/400, boot crystals + smouldering stone 1/512, occult 1/350, smoke staff 1/512,
 * dark claw 1/25, Skotos 1/65, zenyte shard 1/300, ballista parts 1/500); jars and pets use the
 * Vorkath-pilot 1/1000 scale. Slayer gates are the donor's (Kraken 87, Cerberus 91, Thermy 93)
 * and are enforced by the engine's `slayerReq` attack check.
 */
object SlayerBosses {

    data class SlayerBoss(
        val key: String,
        val name: String,
        val lootKey: String,
        val drops: DropTable,
        val pet: String?,
        val petOneIn: Int,
    )

    private fun e(key: String, min: Int, max: Int, w: Int) = DropEntry(key, min, max, weight = w)
    private fun one(key: String, w: Int) = DropEntry(key, 1, 1, weight = w)
    private fun rare(key: String, oneIn: Int) = DropEntry(key, 1, 1, oneInN = oneIn, announce = true, log = true)

    // ───────────────────────────── Kraken ─────────────────────────────

    const val KRAKEN_WHIRLPOOL = "npc.whirlpool_496"
    const val KRAKEN = "npc.kraken"
    const val TENTACLE_WHIRLPOOL = "npc.whirlpool_5534"
    const val TENTACLE = "npc.enormous_tentacle"

    /** Kronos kraken_cove.json: the boss whirlpool at 2278,10034; tentacles at the donor's offsets. */
    val KRAKEN_SPAWN = Tile(2278, 10034, 0)
    val TENTACLE_OFFSETS = listOf(-3 to 0, -3 to 4, 6 to 0, 6 to 4)
    val KRAKEN_LANDING = Tile(2276, 10030, 0)
    const val KRAKEN_REGION = 9116
    const val KRAKEN_RESPAWN_TICKS = 30

    val KRAKEN_BOSS = SlayerBoss(
        key = "kraken", name = "the Kraken", lootKey = KRAKEN,
        drops = DropTable(
            main = listOf(
                one("item.pirate_boots", 14), one("item.mystic_water_staff", 14), one("item.rune_warhammer", 14),
                one("item.rune_longsword", 7), one("item.mystic_robe_top", 7), one("item.mystic_robe_bottom", 2),
                e("item.water_rune", 400, 400, 21), e("item.mist_rune", 100, 100, 11), e("item.chaos_rune", 250, 250, 11),
                e("item.death_rune", 150, 150, 11), e("item.blood_rune", 60, 60, 4), e("item.soul_rune", 50, 50, 4),
                e("item.watermelon_seed", 24, 24, 26), e("item.torstol_seed", 2, 2, 26), one("item.magic_seed", 8),
                e("item.seaweed_noted", 125, 125, 15), e("item.battlestaff_noted", 10, 20, 10), e("item.unpowered_orb_noted", 50, 50, 10),
                e("item.diamond_noted", 8, 8, 10), e("item.oak_plank_noted", 60, 60, 5), e("item.runite_bar", 2, 2, 5),
                e("item.raw_shark_noted", 50, 50, 2), e("item.raw_monkfish_noted", 100, 100, 2), e("item.grimy_snapdragon_noted", 6, 6, 2),
                e("item.coins_995", 10_000, 19_999, 1), e("item.shark", 5, 5, 12), e("item.edible_seaweed", 5, 5, 12), one("item.harpoon", 7),
                one("item.bucket", 5), e("item.sanfew_serum4", 2, 2, 7), one("item.crystal_key", 4), one("item.rusty_sword", 4),
                e("item.antidote4_noted", 2, 2, 4), one("item.dragonstone_ring", 4), one("item.clue_scroll_elite", 1),
            ),
            rare = listOf(
                rare("item.trident_of_the_seas_full", 512), rare("item.kraken_tentacle", 400), rare("item.jar_of_dirt", 1000),
            ),
        ),
        pet = "item.pet_kraken", petOneIn = 1000,
    )

    // ───────────────────────────── Cerberus ─────────────────────────────

    const val CERBERUS = "npc.cerberus"
    const val SOUL_RANGED = "npc.summoned_soul"
    const val SOUL_MAGIC = "npc.summoned_soul_5868"
    const val SOUL_MELEE = "npc.summoned_soul_5869"

    /** Kronos cerberus.json: the three lairs (west / north / east), plane 0. */
    val CERBERUS_SPAWNS = listOf(Tile(1238, 1250, 0), Tile(1302, 1314, 0), Tile(1366, 1250, 0))
    val CERBERUS_LANDING = Tile(1240, 1240, 0)
    val CERBERUS_REGIONS = intArrayOf(4883, 5140, 5395)

    val CERBERUS_BOSS = SlayerBoss(
        key = "cerberus", name = "Cerberus", lootKey = CERBERUS,
        drops = DropTable(
            always = listOf(DropEntry("item.ashes", 1, 1)),
            main = listOf(
                one("item.lava_battlestaff", 13), e("item.battlestaff_noted", 6, 6, 13), one("item.rune_2h_sword", 9), one("item.rune_halberd", 9),
                one("item.black_dhide_body", 9), one("item.rune_full_helm", 4), one("item.rune_axe", 4), one("item.rune_pickaxe", 4),
                one("item.rune_chainbody", 2), one("item.rune_platebody", 2),
                e("item.pure_essence_noted", 300, 300, 20), e("item.fire_rune", 300, 300, 10), e("item.death_rune", 100, 100, 10),
                e("item.soul_rune", 100, 100, 10), e("item.blood_rune", 60, 60, 3), e("item.runite_bolts_unf", 40, 40, 3), e("item.cannonball", 50, 50, 3),
                e("item.super_restore4", 2, 2, 36), e("item.torstol_seed", 3, 3, 18), e("item.grimy_torstol_noted", 6, 6, 6),
                e("item.coal_noted", 120, 120, 45), e("item.runite_ore_noted", 5, 5, 15),
                e("item.coins_995", 10_000, 20_000, 10), one("item.unholy_symbol", 10), e("item.summer_pie", 3, 3, 10), e("item.ashes_noted", 50, 50, 10),
                e("item.dragon_bones_noted", 20, 20, 5), e("item.wine_of_zamorak_noted", 15, 15, 5), e("item.fire_orb_noted", 20, 20, 5),
                e("item.uncut_diamond_noted", 5, 5, 2), e("item.key_master_teleport", 3, 3, 1), one("item.clue_scroll_elite", 1),
            ),
            rare = listOf(
                rare("item.primordial_crystal", 512), rare("item.pegasian_crystal", 512), rare("item.eternal_crystal", 512),
                rare("item.smouldering_stone", 512), rare("item.jar_of_souls", 1000),
            ),
        ),
        pet = "item.hellpuppy", petOneIn = 1000,
    )

    // ───────────────────────────── Thermonuclear Smoke Devil ─────────────────────────────

    const val THERMY = "npc.thermonuclear_smoke_devil"
    val THERMY_SPAWN = Tile(2360, 9452, 0)
    val THERMY_LANDING = Tile(2360, 9445, 0)
    const val THERMY_REGION = 9363

    val THERMY_BOSS = SlayerBoss(
        key = "thermonuclear_smoke_devil", name = "the Thermonuclear Smoke Devil", lootKey = THERMY,
        drops = DropTable(
            always = listOf(DropEntry("item.ashes", 1, 1)),
            main = listOf(
                one("item.rune_dagger", 12), one("item.rune_scimitar", 12), one("item.rune_battleaxe", 6), one("item.mystic_air_staff", 6),
                one("item.mystic_fire_staff", 9), one("item.dragon_scimitar", 6), one("item.ancient_staff", 6),
                one("item.red_dhide_body", 30), one("item.rune_chainbody", 30),
                e("item.smoke_rune", 100, 100, 22), e("item.air_rune", 300, 300, 11), e("item.soul_rune", 60, 60, 11), e("item.rune_knifep_5667", 50, 50, 11), e("item.rune_arrow", 100, 100, 4),
                e("item.snapdragon_seed", 2, 2, 30), one("item.magic_seed", 30),
                e("item.pure_essence_noted", 300, 300, 7), e("item.desert_goat_horn_noted", 50, 50, 7), e("item.molten_glass_noted", 100, 100, 7),
                e("item.mithril_bar_noted", 20, 20, 7), e("item.grimy_toadflax_noted", 15, 15, 7), e("item.coal_noted", 150, 150, 7),
                e("item.gold_ore_noted", 200, 200, 4), e("item.onyx_bolt_tips", 12, 12, 4), e("item.grapes_noted", 100, 100, 4), e("item.diamond_noted", 10, 10, 4), e("item.magic_logs_noted", 20, 20, 1),
                e("item.ugthanki_kebab", 3, 3, 19), e("item.tuna_potato", 3, 3, 19), e("item.prayer_potion4", 2, 2, 19), e("item.sanfew_serum4", 2, 2, 4),
                e("item.coins_995", 10_000, 19_996, 10), one("item.tinderbox", 10), one("item.bullseye_lantern", 10), one("item.fire_talisman", 10),
                one("item.dragonstone_ring", 10), one("item.crystal_key", 10), one("item.clue_scroll_elite", 1),
            ),
            rare = listOf(rare("item.occult_necklace", 350), rare("item.smoke_battlestaff", 512), rare("item.dragon_chainbody", 1000)),
        ),
        pet = "item.pet_smoke_devil", petOneIn = 1000,
    )

    // ───────────────────────────── Skotizo ─────────────────────────────

    const val SKOTIZO = "npc.skotizo"
    /** Dormant (unattackable) → awakened (attackable) altar pairs: south, west, north, east. */
    val ALTARS = listOf(
        Altar("npc.altar", "npc.awakened_altar", Tile(1696, 9871, 0)),
        Altar("npc.altar_7291", "npc.awakened_altar_7290", Tile(1678, 9888, 0)),
        Altar("npc.altar_7293", "npc.awakened_altar_7292", Tile(1694, 9904, 0)),
        Altar("npc.altar_7295", "npc.awakened_altar_7294", Tile(1714, 9888, 0)),
    )
    data class Altar(val dormantKey: String, val awakenedKey: String, val src: Tile)

    /** Skotizo's chamber (region 6810) as the instance source; the catacombs altar is the exit. */
    val SKOTIZO_SOURCE = Area(1664, 9856, 1727, 9919)
    val SKOTIZO_SPAWN_SRC = Tile(1693, 9885, 0)
    val SKOTIZO_PLAYER_SRC = Tile(1695, 9878, 0)
    val SKOTIZO_EXIT = Tile(1665, 10048, 0)
    const val SKOTIZO_ALTAR_OBJ = "object.altar_28900"
    const val DARK_TOTEM = "item.dark_totem"

    val SKOTIZO_BOSS = SlayerBoss(
        key = "skotizo", name = "Skotizo", lootKey = SKOTIZO,
        drops = DropTable(
            always = listOf(DropEntry("item.clue_scroll_hard", 1, 1), DropEntry("item.ancient_shard", 1, 3)),
            main = listOf(
                e("item.death_rune", 500, 500, 10), e("item.soul_rune", 450, 450, 10), e("item.blood_rune", 450, 450, 10),
                e("item.rune_platebody_noted", 3, 3, 8), e("item.rune_kiteshield_noted", 3, 3, 8), e("item.rune_platelegs_noted", 3, 3, 7), e("item.rune_plateskirt_noted", 3, 3, 7),
                e("item.grimy_ranarr_weed_noted", 40, 40, 10), e("item.grimy_snapdragon_noted", 20, 20, 10), e("item.grimy_torstol_noted", 20, 20, 10),
                e("item.uncut_dragonstone_noted", 10, 10, 4), e("item.battlestaff_noted", 25, 25, 4), e("item.onyx_bolt_tips", 40, 40, 4),
                e("item.adamantite_ore_noted", 75, 75, 4), e("item.runite_bar_noted", 20, 20, 4), e("item.raw_anglerfish_noted", 60, 60, 4), e("item.mahogany_plank_noted", 150, 150, 4),
                one("item.clue_scroll_elite", 3), one("item.dark_totem", 4), one("item.shield_left_half", 2),
            ),
            rare = listOf(rare("item.dark_claw", 25), rare("item.uncut_onyx", 128), rare("item.jar_of_darkness", 500)),
        ),
        pet = "item.skotos", petOneIn = 65,
    )

    // ───────────────────────────── Demonic Gorillas ─────────────────────────────

    /** Prayer forms: 7144 blocks melee, 7145 blocks missiles, 7146 blocks magic. */
    const val GORILLA_MELEE = "npc.demonic_gorilla"
    const val GORILLA_RANGED = "npc.demonic_gorilla_7145"
    const val GORILLA_MAGIC = "npc.demonic_gorilla_7146"
    val GORILLA_FORMS = listOf(GORILLA_MELEE, GORILLA_RANGED, GORILLA_MAGIC)

    /** Crash Site Cavern — a dozen of the donor's monkey_madness.json spawns. */
    val GORILLA_SPAWNS = listOf(
        Tile(2074, 5651, 0), Tile(2074, 5673, 0), Tile(2077, 5644, 0), Tile(2087, 5676, 0),
        Tile(2097, 5675, 0), Tile(2093, 5654, 0), Tile(2102, 5652, 0), Tile(2106, 5660, 0),
        Tile(2110, 5649, 0), Tile(2127, 5675, 0), Tile(2141, 5677, 0), Tile(2152, 5656, 0),
    )
    val GORILLA_LANDING = Tile(2090, 5660, 0)
    val GORILLA_REGIONS = intArrayOf(8280, 8536)
    const val GORILLA_RESPAWN_TICKS = 50

    val GORILLA_BOSS = SlayerBoss(
        key = "demonic_gorilla", name = "a Demonic gorilla", lootKey = GORILLA_MELEE,
        drops = DropTable(
            always = listOf(DropEntry("item.ashes", 1, 1)),
            main = listOf(
                one("item.rune_platelegs", 6), one("item.rune_plateskirt", 6), one("item.rune_chainbody", 6), e("item.runite_bolts", 100, 150, 6), one("item.dragon_scimitar", 6),
                e("item.law_rune", 50, 75, 6), e("item.death_rune", 50, 75, 6), e("item.prayer_potion3", 2, 2, 6), one("item.saradomin_brew2", 6), e("item.shark", 2, 3, 6),
                e("item.grimy_kwuarm_noted", 7, 13, 5), e("item.grimy_cadantine_noted", 7, 13, 5), e("item.grimy_lantadyme_noted", 7, 13, 5), e("item.grimy_dwarf_weed_noted", 7, 12, 3),
                e("item.watermelon_seed", 30, 30, 3), e("item.ranarr_seed", 2, 2, 1), e("item.snapdragon_seed", 2, 2, 1), e("item.torstol_seed", 1, 2, 1),
                e("item.papaya_tree_seed", 2, 2, 1), e("item.palm_tree_seed", 2, 2, 1), e("item.willow_seed", 2, 2, 1), e("item.maple_seed", 2, 2, 1),
                e("item.yew_seed", 2, 2, 1), e("item.magic_seed", 2, 2, 1), e("item.spirit_seed", 2, 2, 1),
                e("item.diamond_noted", 4, 6, 10), e("item.adamantite_bar_noted", 6, 6, 10), e("item.runite_bar_noted", 3, 3, 10),
                e("item.coins_995", 15_000, 25_000, 7), e("item.javelin_shaft", 266, 1238, 7), e("item.rune_javelin_heads", 31, 55, 7), e("item.dragon_javelin_heads", 5, 43, 7),
                one("item.clue_scroll_hard", 1), one("item.clue_scroll_elite", 1),
            ),
            rare = listOf(
                rare("item.zenyte_shard", 300), rare("item.ballista_limbs", 500), rare("item.ballista_spring", 500),
                rare("item.heavy_frame", 500), rare("item.monkey_tail", 500),
            ),
        ),
        pet = null, petOneIn = 0,
    )

    val all: List<SlayerBoss> = listOf(KRAKEN_BOSS, CERBERUS_BOSS, THERMY_BOSS, SKOTIZO_BOSS, GORILLA_BOSS)

    /** Force-loaded at world init (Skotizo's chamber is loaded by the instance allocator). */
    val REGIONS: IntArray = intArrayOf(KRAKEN_REGION) + CERBERUS_REGIONS + intArrayOf(THERMY_REGION) + GORILLA_REGIONS

    /** Multi-way: the smoke devil dungeon and the Crash Site Cavern. Kraken and Cerberus are single-way. */
    val MULTI_REGIONS: IntArray = intArrayOf(THERMY_REGION) + GORILLA_REGIONS
}
