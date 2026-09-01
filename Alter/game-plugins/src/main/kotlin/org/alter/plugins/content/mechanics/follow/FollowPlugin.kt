package org.alter.plugins.content.mechanics.follow

import org.alter.api.ext.getInteractingPlayer
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.MovementQueue.StepType
import org.alter.game.model.move.stopMovement
import org.alter.game.model.move.walkRoute
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import kotlin.math.abs
import kotlin.math.max

/**
 * The right-click **Follow** player option. The option has been advertised on every player since
 * `OSRSPlugin` registered it (`sendOption("Follow", 3)`), but no handler ever existed — clicking it
 * walked you to the target's tile once and printed "Nothing interesting happens."
 *
 * Following runs as a queue task that re-routes to the target every tick, so it tracks them through
 * corners with real pathfinding. Movement uses [walkRoute] directly (NOT `walkTo` — the Player
 * overload of `walkTo` interrupts the pawn's queues, which would make the loop kill itself on its
 * first step). Cancellation comes free from the queue system: any map click ([walkTo]'s
 * `interruptQueues`), object/npc/player interaction or teleport terminates the task, exactly like
 * every other queued action.
 */
class FollowPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onPlayerOption(option = "Follow") {
            follow(player, player.getInteractingPlayer())
        }
    }

    private fun follow(player: Player, target: Player) {
        player.queue {
            player.facePawn(target)
            while (true) {
                // Target gone (logout, death respawn elsewhere), changed floor, or out of
                // render range -> stop quietly, like OSRS.
                if (target.index < 0 || !player.world.players.contains(target)) break
                if (player.tile.height != target.tile.height || dist(player, target) > MAX_RANGE) break

                if (dist(player, target) > 1) {
                    val route = player.world.smartRouteFinder.findRoute(
                        level = player.tile.height,
                        srcX = player.tile.x,
                        srcZ = player.tile.z,
                        destX = target.tile.x,
                        destZ = target.tile.z,
                        locShape = -2,
                        destWidth = 1,
                        destLength = 1,
                    )
                    player.walkRoute(route, StepType.NORMAL)
                } else {
                    // Beside them — hold position instead of stacking onto their tile.
                    player.stopMovement()
                }
                wait(1)
            }
            player.resetFacePawn()
        }
    }

    private fun dist(a: Player, b: Player): Int =
        max(abs(a.tile.x - b.tile.x), abs(a.tile.z - b.tile.z))

    private companion object {
        /** Give up beyond this Chebyshev distance (the target teleported / outran render). */
        const val MAX_RANGE = 15
    }
}
