package org.alter.plugins.content.economy.daily

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DAILY_LAST_DAY_ATTR
import org.alter.game.model.attr.DAILY_STREAK_ATTR
import org.alter.game.model.attr.VOTE_LAST_DAY_ATTR
import org.alter.game.model.attr.VOTE_STREAK_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

/**
 * **Daily reward + Vote** (Phase 7 retention — the "log in every day" loop).
 *
 * Two once-per-day claims, each with a **streak** that grows the payout for consecutive days
 * (and resets if you miss a day):
 *  - `::daily`  — a gp + Vote-point payout (a modest, time-gated faucet; the streak is the hook).
 *  - `::vote` / `::claimvote` — a local stub for voting (no real vote site on a localhost server);
 *    `::claimvote` grants Vote points once per day with its own streak.
 *
 * Day-stamps are epoch-day numbers so "tomorrow" is real-calendar based. Rotating *objective*
 * tasks (kill N of X, etc.) are a future extension — they need cross-skill event tracking; this
 * MVP delivers the core daily-claim retention loop.
 */
class DailyVotePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")

    init {
        onCommand("daily", description = "Claim your daily reward") { claimDaily(player) }
        onCommand("vote", description = "How to vote for the server") { voteInfo(player) }
        onCommand("claimvote", description = "Claim your vote reward") { claimVote(player) }
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

    private fun voteInfo(player: Player) {
        player.message("--- Vote for Fall of Varrock ---")
        player.message("Voting is a local stub on this server. Use <col=801700>::claimvote</col> once a day to claim your reward.")
    }

    private fun claimVote(player: Player) {
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
    }
}
