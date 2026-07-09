package org.alter.plugins.content.teleport

import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Binds the click handlers for the teleport interface ([TeleportInterface]) — the
 * close button, the nine category tabs, and each row's clickable name. Bindings are
 * keyed by (interface, component); the slot is irrelevant since each tab/row is its
 * own component.
 */
class TeleportInterfacePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onButton(TeleportInterface.IFACE, TeleportInterface.CLOSE_BTN) { TeleportInterface.close(player) }

        for (t in 0 until TeleportInterface.TAB_COUNT) {
            onButton(TeleportInterface.IFACE, TeleportInterface.TAB_BASE + t) { TeleportInterface.selectTab(player, t) }
        }

        for (i in 0 until TeleportInterface.ROWS) {
            onButton(TeleportInterface.IFACE, TeleportInterface.rowName(i)) { TeleportInterface.clickRow(player, i) }
        }
    }
}
