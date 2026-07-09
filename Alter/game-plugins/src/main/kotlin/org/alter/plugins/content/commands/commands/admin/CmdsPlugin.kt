package org.alter.plugins.content.commands.commands.admin

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*

class CmdsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {

        onCommand(command = "cmds", powerRequired = Privilege.ADMIN_POWER, description = "Display commands") {
            // The custom command-list interface (187) was never finished and the body
            // threw TODO("CMDS") at runtime. Dump the registered commands to chat instead —
            // reliable and doesn't depend on unverified cache client-scripts.
            val commands = getCommands(world.plugins)
            player.message("<col=801700>Registered commands (${commands.size}):</col>")
            commands.sorted().forEach { player.message(it) }
        }
    }

    private fun getCommands(repo: PluginRepository): List<String> {
        val strList = ArrayList<String>()
        repo.commandPlugins.forEach { (t, _) ->
            var value = "::$t"
            val desc = repo.getDescription(t)
            if (!desc.isNullOrEmpty()) {
                value += " = [ $desc ]"
            }
            strList.add(value)
        }
        return strList
    }
}
