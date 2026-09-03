package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Lifecycle wiring for [WarState] — the war's server-wide persistence layer.
 *
 * - Loads war state on world init.
 * - Periodically flushes dirty state to disk (bounds what a crash can lose to ~60 s).
 * - Flushes on JVM exit: the engine has no save-on-shutdown of its own, so a clean stop between
 *   two timer saves used to drop the last minute of Realm Supplies / march counter.
 */
class WarStatePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // ~60s at 600ms/tick. save() is a no-op unless state is dirty, so a
        // frequent check is cheap; it just bounds how much a crash can lose.
        val saveCheckIntervalTicks = 100
        val warStateSaveTimer = TimerKey()

        onWorldInit {
            WarState.load()
            world.timers[warStateSaveTimer] = saveCheckIntervalTicks
        }

        onTimer(warStateSaveTimer) {
            WarState.save()
            world.timers[warStateSaveTimer] = saveCheckIntervalTicks
        }

        // Only after a successful load — a boot that died before load() must never overwrite the
        // real file with a fresh, empty state. save() never throws.
        Runtime.getRuntime().addShutdownHook(
            Thread({
                if (WarState.isLoaded) {
                    WarState.save(force = true)
                    logger.info { "War state flushed on shutdown." }
                }
            }, "war-state-shutdown-save"),
        )
    }
}
