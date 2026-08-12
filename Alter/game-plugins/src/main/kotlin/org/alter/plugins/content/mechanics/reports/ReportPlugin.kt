package org.alter.plugins.content.mechanics.reports

import org.alter.api.ext.getInteractingPlayer
import org.alter.api.ext.inputString
import org.alter.api.ext.message
import org.alter.api.ext.messageBox
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.discord.DiscordBridge
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.PlayerDetails

/** Wall-clock of the player's last submitted report (anti-spam, not persisted). */
val LAST_REPORT_MS = AttributeKey<Long>()

/**
 * The report system: the chatbox Report button (162:31) and the right-click
 * "Report" player option both run a chatbox dialog flow (this client can't open
 * custom cache interfaces — the old handler pointed the button at group 553, a
 * stale pre-modern report-abuse id that renders the MTA Pizazz panel in our
 * cache). Submitted reports persist via [ReportStore] and ping the staff
 * #mod-log channel through [DiscordBridge] (kind "report"). Staff work the
 * queue with ::reports / ::closereport (ReportCommandsPlugin).
 */
class ReportPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        private const val CHAT_BOX_INTERFACE = 162
        private const val REPORT_BUTTON_COMPONENT = 31
        private const val COOLDOWN_MS = 60_000L
        private const val DETAILS_MAX = 300

        private val CATEGORIES = listOf(
            "Offensive language",
            "Scamming",
            "Botting or macroing",
            "Bug abuse",
            "Real-world trading",
            "Other",
        )
    }

    init {
        onWorldInit {
            ReportStore.init(world.gameContext)
        }

        onButton(CHAT_BOX_INTERFACE, REPORT_BUTTON_COMPONENT) {
            player.queue { reportFlow(this, player, prefillName = null) }
        }

        onPlayerOption("Report") {
            // Capture the name now — the target may walk away or log out before
            // the queued dialog runs, and we only need the string.
            val targetName = player.getInteractingPlayer().username
            player.queue { reportFlow(this, player, prefillName = targetName) }
        }
    }

    private suspend fun reportFlow(task: QueueTask, player: Player, prefillName: String?) {
        val now = System.currentTimeMillis()
        val last = player.attr[LAST_REPORT_MS] ?: 0L
        if (now - last < COOLDOWN_MS) {
            val secs = (COOLDOWN_MS - (now - last) + 999) / 1000
            player.message("Please wait ${secs}s before filing another report.")
            return
        }

        val type: String
        if (prefillName != null) {
            type = Report.TYPE_PLAYER
        } else {
            type = when (task.options(player, "Report a player", "Report a bug", "Cancel", title = "What would you like to report?")) {
                1 -> Report.TYPE_PLAYER
                2 -> Report.TYPE_BUG
                else -> return
            }
        }

        var reportedName = ""
        var category = "Bug"
        if (type == Report.TYPE_PLAYER) {
            reportedName = prefillName?.let(::sanitizeName)
                ?: sanitizeName(task.inputString(player, "Who do you want to report? (exact name)"))
            if (reportedName.isEmpty()) return
            if (reportedName.equals(player.username, ignoreCase = true)) {
                player.message("You can't report yourself.")
                return
            }
            if (world.getPlayerForName(reportedName) == null && PlayerDetails.getDisplayName(reportedName) == null) {
                val sel = task.options(player, "Submit anyway", "Cancel", title = "'$reportedName' isn't a name we recognise.")
                if (sel != 1) return
            }
            category = pickCategory(task, player) ?: return
        }

        val details = sanitizeText(
            task.inputString(player, if (type == Report.TYPE_BUG) "Describe the bug:" else "Describe what happened:"),
        )
        if (type == Report.TYPE_BUG && details.isEmpty()) {
            player.message("A bug report needs a description.")
            return
        }

        val summary = if (type == Report.TYPE_PLAYER) "Report $reportedName for '$category'?" else "Submit this bug report?"
        if (task.options(player, "Yes, submit", "No, cancel", title = summary) != 1) return

        player.attr[LAST_REPORT_MS] = System.currentTimeMillis()
        val report = ReportStore.file(
            type = type,
            reporterLogin = (player as? Client)?.loginUsername ?: player.username,
            reporterDisplay = player.username,
            reportedName = reportedName,
            category = category,
            details = details,
        )

        DiscordBridge.event(
            kind = "report",
            title = if (type == Report.TYPE_PLAYER) "Player report ${report.id}" else "Bug report ${report.id}",
            description = report.details.ifEmpty { null },
            player = report.reporterDisplay,
            fields = buildList {
                add(DiscordBridge.Field("Report ID", report.id))
                add(DiscordBridge.Field("Type", report.type))
                if (report.reportedName.isNotEmpty()) add(DiscordBridge.Field("Reported", report.reportedName))
                add(DiscordBridge.Field("Category", report.category))
                add(DiscordBridge.Field("Reporter", report.reporterDisplay))
            },
        )

        task.messageBox(player, "Thanks — your report (<col=801700>${report.id}</col>) has been sent to the moderators.")
    }

    /** Chatbox option list holds 5 rows: 3 categories + "More..." + "Cancel" per page. */
    private suspend fun pickCategory(task: QueueTask, player: Player): String? {
        val perPage = 3
        val pages = (CATEGORIES.size + perPage - 1) / perPage
        var page = 0
        while (true) {
            val picks = CATEGORIES.drop(page * perPage).take(perPage)
            val labels = picks + (if (pages > 1) listOf("More...") else emptyList()) + "Cancel"
            val sel = task.options(player, *labels.toTypedArray(), title = "What rule are they breaking?")
            if (sel == -1) return null
            if (pages > 1 && sel == picks.size + 1) {
                page = (page + 1) % pages
                continue
            }
            return picks.getOrNull(sel - 1) ?: return null
        }
    }

    private fun sanitizeName(name: String): String =
        name.replace('_', ' ').filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(12)

    /** Strip chat colour tags and Discord mention triggers, cap the length. */
    private fun sanitizeText(text: String): String =
        text.filterNot { it == '<' || it == '>' || it == '@' }.trim().take(DETAILS_MAX)
}
