package org.alter.game.model.social

import io.github.oshai.kotlinlogging.KotlinLogging
import net.rsprot.protocol.game.outgoing.social.FriendListLoaded
import net.rsprot.protocol.game.outgoing.social.MessagePrivate
import net.rsprot.protocol.game.outgoing.social.MessagePrivateEcho
import net.rsprot.protocol.game.outgoing.social.UpdateFriendList
import net.rsprot.protocol.game.outgoing.social.UpdateIgnoreList
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.saving.PlayerDetails
import org.alter.game.saving.PlayerModeration

/**
 * A player's friends and ignore lists.
 *
 * Both lists hold *login keys* (see [Client.normalizeLogin]) rather than display names, so a
 * friend survives a display-name change; the current display name is resolved through
 * [PlayerDetails] whenever an entry is sent to the client. The lists are persisted with the
 * player's save by `SocialSerialisation`.
 *
 * The client shows "Loading friends list / Please wait..." until it receives its first
 * UPDATE_FRIENDLIST packet, so [pushFriends] must run on every login even when the list is
 * empty ([FriendListLoaded] is that empty packet).
 */
class Social {
    val friends = mutableListOf<String>()
    val ignores = mutableListOf<String>()

    /** Send the whole friends list (initial load). Marks the list as loaded on the client. */
    fun pushFriends(player: Player) {
        if (friends.isEmpty()) {
            player.write(FriendListLoaded)
            return
        }
        val entries = friends.map { friendEntry(player, it, added = false) }
        player.write(UpdateFriendList(entries))
    }

    /** Send the whole ignore list (initial load). */
    fun pushIgnores(player: Player) {
        val entries =
            ignores.map {
                UpdateIgnoreList.AddedIgnoredEntry(displayNameOf(it), null, "", false)
            }
        player.write(UpdateIgnoreList(entries))
    }

    fun addFriend(
        player: Player,
        name: String,
    ) {
        val (key, displayName) = PlayerDetails.resolveAccount(name) ?: run {
            player.writeMessage("Unable to add player; user with this username doesn't exist.")
            return
        }
        if (key == keyOf(player)) {
            player.writeMessage("You can't add yourself to your own friend list.")
            return
        }
        if (friends.contains(key)) {
            return
        }
        if (ignores.contains(key)) {
            player.writeMessage("Please remove $displayName from your ignore list first.")
            return
        }
        if (friends.size >= MAX_FRIENDS) {
            player.writeMessage("Your friend list is full.")
            return
        }
        friends.add(key)
        player.write(UpdateFriendList(listOf(friendEntry(player, key, added = true))))
    }

    fun deleteFriend(
        player: Player,
        name: String,
    ) {
        // The client already dropped the row locally; nothing to send back.
        friends.remove(keyFor(name))
    }

    fun addIgnore(
        player: Player,
        name: String,
    ) {
        val (key, displayName) = PlayerDetails.resolveAccount(name) ?: run {
            player.writeMessage("Unable to ignore player; user with this username doesn't exist.")
            return
        }
        if (key == keyOf(player)) {
            player.writeMessage("You can't add yourself to your own ignore list.")
            return
        }
        if (ignores.contains(key)) {
            return
        }
        if (friends.contains(key)) {
            player.writeMessage("Please remove $displayName from your friend list first.")
            return
        }
        if (ignores.size >= MAX_IGNORES) {
            player.writeMessage("Your ignore list is full.")
            return
        }
        ignores.add(key)
        player.write(UpdateIgnoreList(listOf(UpdateIgnoreList.AddedIgnoredEntry(displayName, null, "", true))))
        // Anyone we just ignored must stop seeing us as online.
        onlinePlayerFor(player.world, key)?.let { ignored ->
            if (ignored.social.friends.contains(keyOf(player))) {
                ignored.write(UpdateFriendList(listOf(ignored.social.friendEntry(ignored, keyOf(player), added = false))))
            }
        }
    }

    fun deleteIgnore(
        player: Player,
        name: String,
    ) {
        val key = keyFor(name)
        if (!ignores.remove(key)) {
            return
        }
        // We're visible to them again.
        onlinePlayerFor(player.world, key)?.let { unignored ->
            if (unignored.social.friends.contains(keyOf(player))) {
                unignored.write(UpdateFriendList(listOf(unignored.social.friendEntry(unignored, keyOf(player), added = false))))
            }
        }
    }

    /**
     * Tell everyone who has [player] on their friends list that their status changed
     * (called after login and after logout). Only the one changed row is sent; the client
     * updates the existing entry in place and prints "X has logged in/out".
     */
    // TODO Add support for private chat modes (off / friends only).
    fun updateStatus(player: Player) {
        if (!player.entityType.isHumanControlled) {
            return
        }
        val key = keyOf(player)
        player.world.players.forEach {
            if (it === player || !it.initiated) {
                return@forEach
            }
            if (it.social.friends.contains(key)) {
                it.write(UpdateFriendList(listOf(it.social.friendEntry(it, key, added = false))))
            }
        }
    }

    fun sendPrivateMessage(
        player: Player,
        targetName: String,
        message: String,
    ) {
        if (message.isBlank()) {
            return
        }
        if (PlayerModeration.isMuted(keyOf(player))) {
            player.writeMessage("You are muted and cannot talk.")
            return
        }
        val target = onlinePlayerFor(player.world, keyFor(targetName))
        if (target == null || target.social.ignores.contains(keyOf(player))) {
            player.writeMessage("Unable to send message - player unavailable.")
            return
        }
        logger.info { "PM ${player.username} -> ${target.username}: $message" }
        target.write(
            MessagePrivate(
                sender = player.username,
                worldId = GlobalChatChannel.WORLD_ID,
                worldMessageCounter = GlobalChatChannel.nextMessageId(),
                chatCrownType = player.privilege.icon,
                message = message,
            ),
        )
        player.write(MessagePrivateEcho(recipient = target.username, message = message))
    }

    /**
     * Build the friend-list row for [key] as seen by [viewer]: online (with our world) when the
     * friend is logged in and hasn't ignored the viewer, otherwise offline.
     */
    private fun friendEntry(
        viewer: Player,
        key: String,
        added: Boolean,
    ): UpdateFriendList.Friend {
        val name = displayNameOf(key)
        val online = onlinePlayerFor(viewer.world, key)
        // Someone who has ignored the viewer always appears offline to them.
        val visible = online != null && !online.social.ignores.contains(keyOf(viewer))
        return if (visible) {
            UpdateFriendList.OnlineFriend(
                added = added,
                name = name,
                previousName = null,
                worldId = GlobalChatChannel.WORLD_ID,
                rank = 0,
                properties = 0,
                notes = "",
                worldName = GlobalChatChannel.CHANNEL_OWNER,
                platform = PLATFORM_OSRS,
                worldFlags = 0,
            )
        } else {
            UpdateFriendList.OfflineFriend(
                added = added,
                name = name,
                previousName = null,
                rank = 0,
                properties = 0,
                notes = "",
            )
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The client's own caps (free / members); we allow the larger one for everyone. */
        const val MAX_FRIENDS = 400
        const val MAX_IGNORES = 400

        /** `platform` value the OSRS client is identified by in friend-list rows. */
        private const val PLATFORM_OSRS = 8

        /** The login key that [player]'s account is stored under. */
        fun keyOf(player: Player): String = (player as? Client)?.loginUsername ?: Client.normalizeLogin(player.username)

        /** The login key for a name the client typed (or a stored display name). */
        fun keyFor(name: String): String = PlayerDetails.resolveAccount(name)?.first ?: Client.normalizeLogin(name)

        fun displayNameOf(key: String): String = PlayerDetails.getDisplayName(key)?.currentDisplayName ?: key

        /** The logged-in human player stored under [key], if any. */
        fun onlinePlayerFor(
            world: World,
            key: String,
        ): Player? {
            var found: Player? = null
            world.players.forEach {
                if (found == null && it.initiated && it.entityType.isHumanControlled && keyOf(it) == key) {
                    found = it
                }
            }
            return found
        }
    }
}
