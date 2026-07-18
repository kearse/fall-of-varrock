package org.alter.plugins.content.economy.daily

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import java.net.URLEncoder
import org.alter.api.ChatMessageType
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DAILY_LAST_DAY_ATTR
import org.alter.game.model.attr.DAILY_STREAK_ATTR
import org.alter.game.model.attr.VOTE_LAST_DAY_ATTR
import org.alter.game.model.attr.VOTE_STREAK_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.formats.impl.DatabaseManager
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.economy.store.RewardDeliveryPlugin
import org.alter.rscm.RSCM.getRSCM

/**
 * **Daily reward + Vote** (Phase 7 retention — the "log in every day" loop).
 *
 *  - `::daily` — a gp + Vote-point payout with a consecutive-day streak (resets on a missed day).
 *  - `::vote`  — streams the toplist sites to the custom client's `lofvote` window (clickable
 *    rows with per-site cooldown state; clicking opens the site's vote page in the browser).
 *    Old custom clients browse the website's vote page off the open| line; vanilla clients get
 *    the URL in chat. The toplists confirm each vote via postback (`/api/vote/postback/<site>`),
 *    which queues a `vote_points` entitlement — delivered on next login by [RewardDeliveryPlugin].
 *  - `::claimvote` — drains pending vote rewards immediately for online players. On a server
 *    without MongoDB (local dev) it falls back to the old once-a-day streak stub.
 *  - `::setvotepoints <n>` (admin) — sets points-per-vote in the shared `settings` collection,
 *    which the website's postback route reads when crediting votes (default 1).
 *
 * Day-stamps are epoch-day numbers so "tomorrow" is real-calendar based.
 */
class DailyVotePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")

    init {
        onCommand("daily", description = "Claim your daily reward") { claimDaily(player) }
        onCommand("vote", description = "Vote for the server") { openVotePage(player) }
        onCommand("claimvote", description = "Claim your vote reward") { claimVote(player) }
        onCommand("setvotepoints", Privilege.ADMIN_POWER, description = "Set Vote Tickets granted per toplist vote") {
            val points = player.getCommandArgs().getOrNull(0)?.toIntOrNull()
            if (points == null || points < 1) {
                player.message("Usage: <col=801700>::setvotepoints &lt;points&gt;</col> (minimum 1)")
            } else {
                setVotePoints(player, points)
            }
        }
    }

    private fun claimDaily(player: Player) {
        val today = epochDay()
        val last = player.attr[DAILY_LAST_DAY_ATTR] ?: 0
        if (last == today) {
            player.message("You've already claimed your daily reward. Come back tomorrow!")
            return
        }
        val streak = if (last == today - 1) (player.attr[DAILY_STREAK_ATTR] ?: 0) + 1 else 1
        player.attr[DAILY_LAST_DAY_ATTR] = today
        player.attr[DAILY_STREAK_ATTR] = streak

        val gp = (DAILY_GP_BASE + DAILY_GP_PER_STREAK * (streak - 1)).coerceAtMost(DAILY_GP_MAX)
        val votePts = DAILY_VOTE_POINTS
        giveCoins(player, gp)
        player.awardTickets(PointKind.VOTE, votePts)
        player.message("<col=801700>Daily reward (day $streak streak):</col> ${"%,d".format(gp)} coins + $votePts Vote Tickets.")
    }

    /**
     * ::vote — stream the toplist sites to the client's vote window (lofvote), one row per
     * site with the player's login already substituted into the vote URL and the minutes
     * left on that site's cooldown (from the shared `votes` collection the web postbacks
     * write). Old clients act only on the open| line and browse the website's vote page.
     */
    private fun openVotePage(player: Player) {
        val login = (player as? Client)?.loginUsername ?: player.username
        val encoded = URLEncoder.encode(login, "UTF-8")
        val lastBySite = lastVoteBySite(login)
        val now = System.currentTimeMillis()

        player.message("${VOTE_TRIGGER_PREFIX}open|$VOTE_PAGE_URL?name=$encoded", ChatMessageType.CONSOLE)
        VOTE_SITES.forEachIndexed { i, site ->
            val url = site.urlTemplate.replace("{username}", encoded)
            val last = lastBySite[site.id] ?: 0L
            val remainingMins = ((last + VOTE_COOLDOWN_MS - now).coerceAtLeast(0L) / 60_000L).toInt()
            player.message("${VOTE_TRIGGER_PREFIX}row|$i|${site.name}|$url|$remainingMins", ChatMessageType.CONSOLE)
        }
        player.message("${VOTE_TRIGGER_PREFIX}end", ChatMessageType.CONSOLE)
        player.message("Vote for Fall of Varrock at <col=801700>fallofvarrock.com/vote</col> — rewards are delivered automatically (or ::claimvote).")
    }

    /** Latest confirmed vote per site id for [login], from the web postbacks' `votes`
     *  collection. Empty when Mongo isn't configured (local dev) — every site shows ready. */
    private fun lastVoteBySite(login: String): Map<String, Long> = try {
        DatabaseManager.connect()
        DatabaseManager.getCollection("votes")
            .find(Filters.eq("loginUsername", login))
            .toList()
            .groupBy { it.getString("site") ?: "" }
            .mapValues { (_, docs) -> docs.maxOf { (it.get("votedAt") as? Number)?.toLong() ?: 0L } }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun claimVote(player: Player) {
        val client = player as? Client
        if (client != null) {
            when (val claimed = RewardDeliveryPlugin.claimVotePoints(client)) {
                -1 -> {} // No MongoDB (local dev) — fall through to the stub below.
                0 -> {
                    player.message("No unclaimed votes right now. Vote with <col=801700>::vote</col> — rewards arrive within a few minutes of the toplist confirming.")
                    return
                }
                else -> {
                    player.message("<col=801700>Thanks for voting!</col> +$claimed Vote Ticket${if (claimed == 1) "" else "s"}. Spend them at the Reward Exchange.")
                    return
                }
            }
        }

        // Local stub (no vote sites reachable without a shared DB): once a day, with a streak.
        val today = epochDay()
        val last = player.attr[VOTE_LAST_DAY_ATTR] ?: 0
        if (last == today) {
            player.message("You've already claimed your vote reward today. Come back tomorrow!")
            return
        }
        val streak = if (last == today - 1) (player.attr[VOTE_STREAK_ATTR] ?: 0) + 1 else 1
        player.attr[VOTE_LAST_DAY_ATTR] = today
        player.attr[VOTE_STREAK_ATTR] = streak

        val votePts = VOTE_POINTS_BASE + (streak - 1)
        player.awardTickets(PointKind.VOTE, votePts)
        player.message("<col=801700>Thanks for voting (day $streak streak):</col> +$votePts Vote Tickets. Spend them at the Reward Exchange.")
    }

    /** ::setvotepoints — shared settings doc the web postback reads when crediting votes. */
    private fun setVotePoints(player: Player, points: Int) {
        try {
            DatabaseManager.connect()
            DatabaseManager.getCollection("settings").updateOne(
                Filters.eq("key", VOTE_POINTS_SETTING_KEY),
                Updates.combine(
                    Updates.set("value", points),
                    Updates.set("updatedAt", System.currentTimeMillis()),
                ),
                UpdateOptions().upsert(true),
            )
            player.message("Vote reward set to <col=801700>$points Vote Ticket${if (points == 1) "" else "s"}</col> per vote.")
        } catch (e: Exception) {
            player.message("Couldn't update the vote reward — is MongoDB configured?")
        }
    }

    /** Add coins to the inventory; overflow drops at the player's feet (so a full pack never voids it). */
    private fun giveCoins(player: Player, amount: Int) {
        val added = player.inventory.add(item = coins, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(coins, leftover, player.tile, player))
    }

    private fun epochDay(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    private companion object {
        const val DAILY_GP_BASE = 50_000
        const val DAILY_GP_PER_STREAK = 10_000
        const val DAILY_GP_MAX = 250_000
        const val DAILY_VOTE_POINTS = 1
        const val VOTE_POINTS_BASE = 2

        /** The website's vote page — the old clients' fallback and the ?name= deep-link target. */
        const val VOTE_PAGE_URL = "https://fallofvarrock.com/vote"

        /** Machine prefix the `lofvote` client plugin listens for (must match its PREFIX). */
        const val VOTE_TRIGGER_PREFIX = "FOV_VOTE:"

        /** `settings` key the web postback reads for points-per-vote (matches lib/vote.ts). */
        const val VOTE_POINTS_SETTING_KEY = "votePointsPerVote"

        /** One credit per site per window — matches the web postback's cooldown guard. */
        const val VOTE_COOLDOWN_MS = 12 * 3_600_000L

        /**
         * The toplists streamed to the vote window. KEEP IN SYNC with the website's
         * VOTE_SITES (Alter/web/src/lib/vote.ts) — same ids (they key the cooldown
         * lookup against the `votes` collection), same order, same {username} slot.
         */
        val VOTE_SITES = listOf(
            VoteSite("rsps-list", "RSPS-List", "https://www.rsps-list.com/index.php?a=in&u=BizzyZ&id={username}"),
            VoteSite("rulocus", "RuLocus", "https://www.rulocus.com/top-rsps-list/fall-of-varrock/vote?callback={username}"),
            VoteSite("moparscape", "Moparscape", "https://www.moparscape.org/rsps-list/server/fall-of-varrock?incentive={username}"),
            VoteSite("top100arena", "Top100Arena", "https://www.top100arena.com/listing/LISTING_ID/vote?incentive={username}"),
            VoteSite("topg", "TopG", "https://topg.org/runescape-private-servers/server-684312-{username}#vote"),
        )
    }

    /** One toplist: web-matching id, display name, vote URL with a {username} slot. */
    data class VoteSite(val id: String, val name: String, val urlTemplate: String)
}
