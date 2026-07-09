package org.alter.plugins.content.commands.commands

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * **Book of Commands** — a player-facing reference listing every `::command` available to a normal
 * player and to donors (staff/admin commands are omitted). Read the book item, or type `::commands`.
 *
 * The list is generated live from the registered command table ([PluginRepository.commandPlugins]),
 * split by the power each command requires (null = everyone, "donor" = donor) — so it never drifts
 * from what actually exists in the server. New player/donor commands appear here automatically.
 *
 * The book item is handed out in the Recruit Trials starter pack; reading it opens this same list.
 */
class BookOfCommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val book = "item.book"

    init {
        onCommand("commands", description = "List every command available to you") { listCommands(player) }

        // The book item must carry a "Read" option in the cache, or onItemOption throws and drops the
        // whole plugin — so check first and bind only if present (::commands works either way).
        val opts = runCatching { getItem(getRSCM(book)).interfaceOptions }.getOrNull()
        val read = opts?.firstOrNull { it?.equals("Read", true) == true }
        if (read != null) {
            onItemOption(book, option = read) { listCommands(player) }
        } else {
            logger.warn { "Book of Commands: '$book' has no Read option in cache (options=${opts?.filterNotNull()}); readable via ::commands only." }
        }
    }

    private fun listCommands(p: Player) {
        val repo = world.plugins
        val playerCmds = sortedSetOf<String>()
        val donorCmds = sortedSetOf<String>()
        repo.commandPlugins.forEach { (cmd, pair) ->
            val desc = repo.getDescription(cmd)?.takeIf { it.isNotBlank() && it != "null" }
            val line = "<col=000080>::$cmd</col>" + (desc?.let { " — $it" } ?: "")
            when (pair.first) {
                null -> playerCmds += line
                Privilege.DONOR_POWER -> donorCmds += line
                else -> {} // staff/admin/dev/owner commands are not listed for players
            }
        }
        p.message("<col=801700>===== Book of Commands =====</col>")
        p.message("<col=801700>Player commands (${playerCmds.size}):</col>")
        playerCmds.forEach { p.message(it) }
        p.message("<col=801700>Donor commands (${donorCmds.size}):</col>")
        if (donorCmds.isEmpty()) p.message("<col=000080>(none yet — coming with the donor system)</col>") else donorCmds.forEach { p.message(it) }
        p.message("<col=801700>============================</col>")
    }
}
