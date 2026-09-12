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
 * The OSRS wilderness geometry — the ONE place the surface box, the underground boss lairs and the
 * depth→level formula live. `PvpZones` (game-plugins) consumes this for human PvP zoning and layers
 * its own pockets / hostile zones / safe carve-outs on top; nothing else should re-derive these
 * numbers (the old hand-kept mirror between the two modules drifted, which is why this exists).
 */
object Wilderness {

    /**
     * Surface box: the full OSRS wilderness width, from the first tile north of the Edgeville ditch
     * (the ditch hop lands you at `ditch.z + 2`, i.e. z3523) up to the northern sea. Everything south
     * of this line is safe FROM PLAYERS.
     */
    val SURFACE: Area = Area(2944, 3523, 3391, 3967)

    /**
     * Underground boss lairs at their OSRS fixed levels. [Area] is x/z-only and every surface box
     * lives at surface latitudes, so a cave at z≈10300 needs its own box or it reads as safe ground
     * (Scorpia's lair had no overlay, no PvP, no skull and no death drops — player report 2026-09-03).
     */
    val DUNGEONS: List<Pair<Area, Int>> = listOf(
        Area(3200, 10304, 3263, 10367) to 54, // r12961 Scorpia's cave
        Area(3200, 10176, 3263, 10239) to 34, // r12959 Vet'ion's Rest
        Area(3264, 10176, 3327, 10239) to 41, // r13215 Callisto's Den
        Area(3328, 10240, 3391, 10367) to 28, // r13472/13473 Venenatis' dens
    )

    const val MAX_LEVEL = 56

    /** Depth origin for the OSRS/RuneLite formula `((z - 3520) / 8) + 1`: level 1 spans z3523-3527,
     *  level 2 starts at z3528, level 56 covers z3960-3967. */
    private const val LEVEL_ORIGIN_Z = 3520
    private const val TILES_PER_LEVEL = 8

    /** Wilderness level at [t] by OSRS geometry alone: a lair's fixed level, the surface depth, or 0. */
    fun levelAt(t: Tile): Int {
        DUNGEONS.firstOrNull { it.first.contains(t) }?.let { return it.second }
        if (!SURFACE.contains(t)) return 0
        return ((t.z - LEVEL_ORIGIN_Z) / TILES_PER_LEVEL + 1).coerceIn(1, MAX_LEVEL)
    }
}
