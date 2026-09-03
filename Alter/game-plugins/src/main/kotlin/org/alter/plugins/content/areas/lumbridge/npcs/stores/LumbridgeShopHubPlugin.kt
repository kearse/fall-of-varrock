package org.alter.plugins.content.areas.lumbridge.npcs.stores

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.Shop
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.mechanics.shops.bindVendorTalkAndTrade
import org.alter.plugins.content.economy.grandexchange.GeCurrencyPrices
import org.alter.plugins.content.economy.grandexchange.currencyBuyShop
import org.alter.plugins.content.war.ApprenticeArmoury
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

/**
 * Centralised Lumbridge shopping hub — every store NPC lives on two facing rows at the castle
 * front so players have one place to gear up and stock up.
 *
 * Laid out as a market street in the open courtyard pocket east of the castle wall — two
 * facing rows with a walkable aisle (x3220-3223) between them. Tiles verified against the
 * cache collision dump (the castle east wall, x=3217, is NOT walkable — avoid it):
 *   West row (x=3219, facing EAST): Warrior (weapons + rank armour), Ranger, Magic
 *   East row (x=3224, facing WEST): Skilling, Fishing, Farming & Herblore, Rewards
 *
 * Economy rules baked in (per the server's economy design — shops are a STARTER supply, not a
 * substitute for skills):
 *   - Melee/ranged weapons capped at ADAMANT; ARMOUR is rank-gated (shared apprentice armoury,
 *     lesser pieces only — no full helm/platebody/kiteshield). Rune+ gear comes from content.
 *   - Food: cooked only up to swordfish; higher fish sold RAW (Cooking gate to use them).
 *   - Potions: basic finished low potions only; high potions sold UNFINISHED + secondaries, so
 *     the final brew step requires Herblore (Herblore gate).
 *   - Runes: stocked up to DEATH (Runecrafting still does bulk/blood+); Zaff also lifts players
 *     to the rune altar.
 *   - Materials: low/primary only, but ALL farming seeds are available so any herb can be grown.
 *
 * Big stores (Farming/Herblore, Skilling, Warrior, Rewards) are TABBED storefronts ([ShopTabs]):
 * clicking the vendor opens the shop window directly, with a tab per focused sub-shop (each kept
 * under the 40-slot shop display limit) — no dialogue menus.
 *
 * Currencies: GP for the coin stores; existing Slayer/Boss/Vote point counters for the reward
 * exchange. (PK / Skilling-point / Donator / Prestige shops are staged for later — they need
 * their own currency + earning systems first.)
 *
 * Stock is resolved defensively ([resolveOrNull]) so a missing cache key is skipped rather than
 * crashing the plugin, and bindings adapt to whatever click options an npc actually has.
 */
class LumbridgeShopHubPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    /** One item line in a shop. Null prices fall back to cache value via the currency. [guarded]
     *  (ticket shops only) registers the ware with [SpecialShopGuard]: gear and cosmetics bought for a
     *  currency may never be NPC-converted to gp; consumables that ordinary shops also sell stay
     *  vendorable, so they are marked false. */
    private data class Ware(val key: String, val amount: Int, val sell: Int? = null, val buy: Int? = null, val guarded: Boolean = true)

    // ----------------------------------- shop builders -----------------------------------

    private fun coinShop(name: String, policy: PurchasePolicy, wares: List<Ware>) {
        val stock = wares.mapNotNull { w -> resolveOrNull(w.key)?.let { ShopItem(it, w.amount, w.sell, w.buy) } }
        // Shops that accept arbitrary loot (not just their own stock) need empty slots for
        // sold items to land in — otherwise selling anything not already stocked reports
        // "the shop has run out of space". Stock-only / no-buy shops need no spare slots.
        val buffer = if (policy == PurchasePolicy.BUY_TRADEABLES || policy == PurchasePolicy.BUY_ALL) {
            Shop.DEFAULT_STOCK_SIZE
        } else 0
        createShop(name, CoinCurrency(), purchasePolicy = policy, stockSize = maxOf(stock.size + buffer, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    /** Reward shop paid in a tradeable ticket ITEM currency (coins-like) — earned from content and
     *  spent here directly (DECISIONS.md §8). Sell-only (BUY_NONE): tickets buy rewards, no sell-back. */
    private fun ticketShop(name: String, ticketKey: String, singular: String, plural: String, wares: List<Ware>) {
        val ticketId = resolveOrNull(ticketKey) ?: return
        val stock = wares.mapNotNull { w -> resolveOrNull(w.key)?.let { ShopItem(it, w.amount, w.sell) } }
        createShop(name, ItemCurrency(ticketId, singular, plural), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
        // Ticket-priced gear/cosmetics may never be NPC-converted to gp (alch, Trading Post, General
        // Store) — the 2026-09 arbitrage audit found this shelf was the only currency shop that never
        // registered, so its safety rested on prices alone. Supplies (guarded = false) stay vendorable.
        SpecialShopGuard.register(wares.filter { it.guarded }.mapNotNull { resolveOrNull(it.key) })
    }

    // ----------------------------------- vendor wiring -----------------------------------

    /** A vendor with a single shop: every option (Talk-to AND Trade) opens the store directly. */
    private fun singleVendor(npc: String, x: Int, z: Int, dir: Direction, shop: String) {
        spawnNpc(npc, x, z, 0, 0, dir)
        bindVendorOptions(npc) { openOrClosed(player, shop) }
    }

    /** A vendor with several stores: every option opens the TABBED storefront directly (the
     *  custom client draws the tab strip on the shop window — see ShopTabs). No dialogue hop. */
    private fun tabVendor(npc: String, x: Int, z: Int, dir: Direction, vararg tabs: ShopTabs.Tab) {
        spawnNpc(npc, x, z, 0, 0, dir)
        val store = tabs.toList()
        bindVendorOptions(npc) { ShopTabs.open(player, store, guard = hubGuard) }
    }

    /** Storefront guard, shared by every hub storefront (re-checked on each tab switch too). The
     *  Last Free City's market never closes; the hook stays so a future condition has one seam. */
    private val hubGuard: (Player) -> Boolean = { _ -> true }

    private fun openOrClosed(player: Player, shop: String) {
        if (hubGuard(player)) player.openShop(shop)
    }

    /** Zaff's Talk-to keeps the rune-altar lift; Trade opens the store directly. */
    private suspend fun QueueTask.zaffTalk(player: Player) {
        when (options(player, "View runes & staves", "Teleport me to the rune altar", "Nevermind", title = "Magic Store")) {
            1 -> openOrClosed(player, MAGIC_STORE)
            2 -> {
                chatNpc(player, "Mind the essence — craft any rune your Runecraft level allows.")
                player.moveTo(RUNE_ALTAR)
            }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    // ----------------------------------- stock: starter coin shops -----------------------------------

    /** Melee weapons, bronze -> adamant only. (Armour comes from the rank-gated apprentice
     *  armoury via [ApprenticeArmoury] — see warriorMenu.) */
    private val meleeWeaponStock = listOf("bronze", "iron", "steel", "black", "mithril", "adamant").flatMap { m ->
        listOf("dagger", "sword", "scimitar", "longsword", "mace", "battleaxe", "warhammer", "2h_sword")
            .map { Ware("item.${m}_$it", 10) }
    }

    /** Ranged gear — leather/studded/green d-hide, bows up to maple, arrows up to adamant. */
    private val rangedStock = listOf(
        Ware("item.leather_cowl", 10), Ware("item.leather_body", 10), Ware("item.leather_chaps", 10),
        Ware("item.leather_vambraces", 10), Ware("item.leather_gloves", 10), Ware("item.leather_boots", 10),
        Ware("item.hardleather_body", 10), Ware("item.coif", 10),
        Ware("item.studded_body", 10), Ware("item.studded_chaps", 10),
        Ware("item.green_dhide_body", 5), Ware("item.green_dhide_chaps", 5), Ware("item.green_dhide_vambraces", 5),
        Ware("item.shortbow", 10), Ware("item.longbow", 10),
        Ware("item.oak_shortbow", 10), Ware("item.oak_longbow", 10),
        Ware("item.willow_shortbow", 10), Ware("item.willow_longbow", 10),
        Ware("item.maple_shortbow", 10), Ware("item.maple_longbow", 10),
        Ware("item.bronze_arrow", 5000), Ware("item.iron_arrow", 5000), Ware("item.steel_arrow", 2500),
        // Top-of-category markup (store-audit spot-reprice): the BEST arrow sold carries an explicit
        // premium over cache cost (80 -> 120) so Fletching undercuts the shop; lower tiers stay at cache.
        Ware("item.mithril_arrow", 1000), Ware("item.adamant_arrow", 500, 120),
        Ware("item.bronze_dart", 1000), Ware("item.iron_dart", 1000), Ware("item.steel_dart", 1000),
    )

    /** Magic — elemental staves, runes up to DEATH (unlimited stock — the coin price is the
     *  gate; the death-rune premium still keeps Runecrafting cheaper), basic teleport tabs.
     *  UNLIMITED stock never decrements (see ItemCurrency.sellToPlayer), so runes can't run
     *  out; the flip side is the shop can't take them back (sell-back reports "out of space"),
     *  which is fine — runes flow one way, out of the shop. */
    private val magicStock = listOf(
        Ware("item.staff_of_air", 10), Ware("item.staff_of_water", 10),
        Ware("item.staff_of_earth", 10), Ware("item.staff_of_fire", 10),
        Ware("item.air_rune", UNLIMITED), Ware("item.water_rune", UNLIMITED), Ware("item.earth_rune", UNLIMITED),
        Ware("item.fire_rune", UNLIMITED), Ware("item.mind_rune", UNLIMITED), Ware("item.body_rune", UNLIMITED),
        Ware("item.chaos_rune", UNLIMITED), Ware("item.cosmic_rune", UNLIMITED), Ware("item.nature_rune", UNLIMITED),
        // Top-of-category markup (store-audit spot-reprice): the BEST combat rune sold carries an
        // explicit premium (180 -> 270) so Runecrafting undercuts the shop; lower runes stay at cache.
        Ware("item.law_rune", UNLIMITED), Ware("item.death_rune", UNLIMITED, 270),
        Ware("item.varrock_teleport", 100), Ware("item.lumbridge_teleport", 100),
        Ware("item.falador_teleport", 100), Ware("item.camelot_teleport", 100),
    )

    /** Fish stall (Gerrant's fishing shop) — cooked only up to swordfish; higher fish sold
     *  RAW (Cooking gate to use them). */
    private val fishStock = listOf(
        Ware("item.bread", 1000), Ware("item.cooked_chicken", 1000), Ware("item.cooked_meat", 1000),
        Ware("item.shrimps", 1000), Ware("item.anchovies", 1000), Ware("item.trout", 1000),
        // Top-of-category markup (store-audit spot-reprice): the BEST cooked food sold carries an
        // explicit premium (80 -> 160) so Fishing+Cooking undercut the shop; lobster stays the cheap staple.
        Ware("item.salmon", 1000), Ware("item.tuna", 1000), Ware("item.lobster", 1000), Ware("item.swordfish", 1000, 160),
        // Higher-tier food: raw only — needs Cooking to be usable.
        Ware("item.raw_monkfish", 1000), Ware("item.raw_shark", 1000), Ware("item.raw_sea_turtle", 500),
        Ware("item.raw_manta_ray", 500), Ware("item.raw_anglerfish", 500), Ware("item.raw_karambwan", 500),
    )

    // ----------------------------------- stock: farming & herblore -----------------------------------

    private val allotmentSeedStock = listOf(
        "potato_seed", "onion_seed", "cabbage_seed", "tomato_seed", "sweetcorn_seed", "strawberry_seed",
        "watermelon_seed", "marigold_seed", "rosemary_seed", "nasturtium_seed", "woad_seed", "limpwurt_seed",
        "barley_seed", "hammerstone_seed", "asgarnian_seed", "jute_seed", "yanillian_seed", "krandorian_seed",
        "wildblood_seed", "redberry_seed", "cadavaberry_seed", "dwellberry_seed", "jangerberry_seed",
        "whiteberry_seed", "poison_ivy_seed",
    ).map { Ware("item.$it", 100) }

    private val herbSeedStock = listOf(
        "guam_seed", "marrentill_seed", "tarromin_seed", "harralander_seed", "ranarr_seed", "toadflax_seed",
        "irit_seed", "avantoe_seed", "kwuarm_seed", "snapdragon_seed", "cadantine_seed", "lantadyme_seed",
        "dwarf_weed_seed", "torstol_seed",
    ).map { Ware("item.$it", 100) }

    private val treeSeedStock = listOf(
        "acorn", "willow_seed", "maple_seed", "yew_seed", "magic_seed", "apple_tree_seed", "banana_tree_seed",
        "orange_tree_seed", "curry_tree_seed", "pineapple_seed", "papaya_tree_seed", "palm_tree_seed",
        "calquat_tree_seed",
    ).map { Ware("item.$it", 100) }

    private val specialSeedStock = listOf(
        "spirit_seed", "mushroom_spore", "belladonna_seed", "cactus_seed",
    ).map { Ware("item.$it", 100) }

    /** Clean low herbs (the bare names; guam/ranarr/irit use leaf/weed suffixes in this cache). */
    private val herbStock = listOf(
        "guam_leaf", "marrentill", "tarromin", "harralander", "ranarr_weed", "toadflax", "irit_leaf",
        "avantoe", "kwuarm", "snapdragon", "cadantine", "lantadyme", "dwarf_weed", "torstol",
    ).map { Ware("item.$it", 100) }

    private val secondaryStock = listOf(
        Ware("item.vial_of_water", 1000), Ware("item.vial", 1000), Ware("item.pestle_and_mortar", 10),
        Ware("item.eye_of_newt", 1000), Ware("item.unicorn_horn_dust", 1000), Ware("item.limpwurt_root", 1000),
        Ware("item.red_spiders_eggs", 1000), Ware("item.white_berries", 1000), Ware("item.snape_grass", 1000),
        Ware("item.wine_of_zamorak", 1000), Ware("item.dragon_scale_dust", 1000), Ware("item.potato_cactus", 1000),
        Ware("item.jangerberries", 1000), Ware("item.crushed_nest", 1000), Ware("item.toads_legs", 1000),
        Ware("item.mort_myre_fungus", 1000),
    )

    /** Unfinished potions — the Herblore gate: players add the secondary themselves. */
    private val unfinishedStock = listOf(
        "guam_potion_unf", "marrentill_potion_unf", "tarromin_potion_unf", "harralander_potion_unf",
        "ranarr_potion_unf", "toadflax_potion_unf", "irit_potion_unf", "avantoe_potion_unf", "kwuarm_potion_unf",
        "snapdragon_potion_unf", "cadantine_potion_unf", "lantadyme_potion_unf", "dwarf_weed_potion_unf",
        "torstol_potion_unf",
    ).map { Ware("item.$it", 200) }

    private val farmToolStock = listOf(
        Ware("item.rake", 10), Ware("item.seed_dibber", 10), Ware("item.secateurs", 10),
        Ware("item.gardening_trowel", 10), Ware("item.watering_can", 10), Ware("item.spade", 10),
    )

    // ----------------------------------- stock: skilling supplies -----------------------------------

    private val skillToolStock = listOf(
        Ware("item.bronze_pickaxe", 10), Ware("item.iron_pickaxe", 10), Ware("item.steel_pickaxe", 10),
        Ware("item.mithril_pickaxe", 10), Ware("item.adamant_pickaxe", 5), Ware("item.rune_pickaxe", 3),
        Ware("item.bronze_axe", 10), Ware("item.iron_axe", 10), Ware("item.steel_axe", 10),
        Ware("item.mithril_axe", 10), Ware("item.adamant_axe", 5), Ware("item.rune_axe", 3),
        Ware("item.tinderbox", 20), Ware("item.chisel", 20), Ware("item.hammer", 20), Ware("item.knife", 20),
        Ware("item.needle", 20), Ware("item.shears", 20), Ware("item.glassblowing_pipe", 10),
        // Hunter traps — reusable tools (the skill never consumes them), so a one-time buy.
        Ware("item.bird_snare", 100, 25), Ware("item.box_trap", 100, 75),
    )

    /** Fishing supplies (Gerrant) — fishing gear + bait. */
    private val fishingSuppliesStock = listOf(
        Ware("item.small_fishing_net", 50), Ware("item.big_fishing_net", 20), Ware("item.fishing_rod", 50),
        Ware("item.fly_fishing_rod", 50), Ware("item.lobster_pot", 50), Ware("item.harpoon", 50),
        Ware("item.fishing_bait", 10000), Ware("item.feather", 10000),
    )

    /** Primary/low materials only — mid & high tiers must be gathered. */
    private val skillMaterialStock = listOf(
        Ware("item.copper_ore", 1000), Ware("item.tin_ore", 1000), Ware("item.iron_ore", 1000),
        Ware("item.clay", 1000), Ware("item.coal", 500), Ware("item.bronze_bar", 1000), Ware("item.iron_bar", 500),
        Ware("item.flax", 1000), Ware("item.bow_string", 1000), Ware("item.leather", 1000),
        Ware("item.hard_leather", 1000), Ware("item.thread", 1000), Ware("item.ball_of_wool", 1000),
        Ware("item.feather", 5000), Ware("item.logs", 1000), Ware("item.oak_logs", 1000), Ware("item.willow_logs", 500),
        Ware("item.molten_glass", 1000), Ware("item.soda_ash", 1000), Ware("item.bucket_of_sand", 1000),
    )

    // ----------------------------------- stock: prayer / crafting / construction -----------------------------------

    /** Bones for Prayer — STARTER only (bones + big bones). High-value bones (dragon/wyvern/
     *  babydragon) were removed (store-audit F-2): at cache cost they let a fresh account buy
     *  near-max Prayer XP on thin starter gp and skip the PvM gather gate. High bones now come
     *  from PvM drops + the Trading Post (player-supplied), not a flat vendor. */
    private val bonesStock = listOf(
        Ware("item.bones", 1000), Ware("item.big_bones", 1000),
    )

    /** Crafting & jewellery supplies — bars, uncut gems, moulds, leather/sewing basics. */
    private val craftingStock = listOf(
        Ware("item.silver_bar", 1000), Ware("item.gold_bar", 1000),
        Ware("item.uncut_sapphire", 500), Ware("item.uncut_emerald", 500),
        Ware("item.uncut_ruby", 300), Ware("item.uncut_diamond", 200),
        Ware("item.ring_mould", 5), Ware("item.necklace_mould", 5), Ware("item.amulet_mould", 5),
        Ware("item.bracelet_mould", 5),
        Ware("item.needle", 20), Ware("item.thread", 1000), Ware("item.chisel", 20),
        Ware("item.leather", 1000), Ware("item.soft_clay", 1000),
        // gold_leaf removed (store-audit F-3): a high-end Construction luxury good, not a starter
        // material — route it through the Trading Post (value-derived) instead of a flat vendor.
    )

    /** Construction supplies — planks, nails, cloth, saw and the finer materials. */
    private val constructionStock = listOf(
        Ware("item.plank", 1000), Ware("item.oak_plank", 1000), Ware("item.teak_plank", 500),
        Ware("item.mahogany_plank", 300),
        Ware("item.bronze_nails", 5000), Ware("item.iron_nails", 5000), Ware("item.steel_nails", 5000),
        Ware("item.bolt_of_cloth", 500), Ware("item.saw", 10), Ware("item.hammer", 10),
        Ware("item.soft_clay", 1000), Ware("item.limestone_brick", 500), Ware("item.marble_block", 100),
        // gold_leaf removed (store-audit F-3): luxury good — route via the Trading Post, not a flat vendor.
    )

    // ----------------------------------- stock: reward exchange (points) -----------------------------------
    // TODO: expand with proper UNTRADEABLE rewards (cosmetics, imbues, convenience) once those
    // items exist. Reward shops must never sell tradeable end-game gear (inflation trap).

    // Valaine IS the single Reward Exchange. Boss/Vote Tickets (a tradeable, stackable ITEM currency —
    // DECISIONS.md §8) buy COSMETICS (statless overrides in items/itemOverrides/CustomLaunch.yml — pure
    // flair, no power) + real supplies, directly (no redemption step). Prices below are in TICKETS. No
    // tradeable end-game gear (inflation trap). The ticket item IS the currency, so it's not stocked
    // here — you can't buy the currency with the currency.
    private val bossRewardStock = listOf(
        Ware("item.champions_cape", 1000, 75),   // cosmetic
        Ware("item.divine_halo", 1000, 150),     // cosmetic (premium)
        Ware("item.shark", 1000, 4, guarded = false),
        Ware("item.prayer_potion4", 1000, 8, guarded = false),
        Ware("item.super_combat_potion4", 1000, 20, guarded = false),
        Ware("item.saradomin_brew4", 1000, 12, guarded = false),
    )

    private val voteRewardStock = listOf(
        Ware("item.royal_partyhat", 1000, 40),   // cosmetic
        Ware("item.super_restore4", 1000, 6, guarded = false),
        Ware("item.ranging_potion4", 1000, 6, guarded = false),
        // Shop-economy-redesign §4: the vote shelf was 3 items thin — two QoL consumables.
        Ware("item.stamina_potion4", 1000, 6, guarded = false),
        Ware("item.divine_super_combat_potion4", 1000, 15, guarded = false),
        // The gilded line — rune-stat cosmetics, the vote loyalty chase (§4 asked for
        // cosmetic shelf items). Tickets are tradeable, so non-voters buy off voters;
        // a full set is months of daily votes.
        Ware("item.gilded_full_helm", 1000, 30),
        Ware("item.gilded_platebody", 1000, 60),
        Ware("item.gilded_platelegs", 1000, 50),
        Ware("item.gilded_kiteshield", 1000, 40),
        Ware("item.gilded_scimitar", 1000, 40),
        Ware("item.gilded_boots", 1000, 25),
    )

    // Declared last so every stock list above is initialised before this init runs.
    init {
        // ---- shop registration ----
        // Every coin shop is BUY_STOCK: with no player market at low population, the themed
        // stores are the buyers of last resort — each buys back what it stocks at the shared
        // ItemCurrency.BUY_RATE (70% of value). One-way stays reserved for the ticket/reward
        // shops below.
        coinShop(MELEE_WEAPONS, PurchasePolicy.BUY_STOCK, meleeWeaponStock)
        coinShop(RANGED_GEAR, PurchasePolicy.BUY_STOCK, rangedStock)
        coinShop(MAGIC_STORE, PurchasePolicy.BUY_STOCK, magicStock)
        coinShop(FISHING_SUPPLIES, PurchasePolicy.BUY_STOCK, fishingSuppliesStock)
        coinShop(FISH, PurchasePolicy.BUY_STOCK, fishStock)
        coinShop(ALLOTMENT_SEEDS, PurchasePolicy.BUY_STOCK, allotmentSeedStock)
        coinShop(HERB_SEEDS, PurchasePolicy.BUY_STOCK, herbSeedStock)
        coinShop(TREE_SEEDS, PurchasePolicy.BUY_STOCK, treeSeedStock)
        coinShop(SPECIAL_SEEDS, PurchasePolicy.BUY_STOCK, specialSeedStock)
        coinShop(HERBS, PurchasePolicy.BUY_STOCK, herbStock)
        coinShop(SECONDARIES, PurchasePolicy.BUY_STOCK, secondaryStock)
        coinShop(UNFINISHED_POTIONS, PurchasePolicy.BUY_STOCK, unfinishedStock)
        coinShop(FARM_TOOLS, PurchasePolicy.BUY_STOCK, farmToolStock)
        coinShop(SKILL_TOOLS, PurchasePolicy.BUY_STOCK, skillToolStock)
        coinShop(SKILL_MATERIALS, PurchasePolicy.BUY_STOCK, skillMaterialStock)
        coinShop(BONES, PurchasePolicy.BUY_STOCK, bonesStock)
        coinShop(CRAFTING, PurchasePolicy.BUY_STOCK, craftingStock)
        coinShop(CONSTRUCTION, PurchasePolicy.BUY_STOCK, constructionStock)
        ticketShop(BOSS_REWARDS, "item.boss_ticket", "Boss Ticket", "Boss Tickets", bossRewardStock)
        ticketShop(VOTE_REWARDS, "item.vote_ticket", "Vote Ticket", "Vote Tickets", voteRewardStock)
        // (The "Buy Vote Tickets" coin tab was removed 2026-09-02 at the operator's request:
        // vote tickets are earned by voting, not bought.)

        // One vendor row along the courtyard's north side (z=3228, all facing SOUTH at the
        // aisle), ordered combat first — Melee, Ranged, Magic — then Prayer and the skilling
        // shops. The last slot wraps onto the corner tile @3215,3227 (Valaine's old post —
        // verified walkable; 3215-3216 @ z=3228 are not part of the row).

        // ---- Combat shops ----
        tabVendor("npc.horvik", 3207, 3228, Direction.SOUTH,                         // weapons + rank armour
            ShopTabs.Tab("Weapons", MELEE_WEAPONS, icon = "item.rune_longsword"),
            ShopTabs.Tab("Rank armour", icon = "item.rune_platebody") { ApprenticeArmoury.open(it) })
        singleVendor("npc.lowe", 3208, 3228, Direction.SOUTH, RANGED_GEAR)
        // Zaff: Trade opens the store; Talk-to keeps the rune-altar lift dialogue.
        spawnNpc("npc.zaff", 3209, 3228, 0, 0, Direction.SOUTH)
        bindVendorTalkAndTrade("npc.zaff",
            talk = { player.queue { zaffTalk(player) } },
            trade = { openOrClosed(player, MAGIC_STORE) })

        // ---- Prayer + skilling shops ----
        singleVendor("npc.monk", 3210, 3228, Direction.SOUTH, BONES)                 // bones (Prayer)
        singleVendor("npc.gem_trader", 3211, 3228, Direction.SOUTH, CRAFTING)        // crafting/jewellery
        tabVendor("npc.wydin", 3212, 3228, Direction.SOUTH,                          // tools + materials
            ShopTabs.Tab("Tools", SKILL_TOOLS, icon = "item.rune_pickaxe"),
            ShopTabs.Tab("Materials", SKILL_MATERIALS, icon = "item.iron_ore"))
        tabVendor("npc.gerrant", 3213, 3228, Direction.SOUTH,                        // fishing + fish
            ShopTabs.Tab("Supplies", FISHING_SUPPLIES, icon = "item.fishing_rod"),
            ShopTabs.Tab("Fish", FISH, icon = "item.swordfish"))
        tabVendor("npc.jatix", 3214, 3228, Direction.SOUTH,                          // seeds + herblore
            ShopTabs.Tab("Allotment seeds", ALLOTMENT_SEEDS, icon = "item.potato_seed"),
            ShopTabs.Tab("Herb seeds", HERB_SEEDS, icon = "item.ranarr_seed"),
            ShopTabs.Tab("Tree seeds", TREE_SEEDS, icon = "item.acorn"),
            ShopTabs.Tab("Special seeds", SPECIAL_SEEDS, icon = "item.mushroom_spore"),
            ShopTabs.Tab("Herbs", HERBS, icon = "item.ranarr_weed"),
            ShopTabs.Tab("Secondaries", SECONDARIES, icon = "item.eye_of_newt"),
            ShopTabs.Tab("Unf. potions", UNFINISHED_POTIONS, icon = "item.ranarr_potion_unf"),
            ShopTabs.Tab("Farm tools", FARM_TOOLS, icon = "item.rake"))
        singleVendor("npc.sawmill_operator", 3215, 3227, Direction.SOUTH, CONSTRUCTION) // planks/nails
        // Valaine mans the GE hub's desk ring (east slot of the south face), not the shop rows.
        tabVendor("npc.valaine", 3222, 3209, Direction.SOUTH,                        // boss/vote tickets
            ShopTabs.Tab("Boss rewards", BOSS_REWARDS, icon = "item.boss_ticket"),
            ShopTabs.Tab("Vote rewards", VOTE_REWARDS, icon = "item.vote_ticket"))

        // End-game / war cluster mans the GE hub's desk ring (Quartermaster @ 3223,3211,
        // Slayer Master @ 3222,3212, PK Shop @ 3223,3210, Royal Smith @ 3221,3209, plus
        // Valaine above) and the Dice host @ 3224,3215 — spawned by their own plugins.

        // ---- store-type display names ----
        // The stock cache names (Zaff, Horvik, Gerrant, …) make the market hard to read — you
        // can't tell which vendor sells what. Override each courtyard vendor's client-facing name
        // to its STORE TYPE (e.g. "Magic Shop", "Fishing Shop") via extended-info, exactly like the
        // war NPCs ([WarNpcNames]) — no Displee cache edit. Bound on spawn (and re-applied on
        // respawn, since World re-runs npc-spawn hooks) so late-logging players see it too.
        // The Slayer Master and the war/reward cluster are spawned by other plugins, so renaming
        // here leaves them (Slayer especially) untouched.
        STORE_NAMES.forEach { (key, display) -> onNpcSpawn(key) { WarNpcNames.rename(npc, display) } }
    }

    private companion object {
        /** Infinite stock: the shop engine never decrements an item whose initial amount is
         *  Int.MAX_VALUE (see [org.alter.plugins.content.mechanics.shops.ItemCurrency.sellToPlayer]),
         *  the same convention [currencyBuyShop] uses. */
        const val UNLIMITED = Int.MAX_VALUE

        /** Where Zaff's "rune altar" lift drops the player — one tile east of the fire altar the
         *  Mire yard actually spawns (SwampHubPlugin @3238,3200), so the lift lands you AT the altar
         *  instead of the old placeholder tile up by the castle (which just nudged you a few tiles). */
        val RUNE_ALTAR = Tile(3239, 3200, 0)

        /** Vendor rscm key -> the store-type name shown to players (replaces the stock cache name).
         *  Keep the keys in sync with the spawns above. Slayer/Quartermaster/dice are NOT here —
         *  they belong to other plugins and keep their own names. */
        val STORE_NAMES = mapOf(
            "npc.horvik" to "Melee Shop",
            "npc.lowe" to "Ranged Shop",
            "npc.zaff" to "Magic Shop",
            "npc.monk" to "Prayer Shop",
            "npc.gem_trader" to "Crafting Shop",
            "npc.wydin" to "Skilling Shop",
            "npc.gerrant" to "Fishing Shop",
            "npc.jatix" to "Herblore Shop",
            "npc.sawmill_operator" to "Construction Shop",
            "npc.valaine" to "Rewards Shop",
        )

        const val MELEE_WEAPONS = "Lumbridge Melee Weapons"
        const val RANGED_GEAR = "Lumbridge Ranged Gear"
        const val MAGIC_STORE = "Lumbridge Magic Store"
        const val FISHING_SUPPLIES = "Lumbridge Fishing Supplies"
        const val FISH = "Lumbridge Fish Stall"
        const val ALLOTMENT_SEEDS = "Lumbridge Allotment & Flower Seeds"
        const val HERB_SEEDS = "Lumbridge Herb Seeds"
        const val TREE_SEEDS = "Lumbridge Tree & Fruit Seeds"
        const val SPECIAL_SEEDS = "Lumbridge Special Seeds"
        const val HERBS = "Lumbridge Herbs"
        const val SECONDARIES = "Lumbridge Herblore Secondaries"
        const val UNFINISHED_POTIONS = "Lumbridge Unfinished Potions"
        const val FARM_TOOLS = "Lumbridge Farming Tools"
        const val SKILL_TOOLS = "Lumbridge Skilling Tools"
        const val SKILL_MATERIALS = "Lumbridge Skilling Materials"
        const val BONES = "Lumbridge Bones & Prayer"
        const val CRAFTING = "Lumbridge Crafting Supplies"
        const val CONSTRUCTION = "Lumbridge Construction Supplies"
        const val BOSS_REWARDS = "Lumbridge Boss Rewards"
        const val VOTE_REWARDS = "Lumbridge Vote Rewards"
    }
}
