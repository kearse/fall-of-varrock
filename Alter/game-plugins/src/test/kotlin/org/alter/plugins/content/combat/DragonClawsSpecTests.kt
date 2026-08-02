package org.alter.plugins.content.combat

import org.alter.plugins.content.combat.specialattack.weapons.dragonclaws.DragonClawsSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the exact OSRS dragon-claws damage table (OSRS Wiki, Dragon claws). */
class DragonClawsSpecTests {

    @Test
    fun `first roll splits X, half, quarter, quarter plus one`() {
        val d = DragonClawsSpec.rollDamages(
            maxHit = 40,
            rolls = booleanArrayOf(true, false, false, false),
            roll = { it.first }, // X = 20 (max/2)
            chance = { 1.0 },
        )
        assertEquals(listOf(20, 10, 5, 6), d.toList())
    }

    @Test
    fun `second roll splits 0, X, half, half plus one`() {
        val d = DragonClawsSpec.rollDamages(
            maxHit = 40,
            rolls = booleanArrayOf(false, true, false, false),
            roll = { it.first }, // X = 15 (3max/8)
            chance = { 1.0 },
        )
        assertEquals(listOf(0, 15, 7, 8), d.toList())
    }

    @Test
    fun `third roll is 0, 0, X, X plus one`() {
        val d = DragonClawsSpec.rollDamages(
            maxHit = 40,
            rolls = booleanArrayOf(false, false, true, false),
            roll = { it.first }, // X = 10 (max/4)
            chance = { 1.0 },
        )
        assertEquals(listOf(0, 0, 10, 11), d.toList())
    }

    @Test
    fun `fourth roll is a single hit up to 125 percent`() {
        val d = DragonClawsSpec.rollDamages(
            maxHit = 40,
            rolls = booleanArrayOf(false, false, false, true),
            roll = { it.last }, // X = 50 (5max/4)
            chance = { 1.0 },
        )
        assertEquals(listOf(0, 0, 0, 50), d.toList())
    }

    @Test
    fun `all-miss scratches 1 plus 1 a quarter of the time, else nothing`() {
        val scratch = DragonClawsSpec.rollDamages(40, BooleanArray(4), roll = { it.first }, chance = { 0.1 })
        assertEquals(listOf(0, 0, 1, 1), scratch.toList())
        val nothing = DragonClawsSpec.rollDamages(40, BooleanArray(4), roll = { it.first }, chance = { 0.9 })
        assertEquals(listOf(0, 0, 0, 0), nothing.toList())
    }
}
