package org.alter.plugins.content.interfaces.panel

import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Routes clicks for the reusable [TabbedPanel] interface back into the open spec. Bindings are on
 * the transparent HIT LAYERS (the clickable type-0 components), not the visual text: close, the
 * tab hit layers, and the row hit layers.
 */
class PanelPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onButton(TabbedPanel.IFACE, TabbedPanel.CLOSE_BTN) { TabbedPanel.close(player) }
        onButton(TabbedPanel.IFACE, TabbedPanel.CLOSE_HIT) { TabbedPanel.close(player) }

        for (t in 0 until TabbedPanel.TAB_COUNT) {
            onButton(TabbedPanel.IFACE, TabbedPanel.TAB_HIT_BASE + t) { TabbedPanel.selectTab(player, t) }
        }

        for (i in 0 until TabbedPanel.ROWS) {
            onButton(TabbedPanel.IFACE, TabbedPanel.ROW_HIT_BASE + i) { TabbedPanel.clickRow(player, i) }
        }

        onInterfaceClose(TabbedPanel.IFACE) { TabbedPanel.onClosed(player) }
    }
}
