package org.alter.plugins.content.raids

import org.alter.game.model.Area
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.instance.InstancedChunkSet
import org.alter.game.model.instance.InstancedMap
import org.alter.game.model.instance.InstancedMapAttribute
import org.alter.game.model.instance.InstancedMapConfiguration

/**
 * Thin wrapper over the engine's [org.alter.game.model.instance.InstancedMapAllocator] (R0).
 *
 * [allocate] copies a chunk-aligned [Area] of the live map into a fresh, isolated instance in
 * the reserved instance space and returns the handle; [translate] maps any source-arena tile
 * to its copy inside the allocated instance (so encounter spawns/teleports land correctly no
 * matter where the instance was placed). Teardown is automatic — the allocator deallocates the
 * map when it empties, and on the owner's logout/death (the attributes set below).
 */
class RaidInstance private constructor(
    val map: InstancedMap,
    private val srcBaseX: Int,
    private val srcBaseZ: Int,
    /** 0 = plain same-plane copy. Non-zero = the [allocateFloors] layout: source LEVEL h was
     *  copied onto INSTANCE PLANE 0, offset east by `h * floorStrideTiles` — floors laid out
     *  side-by-side as void-separated islands so the client never has geometry stacked above
     *  the player (multi-plane instance copies render all floors superimposed). */
    private val floorStrideTiles: Int = 0,
) {
    /** Map a source-arena tile to its copy inside this instance (rotation 0, direct copy).
     *  In the floors layout the source tile's HEIGHT picks the island; the copy is on plane 0. */
    fun translate(t: Tile): Tile =
        if (floorStrideTiles == 0) {
            Tile(
                map.area.bottomLeftX + (t.x - srcBaseX),
                map.area.bottomLeftY + (t.z - srcBaseZ),
                t.height,
            )
        } else {
            Tile(
                map.area.bottomLeftX + (t.x - srcBaseX) + t.height * floorStrideTiles,
                map.area.bottomLeftY + (t.z - srcBaseZ),
                0,
            )
        }

    /** Which source LEVEL an instance tile represents: its height normally; in the floors
     *  layout, the island index recovered from the eastward stride. */
    fun floorOf(t: Tile): Int =
        if (floorStrideTiles == 0) t.height else (t.x - map.area.bottomLeftX) / floorStrideTiles

    fun contains(t: Tile): Boolean = map.area.contains(t)

    companion object {
        /**
         * Live instances → the source area each one copies, so content can ask "what is this
         * instance tile a copy of?" without the allocating plugin tracking its own instances
         * (`CompanionPolicy.denyInstanceOf`). Stale entries (deallocated maps) are pruned on the
         * next allocation — the allocator no longer resolves their corner tile to that map.
         */
        private val sources = HashMap<InstancedMap, Area>()

        /** The source [Area] the instance under [tile] was copied from, or null if [tile] isn't instanced. */
        fun sourceOf(world: World, tile: Tile): Area? {
            val map = world.instanceAllocator.getMap(tile) ?: return null
            return sources[map]
        }

        private fun remember(world: World, map: InstancedMap, source: Area) {
            sources.keys.removeAll { stale ->
                world.instanceAllocator.getMap(Tile(stale.area.bottomLeftX, stale.area.bottomLeftY, 0)) !== stale
            }
            sources[map] = source
        }

        /**
         * Allocate an instance copying [sourceArea] (snapped to chunk bounds) for [owner].
         * Returns null if the instance space is full. Force-loads the source regions first so
         * their collision/objects are present to copy even if no player has visited them.
         */
        fun allocate(
            world: World,
            sourceArea: Area,
            exitTile: Tile,
            owner: PlayerUID,
            levels: Int = 1,
            /** When true (default) the instance tears down the moment the OWNER dies or logs out —
             *  right for solo PvM (Fight Cave etc.). Set FALSE for free-for-all content where the owner
             *  is just another competitor and will die mid-game (Last Man Standing): the instance then
             *  only deallocates once it EMPTIES (the allocator's idle scan), so one death can't eject the
             *  whole lobby. The owning plugin is responsible for emptying it (move everyone out on end). */
            autoDeallocate: Boolean = true,
        ): RaidInstance? {
            val baseX = (sourceArea.bottomLeftX shr 3) shl 3
            val baseZ = (sourceArea.bottomLeftY shr 3) shl 3
            val chunksWide = ((sourceArea.topRightX - baseX) + 7) shr 3
            val chunksHigh = ((sourceArea.topRightY - baseZ) + 7) shr 3

            // Ensure the source chunks are populated before the allocator copies them.
            val regions = sortedSetOf<Int>()
            for (cx in 0 until chunksWide) for (cz in 0 until chunksHigh) {
                regions += (((baseX + (cx shl 3)) shr 6) shl 8) or ((baseZ + (cz shl 3)) shr 6)
            }
            runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }

            val builder = InstancedChunkSet.Builder()
            for (cx in 0 until chunksWide) for (cz in 0 until chunksHigh) for (h in 0 until levels) {
                builder.set(cx, cz, h, rot = 0, copy = Tile(baseX + (cx shl 3), baseZ + (cz shl 3), h))
            }

            val config = InstancedMapConfiguration.Builder()
                .setExitTile(exitTile)
                .setOwner(owner)
                .apply {
                    if (autoDeallocate) {
                        addAttribute(InstancedMapAttribute.DEALLOCATE_ON_LOGOUT, InstancedMapAttribute.DEALLOCATE_ON_DEATH)
                    }
                }
                .build()

            val map = world.instanceAllocator.allocate(world, builder.build(), config) ?: return null
            remember(world, map, sourceArea)
            return RaidInstance(map, baseX, baseZ)
        }

        /**
         * Allocate an instance that copies source LEVELS `0 until levels` of [sourceArea] onto
         * **plane 0**, laid out side-by-side along X as islands separated by [gapChunks] chunks of
         * void. Use this instead of a stacked [allocate] for multi-floor buildings the player
         * fights INSIDE: a stacked copy leaves the upper floors' geometry above the player and the
         * client renders every floor superimposed; with islands there is simply nothing above.
         * Move between "floors" by teleporting between islands ([translate] does the math — feed
         * it source tiles with their real heights; [floorOf] recovers the island index).
         *
         * [groundArea] (optional, must share [sourceArea]'s SW corner column) enlarges ONLY the
         * level-0 island — e.g. the outdoor grounds around a tower. For its extension chunks
         * (those outside [sourceArea]) source plane 1 is copied to instance plane 1 as well:
         * BRIDGES store their deck locs + bridge flag on plane 1 (the client renders them shifted
         * down to the ground), so without this a bridge in the grounds would be an invisible strip.
         * Server-side collision needs nothing extra — the engine already stores bridge collision
         * and deck objects shifted to plane 0 at region load.
         */
        fun allocateFloors(
            world: World,
            sourceArea: Area,
            levels: Int,
            exitTile: Tile,
            owner: PlayerUID,
            gapChunks: Int = 1,
            groundArea: Area? = null,
        ): RaidInstance? {
            val baseX = (sourceArea.bottomLeftX shr 3) shl 3
            val baseZ = (sourceArea.bottomLeftY shr 3) shl 3
            val chunksWide = ((sourceArea.topRightX - baseX) + 7) shr 3
            val chunksHigh = ((sourceArea.topRightY - baseZ) + 7) shr 3
            val ground = groundArea ?: sourceArea
            val groundChunksWide = ((ground.topRightX - baseX) + 7) shr 3
            val groundChunksHigh = ((ground.topRightY - baseZ) + 7) shr 3

            val regions = sortedSetOf<Int>()
            for (cx in 0 until maxOf(chunksWide, groundChunksWide)) for (cz in 0 until maxOf(chunksHigh, groundChunksHigh)) {
                regions += (((baseX + (cx shl 3)) shr 6) shl 8) or ((baseZ + (cz shl 3)) shr 6)
            }
            runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }

            val strideChunks = maxOf(chunksWide, groundChunksWide) + gapChunks
            val builder = InstancedChunkSet.Builder()
            // Ground island: the (possibly enlarged) grounds, plus plane-1 copies for the
            // extension chunks (bridge decks render from plane 1).
            for (cx in 0 until groundChunksWide) for (cz in 0 until groundChunksHigh) {
                builder.set(cx, cz, 0, rot = 0, copy = Tile(baseX + (cx shl 3), baseZ + (cz shl 3), 0))
                if (cx >= chunksWide || cz >= chunksHigh) {
                    builder.set(cx, cz, 1, rot = 0, copy = Tile(baseX + (cx shl 3), baseZ + (cz shl 3), 1))
                }
            }
            // Upper floors: the building footprint only.
            for (level in 1 until levels) for (cx in 0 until chunksWide) for (cz in 0 until chunksHigh) {
                builder.set(
                    level * strideChunks + cx, cz, 0, rot = 0,
                    copy = Tile(baseX + (cx shl 3), baseZ + (cz shl 3), level),
                )
            }

            val config = InstancedMapConfiguration.Builder()
                .setExitTile(exitTile)
                .setOwner(owner)
                .addAttribute(InstancedMapAttribute.DEALLOCATE_ON_LOGOUT, InstancedMapAttribute.DEALLOCATE_ON_DEATH)
                .build()

            val map = world.instanceAllocator.allocate(world, builder.build(), config) ?: return null
            remember(world, map, sourceArea)
            return RaidInstance(map, baseX, baseZ, floorStrideTiles = strideChunks shl 3)
        }
    }
}
