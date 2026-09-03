package org.alter.plugins.content.economy.grandexchange

import org.alter.rscm.RSCM.getRSCM

/**
 * The **commodity allowlist** for the GE's NPC backstop — the items whose price the *stores* pin
 * ("minimums set by our stores"). Everything else — gear, megarares, the currency items — floats on
 * the pure player market.
 *
 * Two tiers, split by the 2026-09 arbitrage audit (`docs/economy-arbitrage-audit-2026-09.md`):
 *
 *  - **Floor-only** raw materials: the NPC BUYS at the shared 70% rate (a skiller can always sell)
 *    but never SELLS. Unlimited NPC-sold bars, logs, gems and essence at 100% of value were the tap
 *    behind every S0 loop (GE bars → smith a platebody → 70% buyback = 8,448 gp a piece), and the
 *    skilling shops already sell the low tiers of these with finite stock.
 *  - **Two-sided** boring necessities the shops already sell without limit: the NPC also SELLS at
 *    100% of value, so runes, ammo, cooked food and planks are never blocked by an empty exchange
 *    (doc 04 §7: "magic ammo should not be blocked by empty GE").
 *
 * Both tiers deliberately exclude items the coin shops price *above* cache value on purpose
 * (skilling protection) or that are load-bearing sinks, so the backstop can never undercut them:
 *  - `death_rune`, `adamant_arrow`, cooked `swordfish` — priced at a premium to protect RC/Fletch/Cook.
 *  - `runite_bar` — the Forge's marquee feedstock sink.
 *  - `dragon_bones` — the Prayer gate (buy-cheap/sell-back loop risk).
 *
 * Keys resolve defensively (an unknown key is simply skipped), so the lists are safe to extend.
 */
object GrandExchangeCommodities {

    /** NPC buys at the floor, never sells. */
    private val FLOOR_ONLY: List<String> = listOf(
        // Runecrafting essence — at 4 gp a piece an NPC-sold essence made Runecraft a 2 → 168 gp converter.
        "pure_essence", "rune_essence",
        // Smithing bars (no runite_bar: the Forge's marquee sink)
        "bronze_bar", "iron_bar", "steel_bar", "silver_bar", "gold_bar", "mithril_bar", "adamantite_bar",
        // Mining ores + clay
        "copper_ore", "tin_ore", "iron_ore", "silver_ore", "gold_ore", "mithril_ore", "adamantite_ore",
        "coal", "clay",
        // Woodcutting logs
        "logs", "oak_logs", "willow_logs", "maple_logs", "yew_logs", "magic_logs",
        // Crafting mats / secondaries
        "feather", "flax", "bow_string", "wool", "leather", "soft_clay", "molten_glass",
        "uncut_sapphire", "uncut_emerald", "uncut_ruby", "uncut_diamond",
        // Prayer (no dragon_bones: the Prayer gate)
        "bones", "big_bones",
        // Herblore herbs (clean)
        "guam_leaf", "marrentill", "tarromin", "harralander", "ranarr_weed", "irit_leaf", "avantoe",
        "kwuarm", "cadantine", "dwarf_weed", "torstol", "toadflax", "snapdragon", "lantadyme",
        // Raw fish
        "raw_trout", "raw_salmon", "raw_tuna", "raw_lobster", "raw_monkfish", "raw_shark", "raw_manta_ray",
    )

    /** NPC buys at the floor AND sells at the ceiling (full value). */
    private val TWO_SIDED: List<String> = listOf(
        // Runes — low + high (no death_rune: coin-shop premium protects Runecrafting)
        "air_rune", "water_rune", "earth_rune", "fire_rune", "mind_rune", "body_rune", "chaos_rune",
        "cosmic_rune", "nature_rune", "law_rune", "blood_rune", "soul_rune", "astral_rune",
        // Ammo (no adamant_arrow: coin-shop premium protects Fletching)
        "bronze_arrow", "iron_arrow", "steel_arrow", "mithril_arrow",
        // Cooked food (no cooked swordfish: coin-shop premium protects Cooking)
        "trout", "salmon", "tuna", "lobster", "monkfish", "shark",
        // Construction planks
        "plank", "oak_plank", "teak_plank", "mahogany_plank",
    )

    private fun resolve(keys: List<String>): Set<Int> =
        keys.mapNotNull { runCatching { getRSCM("item.$it") }.getOrNull() }.toSet()

    private val floorOnlyIds: Set<Int> by lazy { resolve(FLOOR_ONLY) }
    private val twoSidedIds: Set<Int> by lazy { resolve(TWO_SIDED) }

    /** True if [itemId] is a store-backstopped commodity (gets an NPC floor; maybe a ceiling too). */
    fun isCommodity(itemId: Int): Boolean = itemId in floorOnlyIds || itemId in twoSidedIds

    /** True if the NPC also SELLS [itemId] at full value (two-sided tier). */
    fun isNpcSold(itemId: Int): Boolean = itemId in twoSidedIds
}
