package org.alter.plugins.content.announce

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.game.model.World

/**
 * **Server-wide announcements** — the curated headline channel that drives the client's
 * announcement ticker (the roak-style banner above the chat box).
 *
 * Anything sent through here goes out as a [ChatMessageType.BROADCAST] chat line. The custom
 * client's `lofannouncements` plugin listens for BROADCAST messages and surfaces the most
 * recent ones in the on-screen ticker; on any other client they just appear as normal broadcast
 * chat. Keep this for *headlines only* (boss spawns, campaign captures, votes, events) so the
 * ticker stays signal, not noise — ordinary war/skill chatter should use plain `message(...)`.
 */
object Announce {

    /** Broadcast [text] to every online player as a headline announcement. */
    fun broadcast(world: World, text: String) {
        world.players.forEach { it.message(text, ChatMessageType.BROADCAST) }
    }
}
