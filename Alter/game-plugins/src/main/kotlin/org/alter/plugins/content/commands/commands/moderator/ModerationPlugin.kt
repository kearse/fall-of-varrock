package org.alter.plugins.content.commands.commands.moderator

import net.rsprot.protocol.game.outgoing.logout.Logout
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.PlayerModeration

/**
 * Staff moderation tools: kick, ban, mute, and the closed-beta whitelist.
 *
 * Names with spaces are passed with underscores (`::ban some_name reason`).
 * Bans and mutes persist in the `moderation` collection and are keyed by login
 * username; bans are enforced at login (see PlayerSaving.loadPlayer), mutes in
 * the public-chat handler and ::yell.
 */
class ModerationPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("kick", Privilege.MOD_POWER, description = "Disconnect a player: ::kick <name>") {
            val target = onlineTarget(player, targetName(player) ?: return@onCommand) ?: return@onCommand
            if (protected(player, target)) return@onCommand
            disconnect(target)
            player.message("Kicked ${target.username}.")
        }

        onCommand("ban", Privilege.MOD_POWER, description = "Ban an account: ::ban <name> [reason]") {
            val name = targetName(player) ?: return@onCommand
            val online = world.getPlayerForName(name)
            if (online != null && protected(player, online)) return@onCommand
            if (online == null && isConfiguredOwner(name)) {
                player.message("You can't ban an owner.")
                return@onCommand
            }
            val reason = player.getCommandArgs().drop(1).joinToString(" ").trim()
            PlayerModeration.setBanned(loginName(online) ?: name, banned = true, reason = reason)
            online?.let { disconnect(it) }
            player.message("Banned $name${if (reason.isEmpty()) "" else " ($reason)"}.")
        }

        onCommand("unban", Privilege.MOD_POWER, description = "Lift a ban: ::unban <name>") {
            val name = targetName(player) ?: return@onCommand
            PlayerModeration.setBanned(name, banned = false)
            player.message("Unbanned $name.")
        }

        onCommand("bans", Privilege.MOD_POWER, description = "List banned accounts") {
            val banned = PlayerModeration.bannedNames()
            if (banned.isEmpty()) player.message("No accounts are banned.")
            else player.message("Banned: ${banned.joinToString(", ")}")
        }

        onCommand("mute", Privilege.MOD_POWER, description = "Mute an account: ::mute <name> [hours]") {
            val name = targetName(player) ?: return@onCommand
            val online = world.getPlayerForName(name)
            if (online != null && protected(player, online)) return@onCommand
            val hours = player.getCommandArgs().getOrNull(1)?.toLongOrNull()
            val until = if (hours == null || hours <= 0) -1L else System.currentTimeMillis() + hours * 3_600_000
            PlayerModeration.setMuted(loginName(online) ?: name, until)
            online?.message("You have been muted.")
            player.message("Muted $name${if (hours != null && hours > 0) " for $hours hour(s)" else " until unmuted"}.")
        }

        onCommand("unmute", Privilege.MOD_POWER, description = "Lift a mute: ::unmute <name>") {
            val name = targetName(player) ?: return@onCommand
            PlayerModeration.setMuted(name, 0)
            world.getPlayerForName(name)?.message("You have been unmuted.")
            player.message("Unmuted $name.")
        }

        onCommand(
            "whitelist",
            Privilege.ADMIN_POWER,
            description = "Closed-beta whitelist: ::whitelist <add|remove|list> [name]",
        ) {
            val args = player.getCommandArgs()
            val name = args.getOrNull(1)?.replace("_", " ")
            when (args.getOrNull(0)?.lowercase()) {
                "add" -> {
                    if (name == null) return@onCommand player.message("Usage: ::whitelist add <name>")
                    PlayerModeration.setWhitelisted(name, true)
                    player.message("Whitelisted $name.")
                }
                "remove" -> {
                    if (name == null) return@onCommand player.message("Usage: ::whitelist remove <name>")
                    PlayerModeration.setWhitelisted(name, false)
                    player.message("Removed $name from the whitelist.")
                }
                "list" -> {
                    val names = PlayerModeration.whitelistedNames()
                    val state = if (world.gameContext.whitelistOnly) "ON" else "off"
                    if (names.isEmpty()) player.message("Whitelist (whitelist-only: $state) is empty.")
                    else player.message("Whitelist (whitelist-only: $state): ${names.joinToString(", ")}")
                }
                else -> player.message("Usage: ::whitelist <add|remove|list> [name]")
            }
        }
    }

    private fun targetName(player: Player): String? {
        val name = player.getCommandArgs().getOrNull(0)?.replace("_", " ")?.trim()
        if (name.isNullOrEmpty()) {
            player.message("You must name a player (use underscores for spaces, e.g. ::mute some_name).")
            return null
        }
        return name
    }

    private fun onlineTarget(player: Player, name: String): Player? {
        val target = world.getPlayerForName(name)
        if (target == null) {
            player.message("$name is not online.")
        }
        return target
    }

    private fun loginName(target: Player?): String? = (target as? Client)?.loginUsername ?: target?.username

    private fun isConfiguredOwner(name: String) =
        name.lowercase() in world.gameContext.owners.map { it.lowercase() }

    /** Staff can't be kicked/banned/muted by other staff — demote them in game.yml first. */
    private fun protected(actor: Player, target: Player): Boolean {
        if (target === actor) {
            actor.message("You can't target yourself.")
            return true
        }
        if (target.privilege.powers.contains(Privilege.MOD_POWER) || isConfiguredOwner(loginName(target)!!)) {
            actor.message("${target.username} is staff and can't be targeted.")
            return true
        }
        return false
    }

    private fun disconnect(target: Player) {
        target.requestLogout()
        target.write(Logout)
        target.session?.requestClose()
        target.channelFlush()
    }
}
