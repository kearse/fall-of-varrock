package org.alter.plugins.content.interfaces.gameframe.tabs.chat_channel

import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.social.GlobalChatChannel
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Keeps the Chat-channel tab populated with every online player, so the member list acts
 * as a live "who's online" panel. The roster is re-broadcast whenever a player logs in or
 * out; clientless bots never trigger a refresh because they can't change the human roster.
 */
class GlobalChatChannelPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            if (player.entityType.isHumanControlled) {
                GlobalChatChannel.refresh(player.world)
            }
        }

        onLogout {
            if (player.entityType.isHumanControlled) {
                GlobalChatChannel.refresh(player.world, exclude = player)
            }
        }
    }
}
