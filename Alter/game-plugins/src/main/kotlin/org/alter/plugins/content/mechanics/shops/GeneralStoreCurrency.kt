package org.alter.plugins.content.mechanics.shops

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.Shop
import org.alter.rscm.RSCM.getRSCM

/**
 * Lumbridge General Store currency — coins, plus an anti-faucet BUY-BACK DENY-LIST (store-audit F-5).
 *
 * A `BUY_TRADEABLES` shop buys ANY tradeable at 60% cache cost, so an NPC-dropped item whose cache
 * `cost` far exceeds its true drop value would be a runaway gp faucet (kill mob -> vendor the drop ->
 * gp). Refuse buy-back on anything whose cache cost exceeds [MAX_BUYBACK_COST]; those route to the
 * value-derived Trading Post (::market) instead. Selling FROM the shop TO the player is unchanged, and
 * low-value junk still sells here as intended (the general store stays the low-end junk sink).
 */
class GeneralStoreCurrency : ItemCurrency(getRSCM("item.coins_995"), "coin", "coins") {

    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        if (getItem(item.toUnnoted().id).cost > MAX_BUYBACK_COST) {
            p.message("The general store won't buy that — try the Trading Post (::market).")
            return
        }
        super.buyFromPlayer(p, shop, slot, amt)
    }

    private companion object {
        /** Above this cache cost an item is "valuable" — route it to the Trading Post, not here. */
        const val MAX_BUYBACK_COST = 5000
    }
}
