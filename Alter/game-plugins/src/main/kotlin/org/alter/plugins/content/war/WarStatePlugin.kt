package org.alter.plugins.content.war

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Lifecycle wiring for [WarState] — the war's server-wide persistence layer.
 *
 * - Loads war state on world init.
 * - Periodically flushes dirty state to disk (the engine has no save-on-shutdown,
 *   so we rely on a timer plus forced saves on meaningful events).
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
    }
}
