package org.alter.plugins.content.war

import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.loadTerrain

/**
 * Cache-derived terrain walkability, independent of the runtime collision map.
 *
 * [CityFrontierPlugin] builds its ring staging at **construction time** — before any region is
 * streamed in — so `world.collision` is still empty and can't be queried (the old code lamented
 * exactly this: *"the collision map isn't populated this early, so we can't pre-filter staging"*).
 * The map's blocked-tile bits, however, live in the cache and are already available by then
 * ([CacheManager] is initialised during server boot). We decode the terrain map (`m{x}_{y}`,
 * unencrypted) once per region and cache the blocked mask.
 *
 * **Terrain only** — water, cliffs, void. Object/wall clipping still comes from the live collision
 * map at runtime ([org.alter.game.model.World.findRandomTileAround] jitters a spawn off blocked
 * tiles within radius 4). That division of labour is enough to stop enemies mustering in the
 * River Lum or the swamp, which is the bulk of the wasted staging.
 *
 * Mirrors the decode in [org.alter.game.fs.DefinitionSet] / the `mapDump` tool, so it agrees
 * with the engine's terrain clipping byte-for-byte.
 */
object StaticTerrain {
    private const val BLOCKED = 0x1 // org.alter.game.model.collision.BLOCKED_TILE

    /** regionId -> [level][localX][localY] blocked mask, or null if the region has no map data. */
    private val regions = HashMap<Int, Array<Array<BooleanArray>>?>()

    private fun region(regionId: Int): Array<Array<BooleanArray>>? = regions.getOrPut(regionId) {
        val x = regionId shr 8
        val y = regionId and 0xFF
        val data = CacheManager.cache.data(MAPS, "m${x}_$y") ?: return@getOrPut null
        val tiles = loadTerrain(data)
        Array(4) { z -> Array(64) { lx -> BooleanArray(64) { ly -> (tiles[z][lx][ly].settings.toInt() and BLOCKED) != 0 } } }
    }

    /** True if [x],[z] is blocked terrain (water/cliff). Unknown/unmapped tiles read as open. */
    fun isBlocked(x: Int, z: Int, level: Int = 0): Boolean {
        val grid = region(((x shr 6) shl 8) or (z shr 6)) ?: return false
        return grid[level][x and 63][z and 63]
    }

    fun isWalkable(x: Int, z: Int, level: Int = 0): Boolean = !isBlocked(x, z, level)

    /**
     * The closest walkable tile to [x],[z] (the tile itself if already walkable), searched in
     * growing square rings out to [maxRadius]. Returns null if everything in range is blocked
     * terrain. Used to snap hand-authored route waypoints off the river/swamp without dropping
     * them (a route needs every waypoint; staging can just discard bad points).
     */
    fun nearestWalkable(x: Int, z: Int, level: Int = 0, maxRadius: Int = 8): Pair<Int, Int>? {
        if (isWalkable(x, z, level)) return x to z
        for (r in 1..maxRadius) {
            var best: Pair<Int, Int>? = null
            for (dx in -r..r) for (dz in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue // current ring only
                if (isWalkable(x + dx, z + dz, level)) {
                    // prefer the geometrically nearest tile on this ring
                    val d2 = dx * dx + dz * dz
                    if (best == null || d2 < (best.first - x) * (best.first - x) + (best.second - z) * (best.second - z)) {
                        best = (x + dx) to (z + dz)
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }
}
