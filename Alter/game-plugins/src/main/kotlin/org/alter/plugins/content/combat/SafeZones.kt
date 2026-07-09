package org.alter.plugins.content.combat

import org.alter.game.model.Tile

/**
 * Superseded by [PvpZones]. Kept as a thin alias so any external reference to `SafeZones.isSafe`
 * still resolves; all real zoning logic (wilderness / single / multi / safe / depth level) lives in
 * [PvpZones].
 */
object SafeZones {
    fun isSafe(tile: Tile): Boolean = PvpZones.isSafe(tile)
}
