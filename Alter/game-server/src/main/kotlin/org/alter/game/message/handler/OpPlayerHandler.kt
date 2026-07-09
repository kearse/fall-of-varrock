package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.players.OpPlayer
import org.alter.game.model.move.PawnPathAction
import org.alter.game.message.MessageHandler
import org.alter.game.model.attr.INTERACTING_OPT_ATTR
import org.alter.game.model.attr.INTERACTING_PLAYER_ATTR
import org.alter.game.model.entity.Client
import java.lang.ref.WeakReference

/**
 * @author Triston Plummer ("Dread")
 */
class OpPlayerHandler : MessageHandler<OpPlayer> {
    override fun consume(
        client: Client,
        message: OpPlayer,
    ) {
        val index = message.index
        // The interaction option id. The client returns this as the 0-based player-option SLOT index
        // it was assigned via SetPlayerOp — so it IS the index into [options]; do NOT subtract 1.
        val option = message.op
        val optionIndex = option

        if (!client.lock.canPlayerInteract()) {
            return
        }

        val other = client.world.players[index] ?: return

        if (client.options.getOrNull(optionIndex) == null || other == client) {
            return
        }

        // (debug packet log removed — was spamming "Player option: ..." into the game chat)

        client.closeInterfaceModal()
        client.interruptQueues()
        client.resetInteractions()

        client.attr[INTERACTING_PLAYER_ATTR] = WeakReference(other)
        client.attr[INTERACTING_OPT_ATTR] = option
        client.executePlugin(PawnPathAction.walkPlugin)
    }
}
