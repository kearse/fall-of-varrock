package org.alter.game.hiscores

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.saving.formats.impl.DatabaseManager
import org.alter.game.saving.impl.AttributeSerialisation
import org.alter.game.saving.impl.SkillSerialisation

/**
 * Game → website hiscores bridge.
 *
 * The website's /hiscores reads the Mongo `details` collection — but with the default
 * `saveFormat: JSON` the game only ever writes saves to disk, so that collection was a
 * frozen snapshot of whenever `migrate-saves.ts` last ran ("hiscores are pulling wrong
 * data"). This mirrors the [org.alter.game.discord.DiscordBridge] pattern: write the
 * hiscores-relevant slice (skills + PvP attributes) straight to Mongo regardless of the
 * save format, on logout and on a periodic sweep of online players (HiscoresSyncPlugin).
 *
 * The update $sets only the fields the web reads, keyed by the NORMALIZED (lowercase)
 * login — so if the full Mongo save format is ever enabled, its complete `details` docs
 * are amended, not clobbered. Unreachable Mongo disables the bridge after one logged
 * failure, exactly like the Discord bridge — hiscores go stale, the game is unaffected.
 */
object HiscoresSync {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var disabled = false

    private val skillSerialisation = SkillSerialisation()
    private val attributeSerialisation = AttributeSerialisation()

    /** The only attribute keys the web/bot hiscores read — keep the synced doc lean. */
    private val PVP_KEYS = listOf("pk_kills", "pk_deaths", "pk_elo")

    /** Upsert [player]'s hiscores slice into the shared `details` collection. */
    fun sync(player: Player) {
        if (disabled) return
        val client = player as? Client ?: return // never bots/companions — real accounts only
        val login = client.loginUsername.lowercase()
        if (login.isBlank()) return
        try {
            DatabaseManager.connect()
            val updates = arrayListOf(
                Updates.set("loginUsername", login),
                Updates.set("attributes.skills", skillSerialisation.asDocument(client)),
                Updates.set("hiscoresSyncedAt", System.currentTimeMillis()),
            )
            val attrs = attributeSerialisation.asDocument(client)
            PVP_KEYS.forEach { key ->
                attrs[key]?.let { updates += Updates.set("attributes.attribute.$key", it) }
            }
            DatabaseManager.getCollection("details").updateOne(
                Filters.eq("loginUsername", login),
                Updates.combine(updates),
                UpdateOptions().upsert(true),
            )
        } catch (e: Exception) {
            failOnce(e)
        }
    }

    private fun failOnce(e: Exception) {
        if (!disabled) {
            disabled = true
            logger.warn(e) { "Hiscores sync disabled — Mongo unreachable (website hiscores will go stale)." }
        }
    }
}
