package org.alter.plugins.content.hunt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Drives [TargetMarker]'s sweep on a world timer. Marker sources (the Rogue Knight ladder now,
 * bounty hunter later) register with [TargetMarker.register] from their own plugin inits — the
 * registry is a static object, so plugin load order never matters.
 */
class TargetMarkerPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            try {
                TargetMarker.tick(world)
            } catch (e: Exception) {
                logger.error(e) { "Target marker sweep failed (skipped)." }
            }
            world.timers[timer] = TICK
        }
    }

    private companion object {
        /** Sweep cadence (game ticks) — matches the knight-camp upkeep, so arrow flips stay in step. */
        const val TICK = 5
    }
}
