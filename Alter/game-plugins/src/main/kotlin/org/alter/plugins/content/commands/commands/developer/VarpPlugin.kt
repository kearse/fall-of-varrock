package org.alter.plugins.content.commands.commands.developer

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

class VarpPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onCommand("varp", Privilege.DEV_POWER, description = "Set varp to amount") {
            val args = player.getCommandArgs()
            val varp = args[0].toInt()
            val state = args[1].toInt()
            val oldState = player.getVarp(varp)
            player.setVarp(varp, state)
            player.message("Set varp (<col=801700>$varp</col>) from <col=801700>$oldState</col> to <col=801700>${player.getVarp(varp)}</col>")
        }

        onCommand("getvarp", Privilege.DEV_POWER) {
            val args = player.getCommandArgs()
            val varpState = player.getVarp(args[0].toInt())
            player.message("${args[0]} Varp state: $varpState")
        }

        // Diagnostic for the custom-overlay varp range (4600-4680+, docs/overlay-design-system.md
        // §8): the table used to be sized only from the cache's varp count, and an id past it
        // THROWS in VarpSet.setState — which silently killed the sparring settings screen and the
        // kit editor's slot varps. One glance at these two numbers settles that class of bug.
        onCommand("varpmax", Privilege.ADMIN_POWER, description = "Show the varp table size vs the cache's varp count") {
            player.message(
                "Varp table: <col=801700>${player.varps.maxVarps}</col> entries " +
                    "(cache defines <col=801700>${dev.openrune.cache.CacheManager.varpSize()}</col>; " +
                    "custom overlays claim up to 4680).",
            )
        }
    }
}
