package org.alter.plugins.content.economy.tradingpost

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Trading Post** (Phase 5 marketplace — see the growth roadmap).
 *
 * A single Lumbridge marketplace that buys and sells (almost) any tradeable at value-derived
 * prices via [TradingPostCurrency], acting as the **NPC liquidity backstop** so a market works
 * even at low population. It reuses the shop engine's [PurchasePolicy.BUY_TRADEABLES] mode:
 * items players sell are added to the post's dynamic stock for others to buy back, while a
 * curated set of frequently-traded mats/consumables is always stocked (infinite) so there's
 * something to buy on day one. The buy/sell margin is the gp **sink** (see the currency).
 *
 * Forward-compatible: a player-to-player offer book can later match *inside* the spread and
 * fall through to this backstop, with no new venue for players to learn.
 */
class TradingPostPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /**
     * Always-stocked tradeables (infinite) so the post is useful to *buy* from at pop≈1 — the
     * commonly-traded mats/secondaries/runes/gems that have no dedicated vendor (deliberately
     * NOT end-game gear; that comes from PvM/Forge/skills). Player sales fill the rest.
     */
    private val seedKeys = listOf(
        // Smithing / Forge feedstock (runite_bar removed — store-audit F-7: it's the Forge's marquee
        // sink feedstock; seeding it at cache cost undercut the Forge. Fills from player sales instead.)
        "item.coal", "item.gold_bar", "item.mithril_bar", "item.adamantite_bar",
        // High runes (gated out of the Magic store)
        "item.nature_rune", "item.law_rune", "item.death_rune", "item.blood_rune",
        "item.cosmic_rune", "item.astral_rune",
        // Runecrafting essence
        "item.pure_essence", "item.rune_essence",
        // Crafting gems (uncut)
        "item.uncut_sapphire", "item.uncut_emerald", "item.uncut_ruby", "item.uncut_diamond",
        // Prayer (dragon_bones removed — store-audit F-7: it undercut the Prayer gate and created a
        // buy-cheap/sell-back loop with the Monk shop; fills from player sales instead.)
        "item.big_bones",
        // High herbs (Herblore)
        "item.ranarr_weed", "item.snapdragon", "item.torstol", "item.kwuarm",
        // High raw food (usable after Cooking)
        "item.raw_shark", "item.raw_manta_ray",
    )

    init {
        val seed = seedKeys.mapNotNull { resolveOrNull(it) }
        createShop(SHOP_NAME, TradingPostCurrency(), purchasePolicy = PurchasePolicy.BUY_TRADEABLES, stockSize = STOCK_SIZE) {
            seed.forEachIndexed { i, id -> items[i] = ShopItem(id, amount = Int.MAX_VALUE) } // infinite stock at market value
        }

        // Exchange Clerk not spawned in Lumbridge for now — the Trading Post will live at the
        // Grand Exchange instead. (Shop + command stay registered for when it's placed there.)
        // spawnNpc(CLERK, 3217, 3208, 0, 0, Direction.WEST)
        // bindClerk(CLERK)

        onCommand("market", description = "Open the Trading Post") { open(player) }
    }

    private fun open(player: Player) {
        player.message("<col=801700>Welcome to the Trading Post. Sell to me at market value (minus a small trade margin), or buy what others have sold.</col>")
        player.openShop(SHOP_NAME)
    }

    /** Bind whatever click option the clerk actually has (cache defs vary) to open the post. */
    private fun bindClerk(npc: String) {
        val acts = try {
            getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val opt = listOf("trade", "exchange", "talk-to", "view-offers").firstNotNullOfOrNull { want ->
            acts.firstOrNull { it.equals(want, true) }
        } ?: acts.firstOrNull()
        if (opt != null) {
            onNpcOption(npc, option = opt) { open(player) }
        } else {
            logger.warn { "trading-post: '$npc' has no click options; use ::market to reach it." }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val SHOP_NAME = "Trading Post"
        const val CLERK = "npc.grand_exchange_clerk"
        const val STOCK_SIZE = 40 // shop display cap; ~26 seeded, rest fills from player sales
    }
}
