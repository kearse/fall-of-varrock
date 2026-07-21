package org.alter.plugins.content.economy.grandexchange

import org.bson.Document

/**
 * One Grand Exchange offer occupying a player's board slot (`box` 0..7).
 *
 * **Dupe-safety model (the important part).** Value only ever *moves* — it is never created or
 * destroyed by a match:
 *  - A **buy** offer escrows `qty × price` coins up front. As it fills at a trade price `p ≤ price`,
 *    `p` per item leaves [escrowCoins] and becomes bought items in [collectItems]; the `(price − p)`
 *    overpay per item is refunded into [collectCoins]. Whatever coins never trade stay in
 *    [escrowCoins] and are refunded on collect/cancel.
 *  - A **sell** offer escrows `qty` items up front ([escrowItems]). As it fills, one item leaves
 *    [escrowItems] and `p` coins enter [collectCoins]. Unsold items stay in [escrowItems], returned
 *    on collect/cancel.
 *
 * So at every instant `escrow + collectable` for a slot equals exactly what the player put in plus
 * what the market paid them — the matcher asserts this invariant (see [GrandExchange]).
 *
 * The [wire] codes are the exact ordinals of the client's `net.runelite.api.GrandExchangeOfferState`
 * (EMPTY 0, CANCELLED_BUY 1, CANCELLED_SELL 2, BUYING 3, BOUGHT 4, SELLING 5, SOLD 6), so the
 * outgoing slot-update maps 1:1 with the client's enum ([GrandExchangeWindow] streams `state.wire`).
 */
enum class GeState(val wire: Int) {
    EMPTY(0), CANCELLED_BUY(1), CANCELLED_SELL(2), BUYING(3), BOUGHT(4), SELLING(5), SOLD(6),
}

class GeOffer(
    var box: Int,
    var buy: Boolean,
    var itemId: Int,
    /** Price per item the player set. Buyers pay at most this; sellers accept at least this. */
    var price: Int,
    /** Total quantity the player wants to trade. */
    var qty: Int,
    /** Quantity transacted so far (0..qty). */
    var filled: Int = 0,
    /** Buy: coins still escrowed to spend. Sell: unused (0). */
    var escrowCoins: Int = 0,
    /** Sell: items still escrowed to sell. Buy: unused (0). */
    var escrowItems: Int = 0,
    /** Proceeds waiting in the collection box: coins (sell earnings, buy overpay/refunds). */
    var collectCoins: Int = 0,
    /** Proceeds waiting in the collection box: items (bought, or unsold returns on cancel). */
    var collectItems: Int = 0,
    var state: GeState = GeState.EMPTY,
    /** Stable owner key (login name, lower-cased) — offers outlive a session. */
    val owner: String,
    /** Monotonic creation sequence — the tie-breaker for price-**time** priority. */
    val seq: Long,
) {
    /** Units still open to trade. */
    val remaining: Int get() = qty - filled

    val isActive: Boolean get() = state == GeState.BUYING || state == GeState.SELLING

    /** True once nothing is left in escrow or the collection box — the slot can go [GeState.EMPTY]. */
    val fullyDrained: Boolean get() = escrowCoins == 0 && escrowItems == 0 && collectCoins == 0 && collectItems == 0

    /** Recompute the visible state after a fill (does not touch escrow/collect). */
    fun refreshState() {
        state = when {
            buy && filled >= qty -> GeState.BOUGHT
            !buy && filled >= qty -> GeState.SOLD
            buy -> GeState.BUYING
            else -> GeState.SELLING
        }
    }

    fun toDocument(): Document = Document()
        .append("box", box).append("buy", buy).append("item", itemId)
        .append("price", price).append("qty", qty).append("filled", filled)
        .append("escrowCoins", escrowCoins).append("escrowItems", escrowItems)
        .append("collectCoins", collectCoins).append("collectItems", collectItems)
        .append("state", state.name).append("owner", owner).append("seq", seq)

    companion object {
        fun fromDocument(d: Document): GeOffer = GeOffer(
            box = d.getInteger("box", 0),
            buy = d.getBoolean("buy", true),
            itemId = d.getInteger("item", -1),
            price = d.getInteger("price", 0),
            qty = d.getInteger("qty", 0),
            filled = d.getInteger("filled", 0),
            escrowCoins = d.getInteger("escrowCoins", 0),
            escrowItems = d.getInteger("escrowItems", 0),
            collectCoins = d.getInteger("collectCoins", 0),
            collectItems = d.getInteger("collectItems", 0),
            state = runCatching { GeState.valueOf(d.getString("state")) }.getOrDefault(GeState.EMPTY),
            owner = d.getString("owner") ?: "",
            seq = (d.get("seq") as? Number)?.toLong() ?: 0L,
        )
    }
}

/** A single agreed trade between two crossing offers (or an offer and the NPC backstop). */
data class GeFill(val qty: Int, val price: Int)

/**
 * The **pure** matching rule — no player, world, or IO. Kept standalone so it's unit-testable in
 * isolation (see `GrandExchangeMatchingTest`), because this is the dupe-safety-critical core.
 */
object GrandExchangeMatching {

    /**
     * The trade that clears between a [buy] and a [sell] offer for the same item, or null if they
     * don't cross. Price is the **resting** (older, lower [GeOffer.seq]) offer's price — the maker
     * gets their posted price; the taker gets price improvement. Quantity is the smaller remaining.
     */
    fun cross(buy: GeOffer, sell: GeOffer): GeFill? {
        require(buy.buy && !sell.buy) { "cross() expects one buy and one sell" }
        if (buy.itemId != sell.itemId) return null
        if (buy.remaining <= 0 || sell.remaining <= 0) return null
        if (buy.price < sell.price) return null // no overlap in the spread
        val price = if (buy.seq <= sell.seq) buy.price else sell.price
        val qty = minOf(buy.remaining, sell.remaining)
        return GeFill(qty, price)
    }
}
