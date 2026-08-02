package org.alter.plugins.content.combat

import org.alter.game.model.Tile
import org.alter.plugins.content.combat.formula.CombatMath
import org.alter.plugins.content.combat.strategy.MagicCombatStrategy
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.combat.strategy.ranged.BoltEnchantments
import org.alter.plugins.content.mechanics.poison.Poison
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-number tests for the pure OSRS combat math. Every expected value is derived
 * from the formulas published on the OSRS Wiki (Damage per second/Melee, Hit delay,
 * Poison, Enchanted bolts) so a regression in any constant breaks a test here.
 */
class CombatMathTests {

    @Test
    fun `effective level applies prayer then style then base 8`() {
        // 99 Strength, Piety (1.23), aggressive (+3): floor(99 * 1.23) = 121; +3 +8 = 132.
        assertEquals(132.0, CombatMath.effectiveLevel(99, prayerMultiplier = 1.23, styleBonus = 3))
        // No prayer, accurate (+3): 99 + 3 + 8 = 110.
        assertEquals(110.0, CombatMath.effectiveLevel(99, styleBonus = 3))
        // Void multiplier floors after the base: floor(110 * 1.10) = 121.
        assertEquals(121.0, CombatMath.effectiveLevel(99, styleBonus = 3, voidMultiplier = 1.10))
    }

    @Test
    fun `max hit matches the wiki formula`() {
        // effective strength 132, strength bonus +85: floor(0.5 + 132*149/640) = floor(31.23) = 31.
        assertEquals(31, CombatMath.baseMaxHit(132.0, 85.0))
        // The wiki's own worked shape: eff 118, bonus 0 -> floor(0.5 + 118*64/640) = 12.
        assertEquals(12, CombatMath.baseMaxHit(118.0, 0.0))
    }

    @Test
    fun `hit chance follows the two-branch OSRS formula`() {
        // attack > defence: 1 - (10000+2) / (2 * (20000+1)) = 0.749987...
        val high = CombatMath.hitChance(20000, 10000)
        assertTrue(high > 0.7499 && high < 0.7501, "got $high")
        // attack <= defence: 10000 / (2 * (20000+1)) = 0.24998...
        val low = CombatMath.hitChance(10000, 20000)
        assertTrue(low > 0.2499 && low < 0.2501, "got $low")
        // equal rolls sit just under 50%.
        val even = CombatMath.hitChance(10000, 10000)
        assertTrue(even > 0.49 && even < 0.50, "got $even")
    }

    @Test
    fun `max roll is effective level times bonus plus 64`() {
        assertEquals(132 * (85 + 64), CombatMath.maxRoll(132.0, 85.0))
    }

    @Test
    fun `ranged hit delay is 1 plus distance over 6`() {
        // OSRS: 1 + floor((3 + d) / 6) on chebyshev distance.
        fun delay(d: Int) = RangedCombatStrategy.getHitDelay(Tile(3200, 3200), Tile(3200 + d, 3200))
        assertEquals(1, delay(1))
        assertEquals(1, delay(2))
        assertEquals(2, delay(3))
        assertEquals(2, delay(8))
        assertEquals(3, delay(9))
        assertEquals(3, delay(10))
    }

    @Test
    fun `magic hit delay is 1 plus distance over 3`() {
        // OSRS: 1 + floor((1 + d) / 3) on chebyshev distance.
        fun delay(d: Int) = MagicCombatStrategy.getHitDelay(Tile(3200, 3200), Tile(3200 + d, 3200))
        assertEquals(1, delay(1))
        assertEquals(2, delay(2))
        assertEquals(2, delay(4))
        assertEquals(3, delay(5))
        assertEquals(4, delay(8))
        assertEquals(4, delay(10))
    }

    @Test
    fun `poison damage steps down one point per five procs`() {
        // poison(6) stores (6*5)-4 = 26 ticks; damage at n ticks left is (n/5)+1.
        assertEquals(6, Poison.getDamageForTicks(26))
        assertEquals(5, Poison.getDamageForTicks(21))
        assertEquals(1, Poison.getDamageForTicks(1))
    }

    @Test
    fun `ruby bolt maths match the wiki`() {
        // 20% of current HP, capped at 100.
        assertEquals(80, BoltEnchantments.rubyDamage(400))
        assertEquals(100, BoltEnchantments.rubyDamage(990))
        // Caster sacrifices 10% of current HP.
        assertEquals(9, BoltEnchantments.rubySacrifice(99))
    }
}
