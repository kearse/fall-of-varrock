package org.alter.plugins.content.commands.commands.admin

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Recovery hatch for a client that has drifted out of sync with the server.
 *
 * Varps and skills are pushed to the client only while their dirty flag is set, and the flag
 * is cleared the moment the packet is written. If a write is ever lost, that value is simply
 * never sent again — the client sits on whatever it last knew, or on its defaults. The
 * signature case is a player showing every skill at 0 with "Total level: 0" and the chatbox
 * refusing input with "You must set a name before you can chat" (varbit 8119 reading 0):
 * nothing is wrong with the account, its client just never received the state.
 *
 * `::resync` re-marks the player's entire varp and skill state dirty so the next cycle
 * re-sends all of it. `::resync <name>` does the same for another online player, which is
 * how you fix a stuck player without making them relog.
 */
class ResyncPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("resync", Privilege.ADMIN_POWER, description = "Re-send all varps and stats to a client") {
            val args = player.getCommandArgs()
            val target =
                if (args.isEmpty()) {
                    player
                } else {
                    val name = args.joinToString(" ")
                    world.getPlayerForName(name) ?: run {
                        player.message("No online player named '$name'.")
                        return@onCommand
                    }
                }

            target.markClientStateDirty()
            if (target == player) {
                player.message("<col=4f9b4f>Re-syncing your varps and stats.</col>")
            } else {
                player.message("<col=4f9b4f>Re-syncing varps and stats for ${target.username}.</col>")
            }
        }
    }
}
