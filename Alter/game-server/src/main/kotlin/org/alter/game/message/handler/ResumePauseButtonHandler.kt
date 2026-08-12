package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.resumed.ResumePauseButton
import net.rsprot.protocol.game.outgoing.interfaces.IfCloseSub
import org.alter.game.message.MessageHandler
import org.alter.game.model.entity.Client

/**
 * @author Tom <rspsmods@gmail.com>
 */
class ResumePauseButtonHandler : MessageHandler<ResumePauseButton> {
    override fun consume(
        client: Client,
        message: ResumePauseButton,
    ) {
        log(client, "Continue dialog: component=[%d:%d], slot=%d", message.interfaceId, message.componentId, message.sub)
        val consumed = client.queues.submitReturnValue(message)
        if (!consumed && client.interfaces.getInterfaceAt(CHATBOX_PARENT, CHATBOX_CHILD) == message.interfaceId) {
            // No queued task is waiting on this dialog — it's a fire-and-forget
            // chatbox box (e.g. the non-blocking level-up notification), so a
            // "Click here to continue" should simply dismiss it.
            client.interfaces.close(CHATBOX_PARENT, CHATBOX_CHILD)
            client.write(IfCloseSub(interfaceId = CHATBOX_PARENT, componentId = CHATBOX_CHILD))
        }
    }

    companion object {
        private const val CHATBOX_PARENT = 162
        private const val CHATBOX_CHILD = 566
    }
}
