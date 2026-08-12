package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.friendchat.FriendChatJoinLeave
import org.alter.game.message.MessageHandler
import org.alter.game.model.entity.Client
import org.alter.game.model.social.GlobalChatChannel

/**
 * @author Tom <rspsmods@gmail.com>
 */
class ClanJoinChatLeaveHandler : MessageHandler<FriendChatJoinLeave> {
    override fun consume(
        client: Client,
        message: FriendChatJoinLeave,
    ) {
        // Everyone shares the server-wide channel, so both "Join chat" and "Leave chat"
        // simply re-assert membership: push the roster back to this client.
        client.writeMessage("Everyone on ${GlobalChatChannel.CHANNEL_OWNER} shares the '${GlobalChatChannel.CHANNEL_NAME}' chat-channel.")
        GlobalChatChannel.sendTo(client)
    }
}
