package org.alter.plugins.content.bots

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Drives every wilderness [BotZones] zone from one world timer — the spawn/roaming counterpart to
 * the war [org.alter.plugins.content.war.CityFrontierPlugin]. Each [BotColony] lazily maintains its
 * population while a player is near and despawns when empty; the per-bot NH behaviour + roaming runs
 * separately in [BotBrain] (via `BotCombatPlugin`'s per-tick heartbeat).
 *
 * Adding a zone is data-only: append a [BotZoneConfig] to [BotZones.all].
 */
class BotSpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val colonies = BotZones.all.map { BotColony(it) }
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            colonies.forEach { it.tick(world) }
            world.timers[timer] = TICK
        }
    }

    private companion object {
        const val TICK = 5 // ~3s population upkeep, matching the hostile frontiers
    }
}
