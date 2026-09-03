package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Admin test surface for [Veteran]: `::veteran` shows your mark, `::veteran grant [name]` /
 * `::veteran revoke [name]` set or clear it (names with underscores for spaces). No content awards
 * the mark yet — this is how Block-2 quest work is verified before the story event exists.
 */
class VeteranPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("veteran", Privilege.ADMIN_POWER, description = "Veteran of Varrock mark (test): ::veteran [grant|revoke] [name]") {
            val a = player.getCommandArgs()
            val verb = a.getOrNull(0)?.lowercase()
            val name = a.getOrNull(1)?.replace('_', ' ')
            val target = if (name == null) player else world.getPlayerForName(name)
            if (target == null) {
                player.message("<col=801700>No player named '$name' is online.</col>")
                return@onCommand
            }
            when (verb) {
                "grant" -> player.message(
                    if (Veteran.award(target, "granted by ${player.username} (test)")) "<col=4f9b4f>[test] ${target.username} is now a Veteran of Varrock.</col>"
                    else "${target.username} already holds the Veteran of Varrock mark.",
                )
                "revoke" -> player.message(
                    if (Veteran.revoke(target)) "<col=4f9b4f>[test] ${target.username}'s Veteran of Varrock mark revoked.</col>"
                    else "${target.username} does not hold the Veteran of Varrock mark.",
                )
                else -> player.message("${target.username}: Veteran of Varrock = <col=ffae00>${Veteran.has(target)}</col>. Usage: ::veteran [grant|revoke] [name]")
            }
        }
    }
}
