package org.alter.game.model.collision

import org.alter.game.model.Tile
import org.alter.game.model.region.Chunk
import org.rsmod.routefinder.LineValidator
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.loc.LocShapeConstants

fun CollisionFlagMap.isClipped(tile: Tile): Boolean = get(tile) != 0
const val WALL_DIAGONAL = LocShapeConstants.WALL_DIAGONAL;
const val BLOCKED_TILE = 0x1
const val BRIDGE_TILE = 0x2
const val ROOF_TILE = 0x4

/**
 * Casts a line using Bresenham's Line Algorithm with point A [start] and
 * point B [target] being its two points and makes sure that there's no
 * collision flag that can block movement from and to both points. This function
 * was originally CollisionManager#raycast in rsmod1.
 *
 * @param projectile
 * Projectiles have a higher tolerance for certain objects when the object's
 * metadata explicitly allows them to.
 */
fun LineValidator.rayCast(
    start: Tile,
    target: Tile,
    projectile: Boolean,
): Boolean {
    check(start.height == target.height) { "Tiles must be on the same height level." }
    return if (projectile) {
        hasLineOfSight(start.height, start.x, start.z, target.x, target.z)
    } else {
        hasLineOfWalk(start.height, start.x, start.z, target.x, target.z)
    }
}

/**
 * Block a single tile of [newChunk] (chunk-local [lx]/[lz]) at height [chunkH]: walk-block always,
 * plus a projectile blocker when [impenetrable]. Used by the instance allocator to copy terrain
 * blocks and to wall off chunks that aren't part of the instance.
 *
 * This used to be a `TODO` NO-OP — instances therefore had no terrain collision at all (walkable
 * water/void), and open zones nothing ever wrote to stayed UNALLOCATED; rsmod reads unallocated
 * zones as -1 (all flags set), which silently failed every line-of-sight raycast crossing open
 * ground in an instance (npcs faced their target but never attacked). See also
 * [org.alter.game.model.instance.InstancedMapAllocator.applyCollision], which now allocates every
 * instance zone explicitly (mirroring DefinitionSet's region load).
 */
fun CollisionFlagMap.block(newChunk: Chunk, chunkH: Int, lx: Int, lz: Int, impenetrable: Boolean) {
    val base = newChunk.coords.toTile()
    var mask = org.rsmod.routefinder.flag.CollisionFlag.BLOCK_WALK
    if (impenetrable) mask = mask or org.rsmod.routefinder.flag.CollisionFlag.LOC_PROJ_BLOCKER
    add(base.x + lx, base.z + lz, chunkH, mask)
}