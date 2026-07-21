package org.alter.plugins.content.skills.agility

import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.GameObject
import kotlin.math.abs

/**
 * Shared geometry for "carry the player across an obstacle" crossings. Given the clicked object
 * and the player's tile, it derives the crossing direction and the landing tile **entirely from
 * the object's live cache definition** (size + rotation) — no per-location tile tables.
 *
 * Extracted from [AgilityShortcutPlugin] so boss-access obstacles (e.g. the Vorkath ice chunks)
 * can reuse the exact same landing math without inheriting the Agility level gate / XP. Both the
 * Agility shortcuts and those free crossings compute their destination through here.
 */
object ObstacleCrossing {

    /** How the player ends up relative to the obstacle. */
    enum class Kind {
        /** End one tile past the obstacle's far edge (fences, walls, crevices, logs, ropes, ice chunks). */
        CROSS,

        /** Land on the obstacle tile itself (stepping stones — hop stone to stone). */
        ONTO,
    }

    /** Cardinal direction from the player toward the obstacle's centre. */
    fun cardinalDir(from: Tile, obj: GameObject): Direction {
        val (sx, sy) = footprint(obj)
        val cx = obj.tile.x + (sx - 1) / 2.0
        val cz = obj.tile.z + (sy - 1) / 2.0
        val dx = cx - from.x
        val dz = cz - from.z
        return if (abs(dx) >= abs(dz)) {
            if (dx >= 0) Direction.EAST else Direction.WEST
        } else {
            if (dz >= 0) Direction.NORTH else Direction.SOUTH
        }
    }

    /** Landing tile: onto the stone, or one tile past the obstacle's far edge (clip-nudged). */
    fun crossTile(world: World, playerTile: Tile, obj: GameObject, dir: Direction, kind: Kind): Tile {
        if (kind == Kind.ONTO) return obj.tile

        val (sx, sy) = footprint(obj)
        val p = playerTile
        var end = when (dir) {
            Direction.EAST -> Tile(obj.tile.x + sx, p.z, p.height)
            Direction.WEST -> Tile(obj.tile.x - 1, p.z, p.height)
            Direction.NORTH -> Tile(p.x, obj.tile.z + sy, p.height)
            else -> Tile(p.x, obj.tile.z - 1, p.height) // SOUTH
        }
        // If we'd land in a wall/water, step one or two further across.
        var tries = 0
        while (world.collision.isClipped(end) && tries < 2) {
            end = end.step(dir)
            tries++
        }
        return end
    }

    /** Object footprint in world axes, accounting for orientation (rot 1/3 swap x/y). */
    fun footprint(obj: GameObject): Pair<Int, Int> {
        val def = obj.getDef()
        val sx = def.sizeX.coerceAtLeast(1)
        val sy = def.sizeY.coerceAtLeast(1)
        return if (obj.rot and 1 == 1) sy to sx else sx to sy
    }

    fun chebyshev(a: Tile, b: Tile): Int = maxOf(abs(a.x - b.x), abs(a.z - b.z))
}
