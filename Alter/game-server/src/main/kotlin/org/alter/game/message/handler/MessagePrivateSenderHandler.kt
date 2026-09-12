package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.messaging.MessagePrivate
import org.alter.game.message.MessageHandler
import org.alter.game.model.entity.Client

class MessagePrivateSenderHandler : MessageHandler<MessagePrivate> {
    override fun consume(
        client: Client,
        message: MessagePrivate,
    ) {
        client.social.sendPrivateMessage(client, message.name, message.message)
    }
}
