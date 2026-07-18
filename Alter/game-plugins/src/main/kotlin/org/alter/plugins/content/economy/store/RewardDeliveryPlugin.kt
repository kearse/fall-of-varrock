package org.alter.plugins.content.economy.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.formats.impl.DatabaseManager
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

/**
 * **Store reward delivery** — the bridge between website purchases and the game.
 *
 * When a player pays on the website, the payment webhook writes an `entitlement`
 * document to MongoDB. On the player's next login we read their unapplied
 * entitlements, grant them in-game, and mark them applied. No live coupling between
 * the web app and the game is needed — the database is the hand-off point.
 *
 * Entitlement shape (see the web app's `EntitlementDoc`):
 * ```
 * { loginUsername, kind, payload, orderId, applied, createdAt }
 *   kind = "donor_points" -> payload { points: Int }
 *   kind = "items"        -> payload { items: [ { item: <rscm|id>, amount: Int }, ... ] }
 *   kind = "membership"   -> payload { tier: String, days: Int }
 *   kind = "vote_points"  -> payload { points: Int }   (toplist postbacks, see /api/vote/postback)
 * ```
 *
 * Only active when the server is running on the MongoDB save format (the store
 * requires a shared DB). If Mongo isn't reachable, delivery is skipped silently.
 */
class RewardDeliveryPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            val client = player as? Client ?: return@onLogin
            try {
                deliverPending(client)
            } catch (e: Exception) {
                logger.warn(e) { "Reward delivery failed for ${client.loginUsername}" }
            }
        }
    }

    private fun deliverPending(player: Client) {
        val collection = try {
            DatabaseManager.connect()
            DatabaseManager.getCollection("entitlements")
        } catch (e: Exception) {
            // No Mongo configured (e.g. JSON save format) — store isn't active.
            return
        }

        val filter = Filters.and(
            Filters.eq("loginUsername", player.loginUsername),
            Filters.eq("applied", false),
        )

        val pending = collection.find(filter).toList()
        if (pending.isEmpty()) return

        var deliveredPurchases = 0
        for (doc in pending) {
            val kind = doc.getString("kind")
            val applied = when (kind) {
                "donor_points" -> applyDonorPoints(player, doc.get("payload", Document::class.java))
                "items" -> applyItems(player, doc.get("payload", Document::class.java))
                "membership" -> applyMembership(player, doc.get("payload", Document::class.java))
                "patron_march" -> applyPatronMarch(player, doc.get("payload", Document::class.java))
                "vote_points" -> applyVotePoints(player, doc.get("payload", Document::class.java))
                else -> false
            }
            if (applied) {
                collection.updateOne(
                    Filters.eq("_id", doc.get("_id")),
                    Updates.combine(
                        Updates.set("applied", true),
                        Updates.set("appliedAt", System.currentTimeMillis()),
                    ),
                )
                if (kind != "vote_points") deliveredPurchases++
            }
        }

        if (deliveredPurchases > 0) {
            player.message("<col=801700>Thank you for supporting Fall of Varrock!</col> Your purchase has been delivered.")
        }
    }

    private fun applyVotePoints(player: Player, payload: Document?): Boolean {
        val points = payload?.let { numberOf(it, "points") } ?: return false
        if (points <= 0) return false
        player.awardTickets(PointKind.VOTE, points)
        player.message("<col=801700>Thanks for voting!</col> +$points Vote Ticket${if (points == 1) "" else "s"}. Spend them at the Reward Exchange.")
        return true
    }

    private fun applyDonorPoints(player: Player, payload: Document?): Boolean {
        val points = payload?.let { numberOf(it, "points") } ?: return false
        if (points <= 0) return false
        player.addPoints(PointKind.DONOR, points)
        player.message("You received <col=801700>$points donor points</col>.")
        return true
    }

    private fun applyItems(player: Player, payload: Document?): Boolean {
        @Suppress("UNCHECKED_CAST")
        val items = payload?.get("items") as? List<Document> ?: return false
        if (items.isEmpty()) return false
        for (entry in items) {
            val itemId = resolveItem(entry.get("item")) ?: continue
            val amount = numberOf(entry, "amount").coerceAtLeast(1)
            val added = player.inventory.add(item = itemId, amount = amount, assureFullInsertion = false)
            val leftover = amount - added.completed
            if (leftover > 0) world.spawn(GroundItem(itemId, leftover, player.tile, player))
        }
        return true
    }

    /** Patron of the (Grand) March — queue a realm march funded in the buyer's name
     *  (story-and-grind-design §7); MarchPlugin consumes it at the next muster call. */
    private fun applyPatronMarch(player: Player, payload: Document?): Boolean {
        val grand = payload?.getBoolean("grand", false) ?: false
        org.alter.plugins.content.war.WarState.queuePatronMarch(player.username, grand)
        player.message("<col=ffae00>Patron of the ${if (grand) "Grand March" else "March"}:</col> the Knight-Captain will muster your march at the next call — in your name.")
        return true
    }

    private fun applyMembership(player: Client, payload: Document?): Boolean {
        val tier = payload?.getString("tier") ?: return false
        val days = numberOf(payload, "days").coerceAtLeast(0)
        // One shared grant path with bond redemption (bond spec §3.4) — see MembershipService.
        return MembershipService.grant(player, tier, days)
    }

    /** Resolve an item reference that may be a numeric id or an rscm string. */
    private fun resolveItem(ref: Any?): Int? = when (ref) {
        is Number -> ref.toInt()
        is String -> try { getRSCM(ref) } catch (e: Exception) { null }
        else -> null
    }

    private fun numberOf(doc: Document, key: String): Int = (doc.get(key) as? Number)?.toInt() ?: 0

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Drain any unapplied `vote_points` entitlements for an online player — the
         * ::claimvote path, so a vote confirmed mid-session pays out without a relog
         * (login delivery above catches everything else). Returns the total points
         * delivered (0 = none pending), or -1 if Mongo isn't available.
         */
        fun claimVotePoints(player: Client): Int {
            val collection = try {
                DatabaseManager.connect()
                DatabaseManager.getCollection("entitlements")
            } catch (e: Exception) {
                return -1
            }

            val filter = Filters.and(
                Filters.eq("loginUsername", player.loginUsername),
                Filters.eq("kind", "vote_points"),
                Filters.eq("applied", false),
            )

            var total = 0
            for (doc in collection.find(filter).toList()) {
                val points = (doc.get("payload", Document::class.java)?.get("points") as? Number)?.toInt() ?: 0
                if (points <= 0) continue
                collection.updateOne(
                    Filters.eq("_id", doc.get("_id")),
                    Updates.combine(
                        Updates.set("applied", true),
                        Updates.set("appliedAt", System.currentTimeMillis()),
                    ),
                )
                total += points
            }
            if (total > 0) player.awardTickets(PointKind.VOTE, total)
            return total
        }
    }
}
