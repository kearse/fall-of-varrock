package org.alter.api.ext

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World

fun Tile.isMulti(world: World): Boolean {
    val region = regionId
    val chunk = chunkCoords.hashCode()
    return world.getMultiCombatChunks().contains(chunk) || world.getMultiCombatRegions().contains(region)
}

/**
 * The underground wilderness boss lairs at their OSRS fixed levels. MIRROR of
 * `PvpZones.WILD_DUNGEONS` (game-plugins — this module cannot see it): keep both in step.
 * Without this every cave tile answered 0, so standard teleports worked from inside
 * Scorpia's cave while the surface model blocked them.
 */
private val UNDERGROUND_WILD: List<Pair<Area, Int>> = listOf(
    Area(3200, 10304, 3263, 10367) to 54, // Scorpia's cave
    Area(3200, 10176, 3263, 10239) to 34, // Vet'ion's Rest
    Area(3264, 10176, 3327, 10239) to 41, // Callisto's Den
    Area(3328, 10240, 3391, 10367) to 28, // Venenatis' dens
)

fun Tile.getWildernessLevel(): Int {
    UNDERGROUND_WILD.firstOrNull { it.first.contains(this) }?.let { return it.second }
    if (x !in 2941..3392 || z !in 3524..3968) {
        return 0
    }

    val y = if (this.z > 6400) this.z - 6400 else this.z
    return (((y - 3525) shr 3) + 1)
}
