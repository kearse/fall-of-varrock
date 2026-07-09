package org.alter.plugins.content.teleport

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles a teleport requested by the client overlay. The client sends `::tp <cat> <row>`
 * as public chat; `MessagePublicHandler` routes it here as the `tpclick` command with args
 * [categoryOrdinal, rowIndex]. Indices map to [TeleportCategory] order + [TeleportRegistry]
 * per-category order (mirrored client-side).
 *
 * Also testable directly by typing `::tpclick <cat> <row>` in chat (the command path).
 */
class TeleportClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("tpclick", description = "Teleport by category+row index (client overlay channel)") {
            val a = player.getCommandArgs()
            click(player, a.getOrNull(0)?.toIntOrNull(), a.getOrNull(1)?.toIntOrNull())
        }
    }

    private fun click(p: Player, categoryOrdinal: Int?, rowIndex: Int?) {
        if (categoryOrdinal == null || rowIndex == null) return
        val cat = TeleportCategory.values().getOrNull(categoryOrdinal) ?: return
        val dest = TeleportRegistry.inCategory(cat).getOrNull(rowIndex) ?: return
        TeleportService.teleport(p, dest)
    }
}
