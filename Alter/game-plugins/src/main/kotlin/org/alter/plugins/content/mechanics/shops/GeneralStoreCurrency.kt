package org.alter.plugins.content.mechanics.shops

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.Shop
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.rscm.RSCM.getRSCM

/**
 * Lumbridge General Store currency — coins, plus an anti-faucet BUY-BACK DENY-LIST (store-audit F-5).
 *
 * A `BUY_TRADEABLES` shop buys ANY tradeable at 70% cache cost, so an NPC-dropped item whose cache
 * `cost` far exceeds its true drop value would be a runaway gp faucet (kill mob -> vendor the drop ->
 * gp). Refuse buy-back on anything whose cache cost exceeds [MAX_BUYBACK_COST]; those route to the
 * value-derived Trading Post (::market) instead. Selling FROM the shop TO the player is unchanged, and
 * low-value junk still sells here as intended (the general store stays the low-end junk sink).
 *
 * Special-currency wares ([SpecialShopGuard]) are refused too: the 2026-09 arbitrage audit found the
 * cost cap was the ONLY thing standing between a 1,200-ticket Justiciar chestguard and a 4.2M gp
 * buy-back here (the store pays 70% where alch pays 60%), and the cap is a number that will move.
 */
class GeneralStoreCurrency : ItemCurrency(getRSCM("item.coins_995"), "coin", "coins") {

    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        when (refusalReason(item.toUnnoted().id)) {
            REFUSE_GUARDED -> {
                p.message("The general store doesn't buy special-stock gear — sell it to other players instead.")
                return
            }
            REFUSE_COST -> {
                p.message("The general store only buys odds and ends — sell that to other players on the Grand Exchange.")
                return
            }
        }
        super.buyFromPlayer(p, shop, slot, amt)
    }

    companion object {
        /** Above this cache cost an item is "valuable" — it belongs on the player market (GE), not
         *  here. Was 5,000; the 2026-09 arbitrage audit found that cap let the store buy back every
         *  mid-tier crafted output (steel/mithril armour, yew bows, cut gems, finished potions) for
         *  more than its NPC-sold inputs cost. 500 keeps the junk sink a junk sink. */
        const val MAX_BUYBACK_COST = 500
        const val REFUSE_GUARDED = "SpecialShopGuard"
        val REFUSE_COST = "GeneralStore.cost>$MAX_BUYBACK_COST"

        /** Why the store refuses to buy the (unnoted) item id, or null when it will buy it — one
         *  predicate shared by the live shop path and the offline economy auditor. */
        fun refusalReason(unnotedId: Int): String? = when {
            SpecialShopGuard.isGuarded(unnotedId) -> REFUSE_GUARDED
            getItem(unnotedId).cost > MAX_BUYBACK_COST -> REFUSE_COST
            else -> null
        }

        fun accepts(unnotedId: Int): Boolean = refusalReason(unnotedId) == null
    }
}
