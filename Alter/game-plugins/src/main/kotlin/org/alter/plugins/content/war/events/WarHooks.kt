package org.alter.plugins.content.war.events

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.plugins.content.war.CampaignTier
import org.alter.plugins.content.war.WarType

private val logger = KotlinLogging.logger {}

/**
 * **The war's result hook.** Every public war op — march, Grand March, Lord operation, campaign,
 * conquest — publishes one [WarResult] the moment it ends (win or loss), after the participants'
 * [ServiceRecord]s are written and the payout has run. Quests ("meaningful participation → advance
 * the journal → award Veteran"), achievements, titles and leaderboards subscribe here instead of
 * polling the ledger. RAID parties (boss support) never publish — they are not a war.
 *
 * Listeners run in descending [priority] order, each isolated: one throwing listener never blocks
 * the rest. Register from any plugin `init`; nothing fires until a real op ends.
 */
object WarHooks {

    data class WarResult(
        val type: WarType,
        val tier: CampaignTier,
        /** The [org.alter.plugins.content.war.CampaignOp.cityKey] — a hostile city or a march-target key. */
        val targetKey: String,
        val displayName: String,
        /** The ledger key ([ServiceRecords.opKey]) every participant's record was filed under. */
        val opKey: String,
        val won: Boolean,
        /** The commanding player's name, or null for the realm's own / an event-started op. */
        val sponsor: String?,
        /** username → percent of the op's participation (0-100). A sponsor who never fought is present with 0. */
        val shares: Map<String, Int>,
        /** The war-chest the participants split (gp value pooled from enemy kills). */
        val lootPool: Long,
    ) {
        fun share(username: String): Int = shares.entries.firstOrNull { it.key.equals(username, ignoreCase = true) }?.value ?: 0
        fun participated(username: String, minShare: Int = 1): Boolean = share(username) >= minShare
    }

    private class Listener(val priority: Int, val fn: (WarResult) -> Unit)

    private val listeners = ArrayList<Listener>()

    fun onOperationEnded(priority: Int = 0, listener: (WarResult) -> Unit) {
        listeners += Listener(priority, listener)
        listeners.sortByDescending { it.priority }
    }

    internal fun fire(result: WarResult) {
        for (l in listeners.toList()) {
            runCatching { l.fn(result) }
                .onFailure { logger.error(it) { "War-result listener failed for ${result.opKey} (won=${result.won})" } }
        }
    }
}
