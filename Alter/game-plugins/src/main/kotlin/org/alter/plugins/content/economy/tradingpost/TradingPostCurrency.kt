package org.alter.plugins.content.economy.tradingpost

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.Shop
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The **Trading Post** currency (Phase 5 marketplace — the NPC liquidity *backstop*).
 *
 * Prices are derived from each item's cache value ([getItem].cost — the same basis the
 * alch faucet uses), so the post is a real value-tracking market rather than a hand-priced
 * shop. The gap between what it sells for and what it pays is the **trade margin**, and that
 * margin is the gp **sink**: an item that flows player → post → player destroys
 * `(1 - BUY_RATE)` of its value in gp.
 *
 * The margin is tighter than the junk general store (which pays 60%), so the Trading Post is
 * the better place to move valuables — but still wide enough that, at low population, dumping
 * loot for gp isn't a runaway faucet. When a player-to-player offer book is added later it
 * simply matches *inside* this spread first, with the post as the fallback fill.
 *
 * Pays in gp (extends [ItemCurrency] over `item.coins_995`, like `CoinCurrency`, but with the
 * value-tracking price overrides).
 */
class TradingPostCurrency : ItemCurrency(getRSCM("item.coins_995"), singularCurrency = "coin", pluralCurrency = "coins") {

    /** Price to BUY one from the post (what the player pays): full market value. */
    override fun getSellPrice(world: World, item: Int): Int =
        maxOf(1, getItem(item).cost)

    /** Price the post PAYS the player when selling to it: market value minus the trade margin. */
    override fun getBuyPrice(world: World, item: Int): Int =
        (getItem(item).cost * BUY_RATE).toInt().coerceAtLeast(1)

    /**
     * Bonds must NEVER be NPC-sold for gold (bond spec §2.3): the tradeable bond's cache cost is
     * 2m (not overridable), so the post's 85% buy would mint 1.7m gp per $4.99 bond. Denied —
     * bonds change hands player-to-player only.
     */
    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        if (item.toUnnoted().id in neverBuy) {
            p.message("The Trading Post doesn't deal in bonds — trade them to other players instead.")
            return
        }
        super.buyFromPlayer(p, shop, slot, amt)
    }

    private val neverBuy: Set<Int> = listOf("item.bond", "item.bond_untradeable")
        .mapNotNull { key -> runCatching { getRSCM(key) }.getOrNull() }
        .toSet()

    private companion object {
        /** Post pays this fraction of value; the remaining 15% is the gp sink. */
        const val BUY_RATE = 0.85
    }
}
