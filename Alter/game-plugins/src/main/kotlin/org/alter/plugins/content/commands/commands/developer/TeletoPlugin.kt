package org.alter.plugins.content.commands.commands.developer

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * `::teleto <name>` — jump to an online player's tile. Names with spaces are
 * passed with underscores (`::teleto player_b`), same as the moderation
 * commands. Pairs with `::invisible` for shadowing a player unseen.
 */
class TeletoPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("teleto", Privilege.DEV_POWER, description = "Teleport to a player: ::teleto <name>") {
            val name = player.getCommandArgs().getOrNull(0)?.replace("_", " ")?.trim()
            if (name.isNullOrEmpty()) {
                player.message("Usage: ::teleto <name> (underscores for spaces)")
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
            player.moveTo(target.tile)
            player.message("Teleported to ${target.username} (${target.tile.x}, ${target.tile.z}, ${target.tile.height}).")
        }
    }
}
