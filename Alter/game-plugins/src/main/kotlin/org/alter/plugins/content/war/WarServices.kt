package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile

/**
 * The war's effect on a city's everyday services (The War — "make it matter"). While a
 * city is **fallen** (General Zo's castle was overrun in a raid), Lumbridge stops being a
 * safe haven: the vendors have fled and the bank vaults are sealed until it recovers.
 *
 * This is the single place the fallen -> services rule lives, consulted by the shop/bank
 * entry points so the consequence is consistent however a player reaches the service.
 *
 * @see WarState.isCityFallen
 */
object WarServices {
    /** Lumbridge's footprint — where services are disrupted while the city is fallen. */
    val LUMBRIDGE = Area(3200, 3200, 3260, 3270)

    private val front: String get() = WarStatePlugin.FRONTIER_NORTH

    /** Shopkeepers flee while the city is fallen. */
    fun shopsClosed(): Boolean = WarState.isCityFallen(front)

    /** The bank seals while the city is fallen. */
    fun bankSealed(): Boolean = WarState.isCityFallen(front)

    fun inLumbridge(tile: Tile): Boolean = LUMBRIDGE.contains(tile)
}
