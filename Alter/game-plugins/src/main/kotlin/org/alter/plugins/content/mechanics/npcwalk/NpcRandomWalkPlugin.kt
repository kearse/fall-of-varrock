package org.alter.plugins.content.mechanics.npcwalk

import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.stepTo
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import kotlin.math.abs
import kotlin.math.max

class NpcRandomWalkPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        val routeSearchTimer = TimerKey()
        val routeSearchDelay = 15..30 // TODO: Find a way to override this for certain NPCs?

        onGlobalNpcSpawn {
            if (npc.walkRadius > 0) {
                npc.timers[routeSearchTimer] = world.random(routeSearchDelay)
            }
        }

        onTimer(routeSearchTimer) {
            // Cheap wander: ONE random adjacent step, collision-checked, with NO route-finder. The
            // old version called walkTo() — a full path search to a tile up to walkRadius away. Across
            // every roaming mob that route-search churn was the dominant NpcCycleTask cost (and what
            // spiked the tick). A single step looks the same in-game: they still drift around their
            // spawn within walkRadius, just without pathfinding to get there.
            if (npc.isActive() && npc.lock.canMove() && !npc.hasMoveDestination()) {
                val facing = npc.attr[FACING_PAWN_ATTR]?.get()

                // Only drift while not squared up to a player.
                if (facing == null) {
                    val dir = Direction.RS_ORDER[world.random(Direction.RS_ORDER.indices)]
                    val dest = npc.tile.transform(dir.getDeltaX(), dir.getDeltaZ())
                    val home = npc.spawnTile
                    val withinRadius = max(abs(dest.x - home.x), abs(dest.z - home.z)) <= npc.walkRadius

                    // Step only onto an in-radius, loaded, traversable tile (a single collision check —
                    // no search). stepTo() queues the one step; an illegal step would self-clear anyway.
                    if (withinRadius &&
                        world.chunks.get(dest, createIfNeeded = false) != null &&
                        world.canTraverse(npc.tile, dir, npc)
                    ) {
                        npc.stepTo(dest)
                    }
                }
            }

            npc.timers[routeSearchTimer] = world.random(routeSearchDelay)
        }
    }
}
