package org.alter.game.fs

import dev.openrune.cache.*
import dev.openrune.cache.filestore.loadLocations
import dev.openrune.cache.filestore.loadTerrain
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.BLOCKED_TILE
import org.alter.game.model.collision.BRIDGE_TILE
import org.alter.game.model.entity.StaticObject
import org.alter.game.model.region.ChunkSet
import org.alter.game.service.xtea.XteaKeyService
import org.rsmod.routefinder.flag.CollisionFlag
import java.io.IOException

/**
 * A [DefinitionSet] is responsible for loading any relevant metadata found in
 * the game resources.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class DefinitionSet {
    private var xteaService: XteaKeyService? = null

    fun loadRegions(
        world: World,
        chunks: ChunkSet,
        regions: IntArray,
    ) {
        val start = System.currentTimeMillis()

        var loaded = 0
        regions.forEach { region ->
            // Add to activeRegions BEFORE createRegion: createRegion internally calls
            // chunks.getOrCreate(), and ChunkSet.get() will itself call createRegion for any
            // region not yet in activeRegions — so pre-adding is what prevents a re-entrant
            // double-load of this same region. add() returns false if already active → skip.
            if (!chunks.activeRegions.add(region)) return@forEach
            if (createRegion(world, region)) {
                loaded++
            } else {
                /*
                 * createRegion failed (transient cache/xtea read, or the XteaKeyService wasn't
                 * resolvable yet during a boot-time force-load). Roll the region back OUT of
                 * activeRegions so it's retried on demand when a player nears it — otherwise it
                 * stays flagged active-but-empty forever and its objects (doors, etc.) never
                 * spawn server-side.
                 */
                chunks.activeRegions.remove(region)
                logger.warn { "createRegion failed for region $region; rolled back (left retryable)." }
            }
        }
        logger.info { "Loaded $loaded regions in ${System.currentTimeMillis() - start}ms" }
    }

    /**
     * Creates an 8x8 [gg.rsmod.game.model.region.Chunk] region.
     */
    fun createRegion(
        world: World,
        id: Int,
    ): Boolean {
        if (xteaService == null) {
            xteaService = world.getService(XteaKeyService::class.java)
        }

        val x = id shr 8
        val y = id and 0xFF

        val mapData = CacheManager.cache.data(MAPS, "m${x}_$y") ?: run {
            logger.warn { "Region $id ($x,$y): no map (m) data in cache — skipping (retryable)." }
            return false
        }

        val baseX: Int = id shr 8 and 255 shl 6
        val baseZ: Int = id and 255 shl 6

        // Allocates all of the chunks within the region
        // TODO only allocate if the tiles are walkable.
        //val baseX = x * 64 // TODO perhaps just call cacheRegion.baseX
        //val baseZ = z * 64 // TODO perhaps just call cacheRegion.baseY
        for (cx in 0 until 8) {
            for (cz in 0 until 8) {
                val chunkBaseX = baseX + cx * 8
                val chunkBaseZ = baseZ + cz * 8
                for (level in 0 until 4) {
//                    world.collision.add(chunkBaseX, chunkBaseZ, level, 0)
                    world.collision.allocateIfAbsent(chunkBaseX, chunkBaseZ, level)
                }
            }
        }

        val blocked = hashSetOf<Tile>()
        val bridges = hashSetOf<Tile>()

        val tiles = loadTerrain(mapData)

        for (height in 0 until 4) {
            for (lx in 0 until 64) {
                for (ly in 0 until 64) {
                    val bridge = tiles[1][lx][ly].settings.toInt() and BRIDGE_TILE != 0
                    if (bridge) {
                        bridges.add(Tile(baseX + lx, baseZ + ly, height))
                    }
                    val blockedTile = tiles[height][lx][ly].settings.toInt() and BLOCKED_TILE != 0
                    if (blockedTile) {
                        val level = if (bridge) (height - 1) else height
                        if (level < 0) continue
                        blocked.add(Tile(baseX + lx, baseZ + ly, level))
                    }
                }
            }
        }

        /*
         * Apply the blocked tiles to the collision detection.
         */
        blocked.forEach { tile ->
            world.chunks.getOrCreate(tile)
            world.collision.add(tile.x, tile.z, tile.height, CollisionFlag.BLOCK_WALK)
        }
        /**
         * EDIT: turns out i was wrong. the assumption made here didn't pan out as expected. the bandos godwars room door ended up having different flags to before.
         *
         * instead, i just use
         * world.collisionFlags.applyUpdate(update)
         *
         * like in the other places, and that works
         *
         */
        if (xteaService == null) {
            /*
             * No [XteaKeyService] resolvable yet. This happens if a region is loaded (e.g. the war's
             * boot-time force-load) BEFORE the service is registered. We've applied terrain collision
             * but loaded NO objects (doors/scenery), so we must NOT report success: returning true here
             * would cache the region as "loaded" with an empty object set forever (loadRegions skips
             * already-active regions), leaving the whole area's objects — the Duke's door included —
             * un-interactable and passable until a restart. Return false so it's retried on demand,
             * by which point the service is available and the objects load.
             */
            logger.warn { "Region $id: XteaKeyService unavailable — objects NOT loaded (terrain only); left retryable." }
            return false
        }

        val keys = xteaService?.get(id) ?: XteaKeyService.EMPTY_KEYS
        try {
            val landData = CacheManager.cache.data(MAPS, "l${x}_$y", keys) ?: run {
                logger.warn { "Region $id ($x,$y): land (l) data null with keys=${keys.joinToString()} — objects NOT loaded; left retryable." }
                return false
            }
            loadLocations(landData) { loc ->
                val tile =
                    Tile(
                        baseX + loc.localX,
                        baseZ + loc.localY,
                        loc.height,
                    )
                val hasBridge = bridges.contains(tile)
                if (hasBridge && loc.height == 0) return@loadLocations
                val adjustedTile = if (bridges.contains(tile)) tile.transform(-1) else tile
                val obj = StaticObject(loc.id, loc.type, loc.orientation, adjustedTile)
                world.chunks.getOrCreate(adjustedTile).addEntity(world, obj, adjustedTile)
            }
            return true
        } catch (e: IOException) {
            logger.error { "${"Could not decrypt map region {}."} $id" }
            return false
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
