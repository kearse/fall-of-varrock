package org.alter.plugins.content.economy.grandexchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the GE price band ([GrandExchangePricing]) — the rail that stopped an offer being
 * posted at 1 gp for an item with a real value, which the NPC backstop would then fill.
 *
 * No cache/world/player: the policy is pure arithmetic by design, so it can be pinned down here.
 */
class GrandExchangePricingTest {

    @Test
    fun `a 1gp offer is refused for an item with a real value`() {
        // The reported exploit: iron bar is worth 28, so 1 gp must not be acceptable.
        assertFalse(GrandExchangePricing.permits(value = 28, price = 1))
    }

    @Test
    fun `the band brackets the value an order of magnitude either side`() {
        assertEquals(100 to 10_000, GrandExchangePricing.bounds(1_000))
        assertTrue(GrandExchangePricing.permits(value = 1_000, price = 100))
        assertTrue(GrandExchangePricing.permits(value = 1_000, price = 10_000))
        assertFalse(GrandExchangePricing.permits(value = 1_000, price = 99))
        assertFalse(GrandExchangePricing.permits(value = 1_000, price = 10_001))
    }

    @Test
    fun `real price discovery still has room`() {
        // A player halving or tripling the guide is ordinary market behaviour and must stay legal.
        assertTrue(GrandExchangePricing.permits(value = 1_000, price = 500))
        assertTrue(GrandExchangePricing.permits(value = 1_000, price = 3_000))
    }

    @Test
    fun `a low value item stays tradeable rather than becoming unlistable`() {
        // A tenth of 5 rounds to 0; the floor must clamp to 1 or the item couldn't be listed at all.
        assertEquals(1, GrandExchangePricing.minPrice(5))
        assertTrue(GrandExchangePricing.permits(value = 5, price = 1))
        assertTrue(GrandExchangePricing.permits(value = 1, price = 1))
    }

    @Test
    fun `a valuable item does not overflow its ceiling`() {
        // value * 10 does not fit an Int here; it must saturate, not wrap negative.
        assertEquals(Int.MAX_VALUE, GrandExchangePricing.maxPrice(Int.MAX_VALUE))
        assertTrue(GrandExchangePricing.maxPrice(500_000_000) > 0)
        assertTrue(GrandExchangePricing.permits(value = 500_000_000, price = Int.MAX_VALUE))
    }

    @Test
    fun `an unvalued item is unbanded but still rejects a non-positive price`() {
        // No credible value => nothing to band against. Such an item is never NPC-backstopped, so a
        // silly price can only be a consenting player-to-player trade, which mints nothing.
        assertTrue(GrandExchangePricing.permits(value = 0, price = 1))
        assertTrue(GrandExchangePricing.permits(value = 0, price = 1_000_000))
        assertFalse(GrandExchangePricing.permits(value = 0, price = 0))
        assertFalse(GrandExchangePricing.permits(value = 28, price = 0))
        assertFalse(GrandExchangePricing.permits(value = 28, price = -5))
    }
}
