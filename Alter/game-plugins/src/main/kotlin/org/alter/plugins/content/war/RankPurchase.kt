package org.alter.plugins.content.war

import org.alter.game.model.attr.PLAYER_TITLE_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.plugins.content.war.warprep.WarPrepSurvival
import org.alter.rscm.RSCM.getRSCM

/**
 * The single place a feudal rank is actually bought. Both purchase surfaces — Duke Horacio's
 * dialogue (the quest beats) and the client-drawn Feudal Ranks window ([RankMenuPlugin]) —
 * call [buy], so the coin check, the title bump, the name refresh and every quest hook stay
 * in lockstep no matter which UI sold the rank.
 */
object RankPurchase {

    sealed class Result {
        /** Rank bought; the player's [Player.title] is already the new rank. */
        object Success : Result()

        /** Not enough coins carried. */
        data class Insufficient(val cost: Int, val have: Int) : Result()

        /** [buy] was asked for a rank that isn't the player's next rung (ladder is one-at-a-time). */
        object NotNext : Result()

        /** Already King — nothing above to buy. */
        object Maxed : Result()
    }

    /**
     * Attempt to raise [player] to [target]. Only ever succeeds when [target] is exactly the
     * player's next rung — the ladder cannot be skipped, whichever UI asks.
     */
    fun buy(player: Player, target: Title): Result {
        val next = player.nextTitle ?: return Result.Maxed
        if (target != next) return Result.NotNext

        val coins = getRSCM("item.coins_995")
        val have = player.inventory.getItemCount(coins)
        if (have < next.cost) return Result.Insufficient(next.cost, have)

        player.inventory.remove(coins, next.cost)
        player.attr[PLAYER_TITLE_ATTR] = next.ordinal
        player.refreshTitledName() // stamp the new (colored, for nobles) name onto the appearance

        RecruitTrials.onBuyRank(player) // advances the intro quest's RANK step, if active
        WarPrepChain.onRankBought(player) // closes the War-Prep chain's RANK step, if active
        RogueProblem.begin(player)        // War-Prep's Squire rank-up opens Act II — start "The Rogue Problem"
        // (Rank purchases never close The Rogue Problem — its finish line is the full ladder.)
        WarPrepRanged.begin(player)       // opens War-Prep II once the ladder is cleared (RogueProblem.complete)
        WarPrepRanged.onRankBought(player)   // closes War-Prep II once Lord is reached
        WarPrepSurvival.begin(player)     // the Lord rank-up opens War-Prep III (gated on WarPrepRanged.complete)
        WarPrepSurvival.onRankBought(player) // closes War-Prep III once Minister is reached
        Conquest.begin(player)            // the King rank-up opens the endgame "King of Lumbridge" quest
        return Result.Success
    }
}
