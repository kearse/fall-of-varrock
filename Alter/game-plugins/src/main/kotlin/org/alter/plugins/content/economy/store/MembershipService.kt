package org.alter.plugins.content.economy.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.entity.Client
import org.alter.game.saving.formats.impl.DatabaseManager
import org.bson.Document

/**
 * **Membership grants** — the ONE code path that writes `membership.tier`/`membership.expires`
 * onto the account doc, shared by the store webhook delivery ([RewardDeliveryPlugin]) and bond
 * redemption ([BondPlugin]), per the bond spec (§3.4): a single helper so the two grant surfaces
 * can never diverge. Extends from the current expiry when still active, else from now (stacking).
 *
 * Only meaningful on the MongoDB save format (the store's shared DB); on JSON saves every call
 * returns false and callers degrade gracefully (same policy as RewardDeliveryPlugin).
 */
object MembershipService {

    private val logger = KotlinLogging.logger {}

    /** True if the shared store DB is reachable (i.e. membership can actually be granted). */
    fun storeAvailable(): Boolean = try {
        DatabaseManager.connect()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Grant [days] of [tier] membership to [player], extending any active time. Tier strings are
     * LOWERCASE ("bronze".."diamond") to match the Discord role map. Returns false (nothing
     * written) if the store DB is unavailable or the write fails — callers must treat that as
     * "not granted" and refund whatever paid for it.
     */
    fun grant(player: Client, tier: String, days: Int): Boolean {
        if (days <= 0) return false
        return try {
            DatabaseManager.connect()
            val accounts = DatabaseManager.getCollection("accounts")
            val account = accounts.find(Filters.eq("loginUsername", player.loginUsername)).first()
            val currentExpiry = (account?.get("membership", Document::class.java)?.get("expires") as? Number)?.toLong() ?: 0L
            val base = maxOf(System.currentTimeMillis(), currentExpiry)
            val newExpiry = base + days * 86_400_000L
            accounts.updateOne(
                Filters.eq("loginUsername", player.loginUsername),
                Updates.combine(
                    Updates.set("membership.tier", tier),
                    Updates.set("membership.expires", newExpiry),
                ),
            )
            player.message("Your <col=801700>$tier membership</col> is now active for $days more day(s).")
            true
        } catch (e: Exception) {
            logger.warn(e) { "Membership grant failed for ${player.loginUsername} ($tier, ${days}d)" }
            false
        }
    }
}
