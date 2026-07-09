package org.alter.game.discord

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.saving.formats.impl.DatabaseManager
import org.bson.Document

/**
 * Game → Discord bridge.
 *
 * Writes in-game events into the shared MongoDB `discord_events` queue and keeps
 * an `online` presence collection up to date. The standalone discord-bot worker
 * (Alter/discord-bot) drains the queue and reads presence — the game never talks
 * to Discord directly, so a bot/network outage never affects the game loop.
 *
 * This reuses [DatabaseManager], so it works whenever Mongo is reachable —
 * regardless of which save format the game itself uses. If Mongo is unreachable
 * it disables itself after the first failure and logs once (events are dropped,
 * the game is unaffected). Disable explicitly with -DDISCORD_BRIDGE=false.
 */
object DiscordBridge {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var disabled = false

    private fun enabled(): Boolean {
        if (disabled) return false
        val flag = System.getProperty("DISCORD_BRIDGE")?.takeIf { it.isNotBlank() }
            ?: System.getenv("DISCORD_BRIDGE")?.takeIf { it.isNotBlank() }
        if (flag != null && flag.equals("false", ignoreCase = true)) {
            disabled = true
            return false
        }
        return true
    }

    /** A single embed field (name, value, inline). */
    data class Field(val name: String, val value: String, val inline: Boolean = true)

    /**
     * Queue an event for the bot to post. `kind` routes it to a channel
     * (see discord-bot/src/config.ts channelForEventKind):
     * "level99", "drop", "pk", "boss", "raid", "war", "status", "boot", "shutdown".
     */
    fun event(
        kind: String,
        title: String,
        description: String? = null,
        player: String? = null,
        fields: List<Field> = emptyList(),
        thumbnailItemId: Int? = null,
    ) {
        if (!enabled()) return
        try {
            DatabaseManager.connect()
            val doc = Document()
                .append("kind", kind)
                .append("title", title)
                .append("posted", false)
                .append("createdAt", System.currentTimeMillis())
            description?.let { doc.append("description", it) }
            player?.let { doc.append("player", it) }
            thumbnailItemId?.let { doc.append("thumbnailItemId", it) }
            if (fields.isNotEmpty()) {
                doc.append("fields", fields.map {
                    Document().append("name", it.name).append("value", it.value).append("inline", it.inline)
                })
            }
            DatabaseManager.getCollection("discord_events").insertOne(doc)
        } catch (e: Exception) {
            failOnce(e)
        }
    }

    /** Mark a player online (upsert one presence doc). */
    fun setOnline(loginUsername: String, displayName: String) {
        if (!enabled()) return
        try {
            DatabaseManager.connect()
            DatabaseManager.getCollection("online").updateOne(
                Filters.eq("loginUsername", loginUsername),
                Updates.combine(
                    Updates.set("displayName", displayName),
                    Updates.setOnInsert("since", System.currentTimeMillis()),
                ),
                UpdateOptions().upsert(true),
            )
        } catch (e: Exception) {
            failOnce(e)
        }
    }

    /** Mark a player offline. */
    fun setOffline(loginUsername: String) {
        if (!enabled()) return
        try {
            DatabaseManager.connect()
            DatabaseManager.getCollection("online").deleteOne(Filters.eq("loginUsername", loginUsername))
        } catch (e: Exception) {
            failOnce(e)
        }
    }

    /** Wipe all presence (call on boot — stale docs from a crash linger otherwise). */
    fun clearOnline() {
        if (!enabled()) return
        try {
            DatabaseManager.connect()
            DatabaseManager.getCollection("online").deleteMany(Document())
        } catch (e: Exception) {
            failOnce(e)
        }
    }

    private fun failOnce(e: Exception) {
        if (!disabled) {
            disabled = true
            logger.warn(e) { "DiscordBridge disabled (MongoDB unavailable); in-game events won't reach Discord." }
        }
    }
}
