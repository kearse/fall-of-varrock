package org.alter.plugins.content.economy.store

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.formats.impl.DatabaseManager
import org.bson.Document

/**
 * **`::update`** — post a game update straight from in-game.
 *
 * Writes a document to the shared `news` collection (the single source of truth for
 * dev updates). The website renders it on the News page, and the Discord worker posts
 * it to the announcements channel — so one in-game command reaches the whole community.
 *
 * Usage: `::update Patch 1.4 | Fixed the Duke door and rebalanced goblin raids.`
 *        (title before the `|`, markdown body after; body optional)
 */
class UpdatePostPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("update", Privilege.ADMIN_POWER, description = "Post a game update to the site + Discord") {
            val raw = player.getCommandArgs().joinToString(" ").trim()
            if (raw.isEmpty()) {
                player.message("Usage: ::update <title> | <body>")
                return@onCommand
            }
            val parts = raw.split("|", limit = 2)
            val title = parts[0].trim()
            val body = parts.getOrNull(1)?.trim().orEmpty()
            val authorName = (player as? Client)?.username ?: "Staff"

            // Standard process: ask whether to announce this to Discord before posting.
            player.queue {
                val choice = options(
                    player,
                    "Post to site + Discord",
                    "Post to site only",
                    "Cancel",
                    title = "Push \"$title\"?",
                )
                if (choice != 1 && choice != 2) {
                    player.message("Update cancelled.")
                    return@queue
                }
                val pushToDiscord = choice == 1
                try {
                    postNews(title, body, authorName, pushToDiscord)
                    val where = if (pushToDiscord) "the site and Discord" else "the site only"
                    player.message("<col=801700>Update posted:</col> \"$title\" — it will appear on $where shortly.")
                } catch (e: Exception) {
                    logger.warn(e) { "::update failed" }
                    player.message("Could not post the update (is MongoDB connected?).")
                }
            }
        }
    }

    private fun postNews(title: String, body: String, authorName: String, pushToDiscord: Boolean) {
        DatabaseManager.connect()
        val now = System.currentTimeMillis()
        val doc = Document().apply {
            append("slug", slugify(title) + "-" + now.toString(36))
            append("title", title)
            append("body", body)
            append("authorName", authorName)
            append("category", "update")
            append("published", true)
            append("pushToDiscord", pushToDiscord)
            append("discordPosted", false)
            append("createdAt", now)
            append("publishedAt", now)
        }
        DatabaseManager.getCollection("news").insertOne(doc)
    }

    private fun slugify(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "update" }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
