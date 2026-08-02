package org.alter.game.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless tests for the tick-accurate hit pipeline semantics that Pawn.applyHit is
 * built on: the delay countdown, application-time damage transforms with the HP
 * clamp, cancellation, and action ordering. Hit is constructible via [Hit.Builder]
 * without a world, so these pin the PvP-critical timing rules (tick-eating, hit
 * stacking, mid-flight prayer switches) at the unit level.
 */
class HitPipelineTests {

    private fun hit(damage: Int, delay: Int): Hit =
        Hit.Builder()
            .addHit(damage = damage, type = 16, attackerSource = -1)
            .setHitbarMaxPercentage(30)
            .setDamageDelay(delay)
            .build()

    @Test
    fun `a hit with delay N applies on the Nth cycle`() {
        val h = hit(damage = 10, delay = 3)
        // three cycles of countdown...
        assertFalse(h.tickCountdown())
        assertFalse(h.tickCountdown())
        assertFalse(h.tickCountdown())
        // ...and the fourth processing applies it.
        assertTrue(h.tickCountdown())
    }

    @Test
    fun `a delay-zero hit applies on its first processing`() {
        assertTrue(hit(damage = 10, delay = 0).tickCountdown())
    }

    @Test
    fun `damage transforms chain in order and write back`() {
        val h = hit(damage = 50, delay = 0)
        // protection-prayer style 40% reduction, then a +5 flat effect
        h.addDamageTransform { Math.floor(it * 0.6).toInt() }
        h.addDamageTransform { it + 5 }
        val result = h.transformedDamage(h.hitmarks[0], currentHp = 99)
        assertEquals(35, result)
        assertEquals(35, h.hitmarks[0].damage)
    }

    @Test
    fun `transformed damage is clamped to remaining hp`() {
        val h = hit(damage = 50, delay = 0)
        val result = h.transformedDamage(h.hitmarks[0], currentHp = 12)
        assertEquals(12, result)
    }

    @Test
    fun `a transform to zero deals no damage`() {
        val h = hit(damage = 30, delay = 0)
        h.addDamageTransform { 0 } // NPC-attacker protection prayer: full block
        assertEquals(0, h.transformedDamage(h.hitmarks[0], currentHp = 99))
    }

    @Test
    fun `cancellation follows the live condition`() {
        val h = hit(damage = 10, delay = 0)
        var dead = false
        h.setCancelIf { dead }
        assertFalse(h.isCancelled())
        dead = true
        assertTrue(h.isCancelled())
    }

    @Test
    fun `actions run in registration order`() {
        val h = hit(damage = 10, delay = 0)
        val order = mutableListOf<Int>()
        h.addAction { order.add(1) }
        h.addAction { order.add(2) }
        h.addAction { order.add(3) }
        h.runActions()
        assertEquals(listOf(1, 2, 3), order)
    }
}
