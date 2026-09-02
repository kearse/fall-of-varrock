package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Player-facing economy commands. Currently the reward-point balances readout; the
 * daily/vote and other economy systems will register here as they land.
 */
class EconomyPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("points", description = "Show your reward-point balances") {
            player.message("--- Your reward points ---")
            PointKind.values().forEach { kind ->
                val note = if (kind.spendable) "" else " <col=808080>(lifetime service record — never spent; ::service)</col>"
                player.message("${kind.display}: <col=801700>${player.points(kind)}</col>$note")
            }
        }
    }
}
