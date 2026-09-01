package org.alter.plugins.content.economy.tradingpost

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.Shop
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The **Trading Post** currency (Phase 5 marketplace — the NPC liquidity *backstop*).
 *
 * Prices are derived from each item's cache value ([getItem].cost — the same basis the
 * alch faucet uses), so the post is a real value-tracking market rather than a hand-priced
 * shop. It sells at full value and pays the shared [ItemCurrency.BUY_RATE] (70%) like every
 * other coin shop — the 30% spread is the gp **sink**. Deliberately NOT better than the
 * shops: the post is the accepts-anything fallback, and the place to beat the margin is the
 * Grand Exchange, where players sell to each other inside the 70%–100% band.
 *
 * Pays in gp (extends [ItemCurrency] over `item.coins_995`, like `CoinCurrency`; the base
 * class already prices from cache value).
 */
class TradingPostCurrency : ItemCurrency(getRSCM("item.coins_995"), singularCurrency = "coin", pluralCurrency = "coins") {

    /**
     * Bonds must NEVER be NPC-sold for gold (bond spec §2.3): the tradeable bond's cache cost is
     * 2m (not overridable), so the post's 70% buy would mint 1.4m gp per $4.99 bond. Denied —
     * bonds change hands player-to-player only.
     */
    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        if (item.toUnnoted().id in neverBuy) {
            p.message("The Trading Post doesn't deal in bonds — trade them to other players instead.")
            return
        }
        // Special-currency shop wares (Boss Ticket gear etc.) may never be NPC-sold for gp —
        // the 70% buy-back was one half of the ticket-shop→gp infinite loop the audit found.
        if (SpecialShopGuard.isGuarded(item.toUnnoted().id)) {
            p.message("The Trading Post doesn't buy special-stock gear — sell it to other players instead.")
            return
        }
        super.buyFromPlayer(p, shop, slot, amt)
    }

    private val neverBuy: Set<Int> = listOf("item.bond", "item.bond_untradeable")
        .mapNotNull { key -> runCatching { getRSCM(key) }.getOrNull() }
        .toSet()
}
