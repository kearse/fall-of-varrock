package org.alter.plugins.content.commands.commands.admin

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce

/**
 * @author Fritz <frikkipafi@gmail.com>
 */
class BroadcastPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        // Command args are split on spaces — re-join them so the whole sentence goes out (this
        // used to send only the first word).
        onCommand("broadcast", Privilege.ADMIN_POWER, description = "Broadcast for everyone") {
            val text = player.getCommandArgs().joinToString(" ").trim()
            if (text.isNotEmpty()) Announce.broadcast(world, text)
            else player.message("Usage: ::broadcast <message>")
        }

        // Full-sentence headline announcement (drives the client announcement ticker). Command args
        // are split on spaces, so re-join them. e.g. `::announce A Goblin King has spawned in Varrock!`
        onCommand("announce", Privilege.ADMIN_POWER, description = "Send a headline announcement to everyone") {
            val text = player.getCommandArgs().joinToString(" ").trim()
            if (text.isNotEmpty()) Announce.broadcast(world, text)
            else player.message("Usage: ::announce <message>")
        }
    }
}
