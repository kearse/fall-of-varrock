package org.alter.plugins.content.mechanics.saving

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.PlayerSaving

private val logger = KotlinLogging.logger {}

/**
 * Player-persistence safety net. The engine otherwise saves a character ONLY on logout
 * ([Client.handleLogout]) — there is no autosave — so a crash or a hard restart loses
 * everything since the player logged in. This plugin adds:
 *  - `::save` — save your own character on the spot, no logout needed.
 *  - a periodic autosave of every online player every [AUTOSAVE_TICKS] ticks (~60s).
 *
 * Saving mid-session is the same `PlayerSaving.savePlayer` call logout uses; it reads
 * the live player (inventory/equipment/stats/attributes) into a per-username file that
 * is overwritten in place — no accumulation, no leak.
 */
class PlayerSavePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("save", Privilege.ADMIN_POWER, description = "Save your character right now") {
            val client = player as? Client
            if (client != null) {
                PlayerSaving.savePlayer(client)
                player.message("<col=4f9b4f>Character saved.</col>")
            }
        }

        val autosaveTimer = TimerKey()
        onWorldInit { world.timers[autosaveTimer] = AUTOSAVE_TICKS }
        onTimer(autosaveTimer) {
            val saved = saveAllOnline()
            if (saved > 0) logger.info { "Autosaved $saved online player(s)." }
            world.timers[autosaveTimer] = AUTOSAVE_TICKS
        }
    }

    /** Save every online player; one failure never aborts the rest. Returns the count saved. */
    private fun saveAllOnline(): Int {
        var count = 0
        world.players.forEach { p ->
            val client = p as? Client ?: return@forEach
            try {
                PlayerSaving.savePlayer(client)
                count++
            } catch (e: Exception) {
                logger.error(e) { "Autosave failed for ${client.loginUsername}" }
            }
        }
        return count
    }

    private companion object {
        const val AUTOSAVE_TICKS = 100 // 100 ticks * 0.6s ≈ 60s
    }
}
