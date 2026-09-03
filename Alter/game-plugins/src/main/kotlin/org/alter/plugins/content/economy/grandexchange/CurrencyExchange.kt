package org.alter.plugins.content.economy.grandexchange

import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The **assumed** gp value of each tradeable special currency, kept for documentation and for the
 * economy auditor's soft pegs (`-PeconPegs`). No NPC sells these any more: the "buy currency for
 * coins" tabs were removed on 2026-09-02 (blood money is earned from kills, vote tickets from
 * voting), and Boss Tickets were retired outright in 2026-09 (design doc 04 §13). The currencies'
 * real gp value is whatever players pay on the Grand Exchange.
 */
object GeCurrencyPrices {
    const val BLOOD_MONEY = 800
    const val VOTE_TICKET = 2_000
}

/**
 * Register a one-way **"buy &lt;currency&gt; for coins"** shop: infinite stock of the currency item at a
 * fixed coin price, [PurchasePolicy.BUY_NONE] so the NPC never buys it back. Unused since the
 * 2026-09-02 tab removals; kept as the seam for any future coin-priced token.
 */
fun KotlinPlugin.currencyBuyShop(shopName: String, currencyItemKey: String, coinPrice: Int) {
    val id = runCatching { getRSCM(currencyItemKey) }.getOrNull() ?: return
    createShop(shopName, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = 1) {
        items[0] = ShopItem(id, amount = Int.MAX_VALUE, sellPrice = coinPrice)
    }
}
