package org.alter.plugins.content.economy.grandexchange

/**
 * The **price policy** for the Grand Exchange — the band an offer's price has to sit inside, expressed
 * against the item's economy value.
 *
 * Why this exists: the book used to accept any price above zero, so an offer could be posted at 1 gp for
 * anything. That is harmless between two consenting players (a match only *moves* value), but it is not
 * harmless next to the NPC commodity backstop, which fills any buy whose price reaches the item's
 * ceiling. When an item had no credible cache value the old ceiling collapsed to 1 gp
 * (`maxOf(1, cost)`), and a 1 gp buy then cleared instantly against the NPC — buying the item for a
 * single coin. The band below stops the offer being created in the first place, and
 * [GrandExchange.economyValue] no longer invents a value for an item that hasn't got one.
 *
 * **Deliberately wide.** This is a sanity rail, not a peg: the point is to catch a fat-finger and a 1 gp
 * exploit while leaving real player price discovery alone. A tenth of value to ten times value is far
 * looser than any genuine market swing, so a player can still speculate, undercut and hold out for a
 * premium — they just can't post a price with no relationship to the item at all. Note the commodity
 * allowlist is separately pinned to a much tighter 85–100% store band by the backstop
 * ([GrandExchange.band]); this band is the outer limit that applies to *everything* with a value.
 *
 * Pure arithmetic — no cache, no world, no player — so it is unit-testable in isolation like
 * [GrandExchangeMatching]. Resolving an item's value is [GrandExchange]'s job, since that needs the cache.
 */
object GrandExchangePricing {

    /** The band is value/[FACTOR] .. value×[FACTOR]. Integer arithmetic throughout — a rail this wide
     *  gains nothing from floating point, and `value * 0.1` is not exactly a tenth. */
    const val FACTOR = 10

    /**
     * Lowest price the book accepts for an item worth [value]. Always at least 1, so a very low-value
     * item (value 1..9, where a tenth truncates to nothing) stays tradeable rather than becoming
     * un-listable.
     */
    fun minPrice(value: Int): Int {
        if (value <= 0) return 1
        return (value / FACTOR).coerceAtLeast(1)
    }

    /**
     * Highest price the book accepts for an item worth [value], saturating at [Int.MAX_VALUE] rather
     * than overflowing — `value * FACTOR` does not fit an Int for a valuable item.
     */
    fun maxPrice(value: Int): Int {
        if (value <= 0) return Int.MAX_VALUE
        return (value.toLong() * FACTOR).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The `(min, max)` band for an item worth [value]. */
    fun bounds(value: Int): Pair<Int, Int> = minPrice(value) to maxPrice(value)

    /**
     * True if [price] is acceptable for an item worth [value]. A non-positive [value] means the item has
     * no credible economy value to band against, so any positive price passes — such an item is never
     * NPC-backstopped (see [GrandExchange.economyValue]), so a silly price can only ever be a trade
     * between two consenting players and cannot mint anything.
     */
    fun permits(value: Int, price: Int): Boolean {
        if (price <= 0) return false
        if (value <= 0) return true
        return price >= minPrice(value) && price <= maxPrice(value)
    }
}
