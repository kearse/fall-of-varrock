package org.alter.plugins.content.mechanics.reports

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.discord.DiscordBridge
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Staff-side tracking for the report system (see [ReportPlugin] for the
 * player-facing flow): list the queue in-game and close reports, mirroring the
 * resolution back to Discord #mod-log (kind "reportResolved").
 */
class ReportCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        private const val PER_PAGE = 5
    }

    init {
        onCommand("reports", Privilege.MOD_POWER, description = "List open reports: ::reports [page]") {
            listReports(player, ReportStore.open(), "open")
        }

        onCommand("allreports", Privilege.MOD_POWER, description = "List all reports incl. resolved: ::allreports [page]") {
            listReports(player, ReportStore.all(), "total")
        }

        onCommand("closereport", Privilege.MOD_POWER, description = "Resolve a report: ::closereport <id> [note]") {
            val args = player.getCommandArgs()
            val id = args.getOrNull(0)?.trim()?.lowercase()
            if (id.isNullOrEmpty()) {
                player.message("Usage: ::closereport <id> [note]")
                return@onCommand
            }
            val note = args.drop(1).joinToString(" ").trim()
            val staff = (player as? Client)?.loginUsername ?: player.username
            val resolved = ReportStore.resolve(id, staff, note)
            if (resolved == null) {
                player.message("No open report with id '$id'.")
                return@onCommand
            }
            player.message("Report ${resolved.id} marked resolved. ${ReportStore.open().size} still open.")
            DiscordBridge.event(
                kind = "reportResolved",
                title = "Report ${resolved.id} resolved",
                description = note.ifEmpty { null },
                player = player.username,
                fields = listOf(
                    DiscordBridge.Field("Report ID", resolved.id),
                    DiscordBridge.Field("Resolved by", player.username),
                ),
            )
        }
    }

    private fun listReports(player: Player, reports: List<Report>, label: String) {
        if (reports.isEmpty()) {
            player.message("No $label reports.")
            return
        }
        val pages = (reports.size + PER_PAGE - 1) / PER_PAGE
        val page = (player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: 1).coerceIn(1, pages)
        reports.drop((page - 1) * PER_PAGE).take(PER_PAGE).forEach { r ->
            val target = if (r.type == Report.TYPE_PLAYER) r.reportedName else "-"
            val status = if (r.status == Report.STATUS_RESOLVED) " | resolved by ${r.resolvedBy}" else ""
            player.message("[${r.id}] ${r.type} | ${r.category} | $target | by ${r.reporterDisplay} | ${age(r.createdAt)}$status")
        }
        player.message("Page $page/$pages — ${reports.size} $label report(s). ::closereport <id> [note] to resolve.")
    }

    private fun age(createdAt: Long): String {
        val mins = (System.currentTimeMillis() - createdAt) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}
