package org.alter.plugins.content.bosses

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.npcs.elite.EliteBosses
import org.alter.plugins.content.npcs.godwars.GodWarsBosses
import org.alter.plugins.content.npcs.pvm.PvmBosses
import org.alter.plugins.content.npcs.wilderness.WildernessBosses

/**
 * **Boss teleport commands.** Registers a `::<boss>` command per boss across every roster
 * ([WildernessBosses], [PvmBosses], [GodWarsBosses], [EliteBosses]) that teleports to its lair,
 * plus `::bosses` to list them and `::boss <name>` as a fuzzy fallback. Command names are
 * derived from the npc key (`npc.chaos_elemental_2054` → `chaos_elemental`).
 *
 * This is the reliable, server-side teleport path. The home portal's overlay (the RuneLite
 * `lofteleports` plugin) is driven by the same [TeleportRegistry] but its display list is a
 * **client-side mirror** that must be re-synced + the client rebuilt separately to show new
 * bosses in the window; these commands work regardless.
 */
class BossTeleportCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Dest(val cmd: String, val name: String, val tile: Tile)

    private val dests: List<Dest> = buildList {
        WildernessBosses.all.forEach { add(Dest(cmd(it.key), it.name, it.lair)) }
        PvmBosses.all.forEach { add(Dest(cmd(it.key), it.name, it.lair)) }
        GodWarsBosses.all.forEach { add(Dest(cmd(it.key), it.name, it.lair)) }
        EliteBosses.all.forEach { add(Dest(cmd(it.key), it.name, it.lair)) }
    }.distinctBy { it.cmd }

    init {
        dests.forEach { d ->
            // Guard: a name clash with an existing command would otherwise throw and drop the plugin.
            runCatching {
                onCommand(d.cmd, description = "Teleport to ${d.name}") { go(player, d) }
            }
        }

        onCommand("bosses", description = "List boss teleport commands") {
            player.message("<col=801700>== Boss teleports ==</col>")
            dests.sortedBy { it.name }.chunked(4).forEach { row ->
                player.message(row.joinToString("   ") { "${it.name}: <col=801700>::${it.cmd}</col>" })
            }
            player.message("Also: <col=801700>::kbd ::zulrah ::barrows</col>")
        }

        onCommand("boss", description = "Teleport to a boss by name: ::boss <name>") {
            val q = player.getCommandArgs().joinToString(" ").trim()
            if (q.isEmpty()) { player.message("Usage: ::boss <name>  —  see ::bosses"); return@onCommand }
            val match = dests.firstOrNull { it.cmd.equals(q, true) || it.name.equals(q, true) }
                ?: dests.firstOrNull { it.name.contains(q, true) || it.cmd.contains(q, true) }
            if (match == null) player.message("No boss matching '<col=801700>$q</col>'. Try ::bosses")
            else go(player, match)
        }
    }

    private fun go(p: Player, d: Dest) {
        p.moveTo(d.tile)
        p.message("<col=801700>Teleported to ${d.name}.</col>")
    }

    private fun cmd(key: String): String =
        key.removePrefix("npc.").replace(Regex("_\\d+$"), "")
}
