package org.alter.plugins.content.bots

import org.alter.game.model.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Rogue Knights' danger bands and city cores — pure geometry. The tier ladder reads
 * [RogueTerritory.dangerLevel] through [BotZones.tierForWildLevel], so a band slip here would
 * silently put elite NHers on the Lumbridge road.
 */
class RogueTerritoryTests {

    @Test
    fun `danger is the wilderness level inside live wilderness`() {
        assertEquals(1, RogueTerritory.dangerLevel(Tile(3093, 3525)), "just past the ditch")
        assertEquals(56, RogueTerritory.dangerLevel(Tile(3093, 3965)), "the deep wild")
        assertEquals(20, RogueTerritory.dangerLevel(Tile(3211, 3424)), "the Varrock pocket")
    }

    @Test
    fun `mainland danger scales with distance from the nearest safe city`() {
        assertEquals(5, RogueTerritory.dangerLevel(Tile(3222, 3218)), "Lumbridge courtyard")
        assertEquals(5, RogueTerritory.dangerLevel(Tile(3093, 3495)), "Edgeville bank")
        assertEquals(15, RogueTerritory.dangerLevel(Tile(3160, 3300)), "the Draynor road")
        assertEquals(25, RogueTerritory.dangerLevel(Tile(3235, 3345)), "north of Lumbridge, between cities")
        assertEquals(35, RogueTerritory.dangerLevel(Tile(3400, 3300)), "the far east corner")
        // The mainland ceiling maps to T_HIGH — never T_ELITE (that needs 41+).
        assertTrue(RogueTerritory.dangerLevel(Tile(3400, 3300)) <= 40)
    }

    @Test
    fun `city cores cover the safe towns but not the roads or Varrock`() {
        assertTrue(RogueTerritory.inCityCore(Tile(3222, 3218)), "Lumbridge")
        assertTrue(RogueTerritory.inCityCore(Tile(2965, 3380)), "Falador")
        assertTrue(RogueTerritory.inCityCore(Tile(3093, 3495)), "Edgeville")
        assertTrue(RogueTerritory.inCityCore(Tile(3165, 3490)), "Grand Exchange")
        assertFalse(RogueTerritory.inCityCore(Tile(3235, 3345)), "the Lumbridge north road")
        assertFalse(RogueTerritory.inCityCore(Tile(3211, 3424)), "Varrock (fallen)")
    }
}
