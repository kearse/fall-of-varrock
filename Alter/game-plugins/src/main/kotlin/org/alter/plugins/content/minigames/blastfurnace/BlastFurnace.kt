package org.alter.plugins.content.minigames.blastfurnace

import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey

/**
 * **Blast Furnace** — classic OSRS, built from the wiki mechanics page and strategy guide
 * (2026-09-03) against the rev-228 Keldagrim room (region 7757). See
 * `docs/pvm/minigames-b-spec.md`. Team 2 ruling: the OSRS **coffer** model (72,000 coins per
 * hour drawn while your ore is in the machine, 25,000 minimum deposit, everyone pays), no
 * Ordan shop, OSRS Smithing XP, no coin rewards.
 *
 * The dwarves run the machine ("official world" behaviour): no pedalling, pumping or stoking.
 */
object BlastFurnace {

    const val REGION = 7757
    val LANDING = Tile(1940, 4958, 0)

    // objects (the dispenser is the varbit-936 multi-loc 9092 → 9093 empty / 9095-9096 bars ready)
    const val BELT_KEY = "object.conveyor_belt"
    const val MELTING_POT_KEY = "object.melting_pot"
    const val DISPENSER_BASE_KEY = "object.null_9092"
    const val DISPENSER_VARBIT = 936
    const val DISPENSER_EMPTY = 0
    const val DISPENSER_READY = 3
    val STOVE_KEYS = listOf("object.stove", "object.stove_9086", "object.stove_9087")
    const val PUMP_KEY = "object.pump"
    const val PEDALS_KEY = "object.pedals"
    const val GAUGE_KEY = "object.temperature_gauge"
    const val COFFER_ID = 29328
    const val COFFER_KEY = "object.coffer_29328"
    val COFFER_TILE = Tile(1948, 4958, 0)
    const val BANK_CHEST_KEY = "object.bank_chest_26707"

    // npcs
    const val FOREMAN_KEY = "npc.blast_furnace_foreman"
    val DWARVES = listOf("npc.dumpy" to Tile(1946, 4962, 0), "npc.stumpy" to Tile(1950, 4966, 0), "npc.pumpy" to Tile(1951, 4962, 0), "npc.numpty" to Tile(1944, 4970, 0), "npc.thumpy" to Tile(1949, 4970, 0))

    // machine limits (wiki)
    const val MAX_ORE = 28
    const val MAX_COAL = 254
    const val MAX_BARS_PER_TYPE = 28
    const val TICKS_PER_BAR = 2
    const val COAL_BAG_CAP = 27

    // coffer (Team 2)
    const val COFFER_MIN_DEPOSIT = 25_000
    const val COFFER_PER_MINUTE = 1_200
    const val COFFER_MAX = 5_000_000

    data class Bar(val name: String, val bar: String, val ores: Map<String, Int>, val coal: Int, val level: Int, val xp: Double, val goldsmithXp: Double = 0.0)

    val BARS = listOf(
        Bar("Bronze", "item.bronze_bar", mapOf("item.copper_ore" to 1, "item.tin_ore" to 1), 0, 1, 6.2),
        Bar("Iron", "item.iron_bar", mapOf("item.iron_ore" to 1), 0, 15, 12.5),
        Bar("Silver", "item.silver_bar", mapOf("item.silver_ore" to 1), 0, 20, 13.7),
        Bar("Steel", "item.steel_bar", mapOf("item.iron_ore" to 1), 1, 30, 17.5),
        Bar("Gold", "item.gold_bar", mapOf("item.gold_ore" to 1), 0, 40, 22.5, goldsmithXp = 56.2),
        Bar("Mithril", "item.mithril_bar", mapOf("item.mithril_ore" to 1), 2, 50, 30.0),
        Bar("Adamant", "item.adamantite_bar", mapOf("item.adamantite_ore" to 1), 3, 70, 37.5),
        Bar("Rune", "item.runite_bar", mapOf("item.runite_ore" to 1), 4, 85, 50.0),
    )

    const val COAL = "item.coal"
    const val COAL_BAG = "item.coal_bag_12019" // "item.coal_bag" is the retired 764 def (Drop only); 12019 is the real Fill/Open/Check/Empty bag
    const val OPEN_COAL_BAG = "item.open_coal_bag"
    const val ICE_GLOVES = "item.ice_gloves"
    const val SMITHS_GLOVES_I = "item.smiths_gloves_i"
    const val GOLDSMITH_GAUNTLETS = "item.goldsmith_gauntlets"
    const val BUCKET_OF_WATER = "item.bucket_of_water"
    const val BUCKET = "item.bucket"

    val COFFER = AttributeKey<Int>(persistenceKey = "bf_coffer")
    val COAL_IN_BAG = AttributeKey<Int>(persistenceKey = "coal_bag")
    val BARS_MADE = AttributeKey<Int>(persistenceKey = "bf_bars")
}
