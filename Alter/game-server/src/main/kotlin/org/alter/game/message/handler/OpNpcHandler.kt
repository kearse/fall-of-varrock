package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.npcs.OpNpc
import org.alter.game.model.move.PawnPathAction
import org.alter.game.message.MessageHandler
import org.alter.game.model.attr.CLIENT_KEY_COMBINATION
import org.alter.game.model.attr.INTERACTING_NPC_ATTR
import org.alter.game.model.attr.INTERACTING_OPT_ATTR
import org.alter.game.model.entity.Client
import java.lang.ref.WeakReference

class OpNpcHandler : MessageHandler<OpNpc> {
    override fun consume(
        client: Client,
        message: OpNpc,
    ) {
        val npc = client.world.npcs[message.index] ?: return
        log(client, "Npc option %d: index=%d, movement=%b, npc=%s", message.op, message.index, message.controlKey, npc)
        client.attr[CLIENT_KEY_COMBINATION] = if (message.controlKey) 2 else 0
        if (message.op == 2) {
            client.attack(npc)
        } else {
            // Any world click dismisses an open modal (OpLocHandler already does this for
            // objects). Without it a modal the server still considers open — one the client
            // dropped on its own, say — keeps every STANDARD queue task parked
            // (PawnQueueTaskSet.hasMenuOpen), and the npc-option walk below is such a task:
            // the click looks completely dead ("can't poke Vorkath") while Attack, which
            // bypasses the queue, still works.
            client.closeInterfaceModal()
            client.attr[INTERACTING_OPT_ATTR] = message.op
            client.attr[INTERACTING_NPC_ATTR] = WeakReference(npc)
            client.executePlugin(PawnPathAction.walkPlugin)
        }
    }
}
