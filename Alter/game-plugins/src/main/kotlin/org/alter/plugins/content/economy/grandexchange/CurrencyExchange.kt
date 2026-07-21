package org.alter.plugins.content.economy.grandexchange

import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The NPC **coin price** of each tradeable special currency. This is the single lever that sets the
 * coin **ceiling** on everything those vendors sell: anyone can buy the currency for coins and redeem
 * the gear, so no GE listing of that gear can exceed `(ticket cost of item) × (rate below)`.
 *
 * Raise these as the player base / economy grows (the GE economic plan's inflation lever). The rates
 * cross-check against the store ticket costs — e.g. a 2,500-ticket weapon caps at 2.5M gp, a
 * 12M-gp Barrows piece ≈ 12,000 tickets. See the economic plan for the full table.
 */
object GeCurrencyPrices {
    const val BOSS_TICKET = 1_000
    const val BLOOD_MONEY = 800
    const val VOTE_TICKET = 2_000
}

/**
 * Register a one-way **"buy &lt;currency&gt; for coins"** shop: infinite stock of the currency item at a
 * fixed coin price, [PurchasePolicy.BUY_NONE] so the NPC never buys it back. One-way on purpose — it's
 * a coin **sink** (coins in, currency minted then re-sunk when the gear is redeemed), never a route to
 * mint coins *from* currency. Add the returned shop to a vendor with a `ShopTabs.Tab`.
 */
fun KotlinPlugin.currencyBuyShop(shopName: String, currencyItemKey: String, coinPrice: Int) {
    val id = runCatching { getRSCM(currencyItemKey) }.getOrNull() ?: return
    createShop(shopName, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = 1) {
        items[0] = ShopItem(id, amount = Int.MAX_VALUE, sellPrice = coinPrice)
    }
}
