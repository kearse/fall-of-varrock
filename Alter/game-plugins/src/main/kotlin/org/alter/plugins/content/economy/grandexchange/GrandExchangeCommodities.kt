package org.alter.plugins.content.economy.grandexchange

import org.alter.rscm.RSCM.getRSCM

/**
 * The **commodity allowlist** for the GE's NPC backstop — the items whose price the *stores* pin
 * ("minimums set by our stores"). Only these get an NPC floor/ceiling ([GrandExchange] uses cache
 * value as the ceiling and 85% as the floor, matching the Trading Post); everything else — gear,
 * megarares, the currency items — floats on the pure player market.
 *
 * This is the generalisation of `TradingPostPlugin.seedKeys`: the everyday coin-store mats,
 * secondaries, runes, food and feedstock. It deliberately **excludes** items the coin shops price
 * *above* cache value on purpose (skilling protection) or that are load-bearing sinks, so the
 * backstop can never undercut them:
 *  - `death_rune`, `adamant_arrow`, cooked `swordfish` — priced at a premium to protect RC/Fletch/Cook.
 *  - `runite_bar` — the Forge's marquee feedstock sink.
 *  - `dragon_bones` — the Prayer gate (buy-cheap/sell-back loop risk).
 *
 * Keys resolve defensively (an unknown key is simply skipped), so the list is safe to extend.
 */
object GrandExchangeCommodities {
    private val KEYS: List<String> = listOf(
        // Runes — low + high (no death_rune: coin-shop premium protects Runecrafting)
        "air_rune", "water_rune", "earth_rune", "fire_rune", "mind_rune", "body_rune", "chaos_rune",
        "cosmic_rune", "nature_rune", "law_rune", "blood_rune", "soul_rune", "astral_rune",
        // Runecrafting essence
        "pure_essence", "rune_essence",
        // Smithing bars (no runite_bar: the Forge's marquee sink)
        "bronze_bar", "iron_bar", "steel_bar", "silver_bar", "gold_bar", "mithril_bar", "adamantite_bar",
        // Mining ores + clay
        "copper_ore", "tin_ore", "iron_ore", "silver_ore", "gold_ore", "mithril_ore", "adamantite_ore",
        "coal", "clay",
        // Woodcutting logs
        "logs", "oak_logs", "willow_logs", "maple_logs", "yew_logs", "magic_logs",
        // Ammo (no adamant_arrow: coin-shop premium protects Fletching)
        "bronze_arrow", "iron_arrow", "steel_arrow", "mithril_arrow",
        // Crafting mats / secondaries
        "feather", "flax", "bow_string", "wool", "leather", "soft_clay", "molten_glass",
        "uncut_sapphire", "uncut_emerald", "uncut_ruby", "uncut_diamond",
        // Prayer (no dragon_bones: the Prayer gate)
        "bones", "big_bones",
        // Herblore herbs (clean)
        "guam_leaf", "marrentill", "tarromin", "harralander", "ranarr_weed", "irit_leaf", "avantoe",
        "kwuarm", "cadantine", "dwarf_weed", "torstol", "toadflax", "snapdragon", "lantadyme",
        // Fishing/Cooking food — raw + cooked (no cooked swordfish: coin-shop premium protects Cooking)
        "raw_trout", "trout", "raw_salmon", "salmon", "raw_tuna", "tuna", "raw_lobster", "lobster",
        "raw_monkfish", "monkfish", "raw_shark", "shark", "raw_manta_ray",
        // Construction planks
        "plank", "oak_plank", "teak_plank", "mahogany_plank",
    )

    private val ids: Set<Int> by lazy {
        KEYS.mapNotNull { runCatching { getRSCM("item.$it") }.getOrNull() }.toSet()
    }

    /** True if [itemId] is a store-backstopped commodity (gets an NPC floor/ceiling). */
    fun isCommodity(itemId: Int): Boolean = itemId in ids
}
