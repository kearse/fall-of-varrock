package org.alter.plugins.content.economy.grandexchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure GE matching rule ([GrandExchangeMatching.cross]) and the offer
 * accounting helpers. No cache/world/player — this is the dupe-safety-critical core in isolation.
 */
class GrandExchangeMatchingTest {

    private var seq = 0L
    private fun buy(item: Int, price: Int, qty: Int, filled: Int = 0, seqAt: Long = seq++) =
        GeOffer(box = 0, buy = true, itemId = item, price = price, qty = qty, filled = filled,
            escrowCoins = (qty - filled) * price, owner = "buyer", seq = seqAt)
    private fun sell(item: Int, price: Int, qty: Int, filled: Int = 0, seqAt: Long = seq++) =
        GeOffer(box = 0, buy = false, itemId = item, price = price, qty = qty, filled = filled,
            escrowItems = qty - filled, owner = "seller", seq = seqAt)

    @Test
    fun `no cross when the spread does not overlap`() {
        assertNull(GrandExchangeMatching.cross(buy("nats".hashCode(), price = 90, qty = 10), sell("nats".hashCode(), price = 100, qty = 10)))
    }

    @Test
    fun `no cross for different items`() {
        assertNull(GrandExchangeMatching.cross(buy(1, price = 100, qty = 5), sell(2, price = 100, qty = 5)))
    }

    @Test
    fun `resting buyer sets the price - taker seller gets price improvement`() {
        val b = buy(item = 555, price = 120, qty = 10, seqAt = 1) // older
        val s = sell(item = 555, price = 100, qty = 4, seqAt = 2) // newer taker
        val fill = GrandExchangeMatching.cross(b, s)
        assertEquals(GeFill(qty = 4, price = 120), fill) // trades at the resting buyer's 120
    }

    @Test
    fun `resting seller sets the price - taker buyer gets price improvement`() {
        val s = sell(item = 555, price = 100, qty = 10, seqAt = 1) // older
        val b = buy(item = 555, price = 120, qty = 4, seqAt = 2)  // newer taker
        val fill = GrandExchangeMatching.cross(b, s)
        assertEquals(GeFill(qty = 4, price = 100), fill) // trades at the resting seller's 100
    }

    @Test
    fun `quantity is the smaller remaining side`() {
        val b = buy(item = 7, price = 50, qty = 10, filled = 7) // 3 remaining
        val s = sell(item = 7, price = 50, qty = 100)            // 100 remaining
        assertEquals(3, GrandExchangeMatching.cross(b, s)?.qty)
    }

    @Test
    fun `exact price match crosses`() {
        assertEquals(GeFill(5, 100), GrandExchangeMatching.cross(buy(9, 100, 5, seqAt = 1), sell(9, 100, 5, seqAt = 2)))
    }

    @Test
    fun `state refresh reflects fill progress`() {
        val b = buy(3, 100, 10)
        b.filled = 4; b.refreshState(); assertEquals(GeState.BUYING, b.state)
        b.filled = 10; b.refreshState(); assertEquals(GeState.BOUGHT, b.state)
        val s = sell(3, 100, 10)
        s.filled = 2; s.refreshState(); assertEquals(GeState.SELLING, s.state)
        s.filled = 10; s.refreshState(); assertEquals(GeState.SOLD, s.state)
    }

    @Test
    fun `document round-trip preserves the offer`() {
        val o = buy(444, price = 321, qty = 9, filled = 3)
        o.collectItems = 3; o.collectCoins = 12; o.refreshState()
        val back = GeOffer.fromDocument(o.toDocument())
        assertEquals(o.itemId, back.itemId)
        assertEquals(o.price, back.price)
        assertEquals(o.filled, back.filled)
        assertEquals(o.escrowCoins, back.escrowCoins)
        assertEquals(o.collectItems, back.collectItems)
        assertEquals(o.state, back.state)
        assertEquals(o.seq, back.seq)
    }
}
