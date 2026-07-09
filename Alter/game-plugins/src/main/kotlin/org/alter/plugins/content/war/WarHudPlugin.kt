package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Retired:** the bottom-left "Lumbridge siege status" HUD has been removed.
 *
 * The war redesign dropped defensive sieges (war is now player-offense boss raids/campaigns),
 * so the siege status bar no longer has anything to show. The client overlay
 * (`net.runelite.client.plugins.warhud`) has been deleted; this plugin no longer publishes the
 * packed war-state varp (formerly 4601). Only a read-only diagnostic command is kept so the war
 * state is still inspectable in-game. Safe to delete entirely once the dust settles.
 */
class WarHudPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("warhud", Privilege.ADMIN_POWER, description = "Report the current war state (HUD retired)") {
            val front = WarStatePlugin.FRONTIER_NORTH
            val s = WarState.raidStatus(front)
            player.message("War state: phase=${WarState.phaseOf(front)} tier='${s.tierName}' goblins=${s.goblinsAlive}/${s.peakRoster}")
        }
    }
}
