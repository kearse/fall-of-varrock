package org.alter.plugins.content.war

import org.alter.game.model.attr.PLAYER_TITLE_ATTR
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * The single place a feudal rank is actually bought. Both purchase surfaces — Duke Horacio's
 * dialogue (the quest beats) and the client-drawn Feudal Ranks window ([RankMenuPlugin]) —
 * call [buy], so the eligibility check ([RankEligibility]), the title bump, the name refresh,
 * the cape and the [RankEvents] broadcast stay in lockstep no matter which UI sold the rank.
 *
 * Promotion is **earned standing** (design authority §5): coins are one requirement; lifetime
 * War Effort (and, later, milestones such as Veteran of Varrock) are the others. Participation
 * in any war is never gated by rank — only starting one is ([Player.canCommand]).
 */
object RankPurchase {

    sealed class Result {
        /** Rank bought; the player's [Player.title] is already the new rank. */
        object Success : Result()

        /** The ONLY shortfall is coins carried (kept so the old "come back richer" copy still fits). */
        data class Insufficient(val cost: Int, val have: Int) : Result()

        /** Eligibility not met beyond (or besides) coins — service or milestones still owed. */
        data class Blocked(val unmet: List<RankEligibility.Unmet>) : Result()

        /** [buy] was asked for a rank that isn't the player's next rung (ladder is one-at-a-time). */
        object NotNext : Result()

        /** Already King — nothing above to buy. */
        object Maxed : Result()
    }

    /**
     * Attempt to raise [player] to [target]. Only ever succeeds when [target] is exactly the
     * player's next rung — the ladder cannot be skipped, whichever UI asks — AND every
     * [RankEligibility] requirement is met.
     */
    fun buy(player: Player, target: Title): Result {
        val next = player.nextTitle ?: return Result.Maxed
        if (target != next) return Result.NotNext

        val unmet = RankEligibility.check(player, next)
        if (unmet.isNotEmpty()) {
            val coinsOnly = unmet.singleOrNull() as? RankEligibility.Unmet.Coins
            return if (coinsOnly != null) Result.Insufficient(coinsOnly.need, coinsOnly.have) else Result.Blocked(unmet)
        }

        player.inventory.remove(getRSCM("item.coins_995"), next.cost)
        player.attr[PLAYER_TITLE_ATTR] = next.ordinal
        player.refreshTitledName() // stamp the new (colored, for nobles) name onto the appearance
        RankCapes.grant(player, next) // the rank's cape — the wearable mark of the new station
        RankEvents.fire(player, next) // quests / framework react (see LegacyRankHooks)
        return Result.Success
    }
}
