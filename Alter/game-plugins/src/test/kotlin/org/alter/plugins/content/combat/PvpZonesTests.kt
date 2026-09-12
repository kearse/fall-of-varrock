package org.alter.plugins.content.combat

import org.alter.game.model.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The human-PvP zoning table: the OSRS wilderness line at the Edgeville ditch, the depth formula,
 * the Fallen Varrock pocket and its carve-outs, the boss lairs, and single-vs-multi. Pure data —
 * a moved box or a formula slip breaks a line here before it breaks a player.
 */
class PvpZonesTests {

    @Test
    fun `the mainland is safe from players`() {
        listOf(
            Tile(3222, 3218) to "Lumbridge courtyard",
            Tile(3093, 3245) to "Draynor",
            Tile(2965, 3380) to "Falador",
            Tile(3293, 3180) to "Al Kharid",
            Tile(3093, 3495) to "Edgeville bank",
            Tile(3370, 3425) to "the Digsite",
            Tile(3235, 3345) to "Lumbridge north road (the old custom red)",
        ).forEach { (tile, name) ->
            assertFalse(PvpZones.isWilderness(tile), "$name should be safe")
            assertEquals(0, PvpZones.wildernessLevel(tile), "$name level")
        }
    }

    @Test
    fun `the ditch is the line and levels follow the OSRS depth formula`() {
        assertFalse(PvpZones.isWilderness(Tile(3093, 3522)), "south of the ditch")
        assertTrue(PvpZones.isWilderness(Tile(3093, 3523)), "first tile north of the ditch")
        assertEquals(1, PvpZones.wildernessLevel(Tile(3093, 3523)))
        assertEquals(1, PvpZones.wildernessLevel(Tile(3093, 3527)))
        assertEquals(2, PvpZones.wildernessLevel(Tile(3093, 3528)))
        assertEquals(20, PvpZones.wildernessLevel(Tile(3200, 3675)))
        assertEquals(56, PvpZones.wildernessLevel(Tile(3093, 3967)))
        assertFalse(PvpZones.isWilderness(Tile(2943, 3600)), "west of the wilderness")
        assertFalse(PvpZones.isWilderness(Tile(3392, 3600)), "east of the wilderness")
    }

    @Test
    fun `the wild is single by default with multi boxes as the exception`() {
        assertTrue(PvpZones.isSingle(Tile(3093, 3527)), "just north of Edgeville")
        assertTrue(PvpZones.isMulti(Tile(3035, 3635)), "Dark Warriors' Fortress")
        assertTrue(PvpZones.isMulti(Tile(3300, 3880)), "Demonic Ruins")
        assertTrue(PvpZones.isSingle(Tile(3037, 3690)), "the Wild Bandit Camp stays 1v1")
        assertTrue(PvpZones.isSingle(Tile(3015, 3880)), "the Rogue Commander's Redoubt stays 1v1")
    }

    @Test
    fun `fallen varrock is a fixed-level single-combat pocket with safe banks`() {
        val square = Tile(3211, 3424)
        assertTrue(PvpZones.isWilderness(square))
        assertEquals(PvpZones.VARROCK_POCKET_LEVEL, PvpZones.wildernessLevel(square))
        assertTrue(PvpZones.isSingle(square))
        assertFalse(PvpZones.isWilderness(Tile(3185, 3440)), "Varrock west bank")
        assertFalse(PvpZones.isWilderness(Tile(3253, 3420)), "Varrock east bank")
        assertFalse(PvpZones.isWilderness(Tile(3165, 3490)), "Grand Exchange")
        assertFalse(PvpZones.isWilderness(Tile(3211, 3375)), "just south of the pocket")
    }

    @Test
    fun `underground lairs keep their fixed levels and are multi`() {
        assertEquals(54, PvpZones.wildernessLevel(Tile(3232, 10335)), "Scorpia's cave")
        assertEquals(34, PvpZones.wildernessLevel(Tile(3230, 10200)), "Vet'ion's Rest")
        assertEquals(41, PvpZones.wildernessLevel(Tile(3290, 10200)), "Callisto's Den")
        assertEquals(28, PvpZones.wildernessLevel(Tile(3350, 10300)), "Venenatis' dens")
        assertTrue(PvpZones.isMulti(Tile(3232, 10335)))
    }

    @Test
    fun `a registered bank radius is a carve-out and a bank sanctuary`() {
        val booth = Tile(3300, 3700) // deep-wild tile nobody else registers
        assertTrue(PvpZones.isWilderness(booth))
        assertFalse(PvpZones.isBankSafe(booth))
        PvpZones.safeAround(booth, 8)
        assertTrue(PvpZones.isBankSafe(Tile(3305, 3705)))
        assertFalse(PvpZones.isWilderness(Tile(3305, 3705)), "inside the radius")
        assertTrue(PvpZones.isWilderness(Tile(3310, 3700)), "outside the radius")
    }
}
