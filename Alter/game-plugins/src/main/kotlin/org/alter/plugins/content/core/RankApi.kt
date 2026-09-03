package org.alter.plugins.content.core

import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.CommandTier
import org.alter.plugins.content.war.RankEligibility
import org.alter.plugins.content.war.RankEvents
import org.alter.plugins.content.war.RankPurchase
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.canCommand
import org.alter.plugins.content.war.nextTitle
import org.alter.plugins.content.war.title

/**
 * **Rank** — standing and authority (design authority 03 §1): Peasant → Commoner → Squire → Soldier
 * → Knight → Lord → Minister → King. Quests introduce opportunities and may pay War Effort; they
 * **never** hard-gate or auto-grant a promotion — [promote] runs the same eligibility the Duke
 * does (coins + lifetime War Effort + milestones). Rank gates what a player may START
 * ([canCommand]) and wear; it never gates joining a war.
 *
 * Exact Minister/King thresholds are OPEN — today's War Effort floors are placeholders.
 */
object RankApi {

    fun rank(p: Player): Title = p.title
    fun nextRank(p: Player): Title? = p.nextTitle
    fun atLeast(p: Player, title: Title): Boolean = p.title.ordinal >= title.ordinal

    /** May [p] START wars of [tier]? (RAID = Lord+, CAMPAIGN = Minister+, CONQUEST = King.) */
    fun canCommand(p: Player, tier: CommandTier): Boolean = p.canCommand(tier)

    /** What [title] asks for (coins, War Effort floor, milestone flags). */
    fun requirements(title: Title): RankEligibility.Requirements = RankEligibility.requirements(title)

    /** Everything still between [p] and [title] (empty = eligible now; must be the next rung). */
    fun eligibility(p: Player, title: Title): List<RankEligibility.Unmet> = RankEligibility.check(p, title)
    fun isEligible(p: Player, title: Title): Boolean = RankEligibility.isEligible(p, title)
    fun describe(unmet: List<RankEligibility.Unmet>): String = RankEligibility.describeAll(unmet)

    /**
     * Raise [p] to [title] exactly as Duke Horacio would: eligibility checked, coins taken, name
     * and cape refreshed, [onRankChanged] listeners fired. Never skips a rung.
     */
    fun promote(p: Player, title: Title): RankPurchase.Result = RankPurchase.buy(p, title)

    /** React to any promotion (quests auto-begin on rank-up through this already). */
    fun onRankChanged(priority: Int = 0, listener: (Player, Title) -> Unit) = RankEvents.onRankBought(priority, listener)
}
