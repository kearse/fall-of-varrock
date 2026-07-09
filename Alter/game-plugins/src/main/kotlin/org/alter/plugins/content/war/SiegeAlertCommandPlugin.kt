package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Test/trigger command for the client-side "Fall of Varrock Alerts" plugin.
 *
 * Toggles [ALERT_VARP]; the custom RuneLite client watches this varp and
 * shows an on-screen banner + notification when it becomes non-zero. The
 * real War Brain can raise the same varp when a raid/siege begins.
 *
 * NOTE: [ALERT_VARP] must match the client plugin's `alertVarp` config.
 */
class SiegeAlertCommandPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        const val ALERT_VARP = 4600
    }

    init {
        // The alert is a momentary EVENT: pulse the varp to 1 (the client banner is
        // duration-timed) then reset to 0 so it never persists. Persisting it would
        // re-fire the banner on every login (VarpSerialisation saves non-zero varps).
        onCommand("siegealert", description = "Pulse the client siege-alert banner") {
            if (ALERT_VARP >= player.varps.maxVarps) {
                player.message("Alert varp $ALERT_VARP is out of range (max ${player.varps.maxVarps}).")
                return@onCommand
            }
            pulseAlert(player)
            player.message("Siege alert pulsed (varp $ALERT_VARP).")
        }

        // Clear any stale persisted alert on login so it can't re-fire the banner.
        onLogin {
            if (ALERT_VARP < player.varps.maxVarps && player.getVarp(ALERT_VARP) != 0) {
                player.setVarp(ALERT_VARP, 0)
            }
        }
    }

    private fun pulseAlert(player: org.alter.game.model.entity.Player) {
        player.setVarp(ALERT_VARP, 1)
        player.queue {
            wait(2)
            player.setVarp(ALERT_VARP, 0)
        }
    }
}
