package org.alter.plugins.content.commands.commands.all

import org.alter.api.ext.message
import org.alter.api.ext.openUrl
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

class DiscordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        const val DISCORD_INVITE_URL = "https://discord.gg/AmtccSBKYz"
    }

    init {
        onCommand("discord", description = "Open the community Discord") {
            player.message("Opening the Fall of Varrock Discord: $DISCORD_INVITE_URL")
            player.openUrl(DISCORD_INVITE_URL)
        }
    }
}
