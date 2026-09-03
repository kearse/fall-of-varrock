package org.alter.plugins.content.economy.tradingpost

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.Shop
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.economy.grandexchange.GrandExchangeCommodities
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The **Trading Post** currency (Phase 5 marketplace — the NPC liquidity *backstop*).
 *
 * Prices are derived from each item's cache value ([getItem].cost — the same basis the
 * alch faucet uses), so the post is a real value-tracking market rather than a hand-priced
 * shop. It sells at full value and pays the shared [ItemCurrency.BUY_RATE] (70%) like every
 * other coin shop — the 30% spread is the gp **sink**. Deliberately NOT better than the
 * shops, and since the 2026-09 arbitrage audit it BUYS only the GE commodity allowlist
 * ([GrandExchangeCommodities]): the place to sell gear and crafted goods is the Grand
 * Exchange, where players sell to each other inside the 70%–100% band.
 *
 * Pays in gp (extends [ItemCurrency] over `item.coins_995`, like `CoinCurrency`; the base
 * class already prices from cache value).
 */
class TradingPostCurrency : ItemCurrency(getRSCM("item.coins_995"), singularCurrency = "coin", pluralCurrency = "coins") {

    /**
     * Bonds must NEVER be NPC-sold for gold (bond spec §2.3): the tradeable bond's cache cost is
     * 2m (not overridable), so the post's 70% buy would mint 1.4m gp per $4.99 bond. Denied —
     * bonds change hands player-to-player only. Special-currency shop wares (Boss Ticket gear
     * etc.) may never be NPC-sold for gp either — the 70% buy-back was one half of the
     * ticket-shop→gp infinite loop the audit found. Both rules live in [refusalReason] so the
     * offline economy auditor reads exactly what the live shop enforces.
     */
    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        when (refusalReason(item.toUnnoted().id)) {
            REFUSE_BOND -> {
                p.message("The Trading Post doesn't deal in bonds — trade them to other players instead.")
                return
            }
            REFUSE_GUARDED -> {
                p.message("The Trading Post doesn't buy special-stock gear — sell it to other players instead.")
                return
            }
            REFUSE_NOT_COMMODITY -> {
                p.message("The Trading Post only buys everyday commodities — list gear and crafted goods on the Grand Exchange for other players.")
                return
            }
        }
        super.buyFromPlayer(p, shop, slot, amt)
    }

    companion object {
        const val REFUSE_BOND = "TradingPost.bond"
        const val REFUSE_GUARDED = "SpecialShopGuard"
        const val REFUSE_NOT_COMMODITY = "TradingPost.notCommodity"

        private val neverBuy: Set<Int> by lazy {
            listOf("item.bond", "item.bond_untradeable")
                .mapNotNull { key -> runCatching { getRSCM(key) }.getOrNull() }
                .toSet()
        }

        /**
         * Why the post refuses to buy the (unnoted) item id, or null when it will buy it. Since the
         * 2026-09 arbitrage audit the post buys ONLY the GE commodity allowlist: an uncapped "buy
         * anything at 70% of cache value" counter was the sink that turned every mid/high smithing and
         * fletching recipe into a coin printer (adamant platebody: 3,200 gp of NPC bars → 11,648 gp).
         * The post is now the GE floor in shop form; gear and crafted goods float between players.
         */
        fun refusalReason(unnotedId: Int): String? = when {
            unnotedId in neverBuy -> REFUSE_BOND
            SpecialShopGuard.isGuarded(unnotedId) -> REFUSE_GUARDED
            !GrandExchangeCommodities.isCommodity(unnotedId) -> REFUSE_NOT_COMMODITY
            else -> null
        }
    }
}
