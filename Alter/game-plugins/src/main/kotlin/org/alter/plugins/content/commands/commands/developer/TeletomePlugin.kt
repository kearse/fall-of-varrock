package org.alter.plugins.content.commands.commands.developer

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.PluginRepository
import org.alter.game.plugin.KotlinPlugin

/**
 * `::teletome <name>` — pull an online player to your tile. The reverse of
 * `::teleto`. Names with spaces are passed with underscores
 * (`::teletome player_b`), same as the moderation commands.
 */
class TeletomePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("teletome", Privilege.DEV_POWER, description = "Teleport a player to you: ::teletome <name>") {
            val name = player.getCommandArgs().getOrNull(0)?.replace("_", " ")?.trim()
            if (name.isNullOrEmpty()) {
                player.message("Usage: ::teletome <name> (underscores for spaces)")
                return@onCommand
            }
            val target = world.getPlayerForName(name)
            if (target == null) {
                player.message("$name is not online.")
                return@onCommand
            }
            if (target === player) {
                player.message("You are already at your own tile.")
                return@onCommand
            }
            target.moveTo(player.tile)
            target.message("You have been summoned by ${player.username}.")
            player.message("Teleported ${target.username} to you (${player.tile.x}, ${player.tile.z}, ${player.tile.height}).")
        }
    }
}
