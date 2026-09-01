package org.alter.plugins.content.economy

/**
 * Items sold for a NON-GP currency (Boss Tickets, prestige points, ...) that must never be
 * convertible back into gp through an NPC (alchemy, Trading Post buy-back). Tickets are
 * coin-buyable at a fixed rate, so any ticket-priced ware whose alch/TP payout exceeds its
 * ticket cost in coins is an infinite gold loop — the audit found +2.4m/loop on a Justiciar
 * chestguard alone. Shops selling for special currencies register their full catalogue here
 * at boot; AlchemyPlugin and TradingPostCurrency consult it.
 *
 * Player-to-player paths (trade, GE listing) stay open — only NPC gp faucets are denied.
 */
object SpecialShopGuard {
    private val guarded = HashSet<Int>()

    /** Register [ids] as special-currency wares (called from shop plugins at boot). */
    fun register(ids: Collection<Int>) {
        guarded.addAll(ids)
    }

    /** True when [id] was sold by a special-currency shop and may not be NPC-converted to gp. */
    fun isGuarded(id: Int): Boolean = id in guarded
}
