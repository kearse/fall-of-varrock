package org.alter.plugins.content.teleport

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Handles a teleport requested by the client overlay. The client sends
 * `::loftp <cat> <row> [display name…]` as public chat; `MessagePublicHandler` routes it here
 * as the `tpclick` command. Indices map to [TeleportCategory] order + [TeleportRegistry]
 * per-category order (mirrored client-side in `LofTeleportsData`).
 *
 * **Resolution order: name, then index.** When the client build's mirror has drifted from the
 * server registry (rows inserted mid-list on the server, client not yet redeployed — every
 * Bosses-tab row after Barrows shifted by three on 2026-09-03), the indices point at the wrong
 * boss but the name the player clicked is still right. So a name, when present, wins: first
 * within the sent category, then anywhere in the registry. Old clients that send no name keep
 * the index path. Drift is logged once per (cat,row) so ops can see a stale client build.
 *
 * Also testable directly by typing `::tpclick <cat> <row> [name]` in chat (the command path).
 */
class TeleportClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val driftLogged = HashSet<String>()

    init {
        onCommand("tpclick", description = "Teleport by category+row index (client overlay channel)") {
            val a = player.getCommandArgs()
            val name = if (a.size > 2) a.drop(2).joinToString(" ").trim() else ""
            click(player, a.getOrNull(0)?.toIntOrNull(), a.getOrNull(1)?.toIntOrNull(), name)
        }
    }

    private fun click(p: Player, categoryOrdinal: Int?, rowIndex: Int?, name: String) {
        if (categoryOrdinal == null || rowIndex == null) return
        val cat = TeleportCategory.values().getOrNull(categoryOrdinal)
        val byIndex = cat?.let { TeleportRegistry.inCategory(it).getOrNull(rowIndex) }

        val byName = if (name.isEmpty()) null else {
            (cat?.let { c -> TeleportRegistry.inCategory(c).firstOrNull { it.displayName.equals(name, ignoreCase = true) } }
                ?: TeleportRegistry.all.firstOrNull { it.displayName.equals(name, ignoreCase = true) })
        }

        if (byName != null && byIndex !== byName) {
            val tag = "$categoryOrdinal:$rowIndex"
            if (driftLogged.add(tag)) {
                logger.warn { "teleport-portal: client mirror drift — cat $categoryOrdinal row $rowIndex is '${byIndex?.displayName ?: "nothing"}' server-side but the client sent '$name'; resolved by name. Redeploy the client build." }
            }
        }

        val dest = byName ?: byIndex ?: return
        TeleportService.teleport(p, dest)
    }
}
