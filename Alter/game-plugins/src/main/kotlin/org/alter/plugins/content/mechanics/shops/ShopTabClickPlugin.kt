package org.alter.plugins.content.mechanics.shops

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles a storefront tab click from the client overlay (lofshoptabs). The client sends
 * `::shoptab <index>` as public chat; `MessagePublicHandler` routes it here as the
 * `shoptabclick` command. [ShopTabs.switch] validates the index against the tab set the
 * vendor actually offered (a non-persistent attr), so this can't open arbitrary shops.
 *
 * Also testable directly by typing `::shoptabclick <index>` in chat (the command path).
 */
class ShopTabClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("shoptabclick", description = "Switch storefront tab (client overlay channel)") {
            val index = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            ShopTabs.switch(player, index)
        }
    }
}
