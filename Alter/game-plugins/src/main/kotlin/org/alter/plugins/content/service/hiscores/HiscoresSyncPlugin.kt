package org.alter.plugins.content.service.hiscores

import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.hiscores.HiscoresSync
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Drives [HiscoresSync]: every real player's hiscores slice is pushed to Mongo on logout
 * (the authoritative write — same moment the save file is written) and on a periodic sweep
 * of everyone online, so the website leaderboard tracks live play instead of the frozen
 * one-shot migration snapshot it used to show.
 */
class HiscoresSyncPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogout { HiscoresSync.sync(player) }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = INTERVAL }
        onTimer(timer) {
            world.players.forEach { p -> if (p is Client) HiscoresSync.sync(p) }
            world.timers[timer] = INTERVAL
        }
    }

    private companion object {
        /** Sweep cadence in ticks (500 = ~5 min) — logout is the precise write; this covers long sessions. */
        const val INTERVAL = 500
    }
}
