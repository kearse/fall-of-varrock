package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import kotlin.math.abs
import kotlin.math.max

/**
 * The **spatial brain** — a gridded tactical analysis layer over the battle box. The game
 * world is already an exact tile grid; this reads it into a few cheap per-tile fields the
 * War Brain reasons over (the RTS-AI toolkit: influence maps + a flow field):
 *
 *  - **Walkability** — sampled once from the engine collision map (`isClipped`).
 *  - **Flow field** — a BFS distance field FROM the objective (General Zo) over walkable
 *    tiles. 300 goblins path toward the castle for the cost of one search, terrain-aware:
 *    they naturally funnel through the gate/bridges because the grid forces it
 *    ([nextStepToward] follows the downhill gradient, optionally steering around threat).
 *  - **Influence/threat maps** — each tick, goblin/knight strength and **player threat**
 *    (combat level) are stamped with radial falloff, so allocation/targeting can read
 *    "where is it hot / where are we weak" instead of just headcounts.
 *
 * Everything is bounded to the battle box and O(area), recomputed on a slow cadence — cheap
 * for ~120×120 tiles even with 400 units.
 *
 * `// WAR BRAIN ROADMAP`: this is the substrate for the deep spatial AI — danger-avoiding
 * flow fields, chokepoint detection (scan walkability for 1-wide gaps), encirclement
 * (multi-objective fields), fog-of-war (a per-tile "seen" mask), and predictive interception.
 */
class TacticalMap(
    private val minX: Int,
    private val minZ: Int,
    val width: Int,
    val height: Int,
    private val level: Int = 0,
) {
    private val size = width * height
    private val walkable = BooleanArray(size)
    private val flow = IntArray(size) { UNREACHED }
    private val goblinInf = IntArray(size)
    private val knightInf = IntArray(size)
    private val threat = IntArray(size)
    private var built = false
    private var flowObjective: Tile? = null

    private fun idx(x: Int, z: Int) = (z - minZ) * width + (x - minX)
    fun inBounds(x: Int, z: Int) = x >= minX && x < minX + width && z >= minZ && z < minZ + height
    fun inBounds(t: Tile) = inBounds(t.x, t.z)

    /** Sample walkability from the collision map (re-callable: must be (re)built AFTER the map's
     *  collision regions are loaded — a boot-time sample reads everything as clipped). Resets the
     *  flow objective so the next [computeFlow] recomputes against the fresh grid. */
    fun buildWalkability(world: World) {
        for (z in 0 until height) for (x in 0 until width) {
            walkable[z * width + x] = !world.collision.isClipped(Tile(minX + x, minZ + z, level))
        }
        built = true
        flowObjective = null
    }

    /** BFS a flow field of distances FROM [objective] over walkable tiles (8-connected).
     *  Recomputed only when the objective moves (it rarely does), so this is near-free. */
    fun computeFlow(world: World, objective: Tile) {
        if (!built) buildWalkability(world)
        if (flowObjective?.x == objective.x && flowObjective?.z == objective.z) return
        flowObjective = objective
        flow.fill(UNREACHED)
        if (!inBounds(objective)) return
        val start = idx(objective.x, objective.z)
        // Allow the objective tile even if "clipped" (Zo may stand on a decorated tile).
        flow[start] = 0
        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = minX + cur % width
            val cz = minZ + cur / width
            val nd = flow[cur] + 1
            for (d in NEIGHBORS) {
                val nx = cx + d[0]; val nz = cz + d[1]
                if (!inBounds(nx, nz)) continue
                val ni = idx(nx, nz)
                if (!walkable[ni] || flow[ni] <= nd) continue
                flow[ni] = nd
                queue.add(ni)
            }
        }
    }

    /**
     * From [from], follow the flow gradient toward the objective up to [lookahead] tiles,
     * preferring tiles with lower [threat] (steer the horde around player kill-zones), and
     * return the tile reached — a good `walkTo` target the route finder can reach. Returns
     * null if [from] isn't on the reachable field (caller falls back to waypoints).
     */
    fun nextStepToward(from: Tile, lookahead: Int, threatWeight: Int = 2): Tile? {
        if (!inBounds(from)) return null
        var ci = idx(from.x, from.z)
        if (flow[ci] == UNREACHED) return null
        var cx = from.x; var cz = from.z
        var moved = false
        repeat(lookahead) {
            var bestX = cx; var bestZ = cz; var bestCost = Int.MAX_VALUE
            val curFlow = flow[ci]
            for (d in NEIGHBORS) {
                val nx = cx + d[0]; val nz = cz + d[1]
                if (!inBounds(nx, nz)) continue
                val ni = idx(nx, nz)
                if (!walkable[ni] || flow[ni] == UNREACHED || flow[ni] >= curFlow) continue
                val cost = flow[ni] + threatWeight * threatAt(nx, nz)
                if (cost < bestCost) { bestCost = cost; bestX = nx; bestZ = nz }
            }
            if (bestX == cx && bestZ == cz) return@repeat
            cx = bestX; cz = bestZ; ci = idx(cx, cz); moved = true
        }
        return if (moved) Tile(cx, cz, level) else null
    }

    /** Distance to the objective for [tile] along the flow field, or [UNREACHED]. */
    fun flowDistance(tile: Tile): Int = if (inBounds(tile)) flow[idx(tile.x, tile.z)] else UNREACHED

    /** Reset + restamp the influence/threat fields from the current combatants. */
    fun stampInfluence(goblins: List<Npc>, knights: List<Npc>, players: List<Player>) {
        goblinInf.fill(0); knightInf.fill(0); threat.fill(0)
        goblins.forEach { stamp(goblinInf, it.tile, INF_RADIUS, 1) }
        knights.forEach { stamp(knightInf, it.tile, INF_RADIUS, 2) }
        players.forEach { stamp(threat, it.tile, THREAT_RADIUS, max(1, it.combatLevel / 10)) }
    }

    private fun stamp(field: IntArray, at: Tile, radius: Int, weight: Int) {
        for (dz in -radius..radius) for (dx in -radius..radius) {
            val x = at.x + dx; val z = at.z + dz
            if (!inBounds(x, z)) continue
            val falloff = radius - max(abs(dx), abs(dz)) + 1
            if (falloff <= 0) continue
            field[idx(x, z)] += weight * falloff
        }
    }

    fun threatAt(x: Int, z: Int): Int = if (inBounds(x, z)) threat[idx(x, z)] else 0
    fun threatAt(t: Tile): Int = threatAt(t.x, t.z)
    fun goblinInfluenceAt(t: Tile): Int = if (inBounds(t)) goblinInf[idx(t.x, t.z)] else 0
    fun knightInfluenceAt(t: Tile): Int = if (inBounds(t)) knightInf[idx(t.x, t.z)] else 0

    /** Average player threat over an axis-aligned box (a field's zone) — cheap, sampled. */
    fun averageThreat(x0: Int, z0: Int, x1: Int, z1: Int): Int {
        var sum = 0; var n = 0
        val step = 2 // sample every other tile to keep it cheap
        var z = z0
        while (z <= z1) {
            var x = x0
            while (x <= x1) { sum += threatAt(x, z); n++; x += step }
            z += step
        }
        return if (n > 0) sum / n else 0
    }

    private companion object {
        const val UNREACHED = Int.MAX_VALUE
        const val INF_RADIUS = 3
        const val THREAT_RADIUS = 4
        val NEIGHBORS = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
            intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1),
        )
    }
}
