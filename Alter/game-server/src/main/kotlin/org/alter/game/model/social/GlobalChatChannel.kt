package org.alter.game.model.social

import net.rsprot.protocol.game.outgoing.friendchat.MessageFriendChannel
import net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFull
import net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFullV2
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server-wide chat-channel that every player is placed in on login. The Chat-channel
 * tab doubles as an online list: the roster is rebuilt from [World.players] whenever
 * someone logs in or out, so the member list (and its size) always mirrors who is online.
 */
object GlobalChatChannel {
    /** Shown as "Talking in:". Base-37 constrained: 12 chars max, letters/digits/spaces only. */
    const val CHANNEL_NAME = "Varrock"

    /** Shown as "Owner:". Plain string, so the full server name fits here. */
    const val CHANNEL_OWNER = "Fall of Varrock"

    private const val WORLD_ID = 1

    /** Members are unranked, so no rank ever reaches this and the kick option never shows. */
    private const val KICK_RANK = 127
    private const val UNRANKED = -1

    /**
     * 24-bit world-local message counter. Seeded from the hour of the year (per rsprot's
     * guidance) so ids rarely collide across server restarts.
     */
    private val messageCounter = AtomicInteger(((System.currentTimeMillis() / 3_600_000L) % 8760L).toInt() * 50_000)

    /**
     * Push the current online roster to every online player. Pass [exclude] for the player
     * currently logging out: [Player.handleLogout] runs plugins before the world unregisters
     * them, so they would otherwise still appear in the list.
     */
    fun refresh(
        world: World,
        exclude: Player? = null,
    ) {
        val members = onlineMembers(world, exclude)
        val update = buildUpdate(members)
        members.forEach { it.write(update) }
    }

    /** Re-send the current roster to a single player (e.g. after they click "Leave chat"). */
    fun sendTo(player: Player) {
        player.write(buildUpdate(onlineMembers(player.world)))
    }

    private fun buildUpdate(members: List<Player>): UpdateFriendChatChannelFullV2 {
        val entries =
            members.map {
                UpdateFriendChatChannelFull.FriendChatEntry(it.username, WORLD_ID, UNRANKED, "")
            }
        return UpdateFriendChatChannelFullV2(
            UpdateFriendChatChannelFullV2.JoinUpdate(CHANNEL_OWNER, CHANNEL_NAME, KICK_RANK, entries),
        )
    }

    /**
     * Relay a "/"-prefixed chat message from [sender] to every online player in the channel.
     */
    fun sendMessage(
        sender: Player,
        message: String,
    ) {
        val packet =
            MessageFriendChannel(
                sender = sender.username,
                channelName = CHANNEL_NAME,
                worldId = WORLD_ID,
                worldMessageCounter = messageCounter.getAndIncrement() and 0xFFFFFF,
                chatCrownType = sender.privilege.icon,
                message = message,
            )
        onlineMembers(sender.world).forEach { it.write(packet) }
    }

    /** Clientless bots share [World.players] with real users; only humans belong in the roster. */
    private fun onlineMembers(
        world: World,
        exclude: Player? = null,
    ): List<Player> {
        val members = mutableListOf<Player>()
        world.players.forEach {
            if (it.initiated && it.entityType.isHumanControlled && it !== exclude) {
                members.add(it)
            }
        }
        return members
    }
}
