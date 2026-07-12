package org.alter.plugins.content.commands.commands

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Book of Commands** — `::commands` (or reading the book item) opens the on-screen command
 * reference in the custom client: a clean panel that groups every command *this player can
 * actually run* by the rank that unlocks it (Player, Donor, Moderator, Administrator, …) —
 * instead of dumping a wall of text into the chat box.
 *
 * The list is generated live from the registered command table ([PluginRepository.commandPlugins]),
 * so it never drifts from what actually exists. Each command is filtered against the player's
 * [Privilege.powers], so a normal player only ever sees their own commands, an admin sees theirs
 * plus everything below, and so on.
 *
 * ### How it reaches the overlay
 * There is no custom packet. We send the list as [ChatMessageType.CONSOLE] chat lines, each
 * prefixed with [OVERLAY_PREFIX] — the same hidden channel the companion system streams its
 * machine data on (`~LOFCMP~`). CONSOLE lines are delivered to the client's ChatMessage event
 * but are NEVER rendered in the chat box and live in their own line buffer, so they cannot
 * evict or disturb the player's visible chat. (GAME_MESSAGE was tried first: even when
 * display-filtered, each hidden line still consumed a slot in the game-message buffer and
 * silently evicted the player's oldest game messages — the "::commands deletes my chat" bug.)
 *
 * Wire format (one line each):
 * - `FOV_CMDS:open|<RankTitle>`
 * - `FOV_CMDS:row|<order>|<RoleLabel>|<::cmd>|<desc>`   (repeated; desc may be empty)
 * - `FOV_CMDS:end`
 *
 * The book item is handed out in the Recruit Trials starter pack; reading it opens the same panel.
 */
class BookOfCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val book = "item.book"

    init {
        onCommand("commands", description = "Open the command list for your rank") { sendCommandOverlay(player) }

        // The book item must carry a "Read" option in the cache, or onItemOption throws and drops the
        // whole plugin — so check first and bind only if present (::commands works either way).
        val opts = runCatching { getItem(getRSCM(book)).interfaceOptions }.getOrNull()
        val read = opts?.firstOrNull { it?.equals("Read", true) == true }
        if (read != null) {
            onItemOption(book, option = read) { sendCommandOverlay(player) }
        } else {
            logger.warn { "Book of Commands: '$book' has no Read option in cache (options=${opts?.filterNotNull()}); readable via ::commands only." }
        }
    }

    /**
     * Stream the player's available commands to the client overlay, grouped by the rank that
     * unlocks each one. Commands the player can't run are omitted entirely.
     */
    private fun sendCommandOverlay(p: Player) {
        val repo = world.plugins

        val rows = ArrayList<Row>()
        repo.commandPlugins.forEach { (cmd, pair) ->
            val power = pair.first
            // Only list what this player may actually run (null power = everyone).
            if (power != null && !p.privilege.powers.contains(power.lowercase())) return@forEach
            val (order, label) = roleFor(power)
            val desc = repo.getDescription(cmd)?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            rows += Row(order, label, "::$cmd", desc)
        }
        rows.sortWith(compareBy({ it.order }, { it.cmd }))

        val rankTitle = p.privilege.name.replaceFirstChar { it.titlecase() }
        p.message("${OVERLAY_PREFIX}open|$rankTitle", ChatMessageType.CONSOLE)
        for (row in rows) {
            // One command per line keeps us comfortably under the ~250-byte MESSAGE_GAME cap;
            // hard-trim a pathologically long description so a single line can never overflow.
            val desc = if (row.desc.length > MAX_DESC) row.desc.take(MAX_DESC - 3) + "..." else row.desc
            p.message("${OVERLAY_PREFIX}row|${row.order}|${row.label}|${row.cmd}|$desc", ChatMessageType.CONSOLE)
        }
        p.message("${OVERLAY_PREFIX}end", ChatMessageType.CONSOLE)
    }

    /** Maps a command's required power to its (sort order, display label) in the overlay. */
    private fun roleFor(power: String?): Pair<Int, String> = when (power) {
        null -> 0 to "Player"
        Privilege.DONOR_POWER -> 1 to "Donor"
        Privilege.MOD_POWER -> 2 to "Moderator"
        Privilege.ADMIN_POWER -> 3 to "Administrator"
        Privilege.DEV_POWER -> 4 to "Developer"
        Privilege.OWNER_POWER -> 5 to "Owner"
        else -> 6 to power.replaceFirstChar { it.titlecase() }
    }

    private data class Row(val order: Int, val label: String, val cmd: String, val desc: String)

    private companion object {
        /** Machine prefix the `lofcommands` client plugin listens for (must match its PREFIX). */
        const val OVERLAY_PREFIX = "FOV_CMDS:"
        const val MAX_DESC = 160
    }
}
