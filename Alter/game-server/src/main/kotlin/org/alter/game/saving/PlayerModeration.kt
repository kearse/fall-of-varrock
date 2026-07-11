package org.alter.game.saving

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.GameContext
import org.alter.game.saving.formats.FormatHandler
import org.bson.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * A single account's moderation state, keyed by lowercase login username.
 *
 * [mutedUntil]: 0 = not muted, -1 = muted until unmuted, otherwise the
 * epoch-millis timestamp the mute expires at.
 */
data class ModerationRecord(
    val loginUsername: String,
    var banned: Boolean = false,
    var banReason: String = "",
    var mutedUntil: Long = 0,
    var whitelisted: Boolean = false,
) {
    fun asDocument(): Document = Document().apply {
        append("loginUsername", loginUsername)
        append("banned", banned)
        append("banReason", banReason)
        append("mutedUntil", mutedUntil)
        append("whitelisted", whitelisted)
    }

    companion object {
        fun fromDocument(doc: Document): ModerationRecord = ModerationRecord(
            loginUsername = doc.getString("loginUsername") ?: "",
            banned = doc.getBoolean("banned", false),
            banReason = doc.getString("banReason") ?: "",
            mutedUntil = (doc["mutedUntil"] as? Number)?.toLong() ?: 0,
            whitelisted = doc.getBoolean("whitelisted", false),
        )
    }
}

/**
 * Ban / mute / whitelist state, persisted in the `moderation` collection of the
 * configured save format. Whitelist entries are independent of account existence,
 * so a tester can be whitelisted before they first log in.
 *
 * All records are held in memory (the set stays tiny) so login and chat checks
 * never touch the store; every mutation writes through immediately. Reads happen
 * on the login worker thread while writes come from the game thread, hence the
 * concurrent map.
 */
object PlayerModeration {

    private val logger = KotlinLogging.logger {}

    private lateinit var serialization: FormatHandler

    private val records = ConcurrentHashMap<String, ModerationRecord>()

    fun init(gameContext: GameContext) {
        serialization = gameContext.saveFormat.createInstance("moderation")
        serialization.loadAll().forEach { (name, doc) ->
            records[name.lowercase()] = ModerationRecord.fromDocument(doc)
        }
        serialization.init()
        logger.info { "Loaded ${records.size} moderation record(s)." }
    }

    private fun key(loginUsername: String) = loginUsername.lowercase().trim()

    private fun persist(record: ModerationRecord) {
        serialization.saveDocument(record.loginUsername, record.asDocument())
    }

    private fun mutate(loginUsername: String, change: (ModerationRecord) -> Unit) {
        val record = records.getOrPut(key(loginUsername)) { ModerationRecord(key(loginUsername)) }
        change(record)
        persist(record)
    }

    fun isBanned(loginUsername: String): Boolean = records[key(loginUsername)]?.banned == true

    fun banReason(loginUsername: String): String = records[key(loginUsername)]?.banReason.orEmpty()

    fun isMuted(loginUsername: String): Boolean {
        val until = records[key(loginUsername)]?.mutedUntil ?: 0
        return until == -1L || until > System.currentTimeMillis()
    }

    fun isWhitelisted(loginUsername: String): Boolean = records[key(loginUsername)]?.whitelisted == true

    fun setBanned(loginUsername: String, banned: Boolean, reason: String = "") =
        mutate(loginUsername) {
            it.banned = banned
            it.banReason = if (banned) reason else ""
        }

    /**
     * @param until 0 to unmute, -1 for an indefinite mute, otherwise the
     * epoch-millis timestamp the mute lifts at.
     */
    fun setMuted(loginUsername: String, until: Long) = mutate(loginUsername) { it.mutedUntil = until }

    fun setWhitelisted(loginUsername: String, whitelisted: Boolean) =
        mutate(loginUsername) { it.whitelisted = whitelisted }

    fun whitelistedNames(): List<String> = records.values.filter { it.whitelisted }.map { it.loginUsername }.sorted()

    fun bannedNames(): List<String> = records.values.filter { it.banned }.map { it.loginUsername }.sorted()
}
