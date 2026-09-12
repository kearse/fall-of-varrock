package org.alter.plugins.content.minigames.blastfurnace

import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **Blast Furnace** — classic OSRS, built from the wiki mechanics page and strategy guide
 * (2026-09-03, PR #341) against the rev-228 Keldagrim furnace room (region 7757). See
 * `docs/pvm/minigames-b-spec.md`. Team 2 ruling: the OSRS **coffer** model (72,000 coins per
 * hour drawn while your ore is in the machine, 25,000 minimum deposit, everyone pays), no
 * Ordan shop, OSRS Smithing XP, no coin rewards.
 *
 * The dwarves run the machine ("official world" behaviour): no pedalling, pumping or stoking.
 *
 * **History.** PR #341 was merged into the Guardians of the Rift branch (`pvm/12-…`), which never
 * reached `main` — so the room stood in the cache with nothing behind it. Restored 2026-09-12 by
 * *The Guns of Asgarnia* (Asgarnia campaign quest 3, `docs/quests/the-guns-of-asgarnia.md`), whose
 * design rule is that the Blast Furnace is the canonical steel-production gameplay and must be the
 * normal reusable system, never a quest-only stand-in. Two seams were added for it — and for any
 * later story/economy content — without the furnace knowing who listens: [feeWaivers] and
 * [barListeners].
 */
object BlastFurnace {

    const val REGION = 7757

    /** Where the portal (`TeleportRegistry` row [PORTAL_KEY]) lands: the room's south side, by the Foreman. */
    val LANDING = Tile(1940, 4958, 0)
    const val PORTAL_KEY = "blast_furnace"

    // objects — verified against the cache with `objCheck` (2026-09-12):
    //   9100 Conveyor belt [Put-ore-on] (the OTHER belt segments are 9101, no options)
    //   9098 Melting Pot [Check]
    //   9092 the dispenser MULTI-LOC (varbit 936 → 9093 empty [Check] / 9094 (no options) /
    //        9095, 9096 bars ready [Take, Check]). The click dispatches on the CHILD id (see
    //        docs/pvm/README.md, "Multi-loc clicks dispatch on the CHILD id"), so the children are bound.
    //   29328 Coffer [Use] (the room's own 26706 is a null def with no options — spawned as a DynamicObject)
    //   26707 Bank chest [Use, Collect]
    const val BELT_KEY = "object.conveyor_belt"
    val BELT_TILE = Tile(1943, 4967, 0)
    const val MELTING_POT_KEY = "object.melting_pot"
    const val DISPENSER_VARBIT = 936
    const val DISPENSER_EMPTY = 0
    const val DISPENSER_READY = 3
    const val DISPENSER_EMPTY_KEY = "object.bar_dispenser"           // 9093 [Check]
    val DISPENSER_READY_KEYS = listOf("object.bar_dispenser_9095", "object.bar_dispenser_9096") // [Take, Check]
    val DISPENSER_TILE = Tile(1940, 4963, 0)
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
    const val FOREMAN_NAME = "Blast Furnace Foreman"
    val FOREMAN_TILE = Tile(1942, 4958, 0)
    val DWARVES = listOf(
        "npc.dumpy" to Tile(1946, 4962, 0),
        "npc.stumpy" to Tile(1950, 4966, 0),
        "npc.pumpy" to Tile(1951, 4962, 0),
        "npc.numpty" to Tile(1944, 4970, 0),
        "npc.thumpy" to Tile(1949, 4970, 0),
    )

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

    val STEEL: Bar get() = BARS.first { it.bar == "item.steel_bar" }

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

    // ---- seams (one-way: the furnace never knows who listens) ---------------------------------

    /**
     * Fee waivers: while any registered predicate is true for a player, the coffer is neither
     * required nor drawn for them — Keldagrim runs the order at its own expense. The Guns of
     * Asgarnia registers one for its trial order; nothing else should need it often.
     */
    val feeWaivers: MutableList<(Player) -> Boolean> = ArrayList()

    /** Bars actually TAKEN from the dispenser: (player, bar, count). Quests / ledgers subscribe here. */
    val barListeners: MutableList<(Player, Bar, Int) -> Unit> = ArrayList()

    /** Ore of [p]'s still on the belt or in the dispenser (installed by the plugin; 0 when the room is down). */
    @Volatile
    var oreInMachine: (Player) -> Int = { 0 }

    fun feeWaived(p: Player): Boolean = feeWaivers.any { w -> runCatching { w(p) }.getOrDefault(false) }

    internal fun notifyBars(p: Player, bar: Bar, n: Int) {
        barListeners.forEach { l -> runCatching { l(p, bar, n) } }
    }
}
