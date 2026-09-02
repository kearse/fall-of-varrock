package org.alter.plugins.content.war

import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.points
import org.alter.plugins.content.mechanics.Flags
import org.alter.rscm.RSCM.getRSCM

/**
 * **Rank eligibility** — what a player must have before the Duke will raise them to a rank
 * (design authority §5: ranks measure standing; coins may remain a sink, but promotion is not a
 * simple coin or quest shortcut). One data table, one check, usable by [RankPurchase], the
 * Duke's dialogue, `::title`, and Block-2 quests ("is this player eligible for Lord yet?").
 *
 * Requirements per rank:
 *  - **coins** — the classic price ([Title.cost]), still the sink;
 *  - **War Effort** — a floor of lifetime service ([PointKind.WAR_EFFORT], never spent);
 *  - **flags** — milestones such as [Flags.Known.VETERAN_OF_VARROCK].
 *
 * The War Effort floors below are Block-1 **placeholders (TUNE)** so promotion actually asks
 * for service today; exact thresholds are a Block-2 balance decision. Minister's Veteran-of-
 * Varrock slot is reserved but NOT enforced until the first major assault exists to award it.
 */
object RankEligibility {

    data class Requirements(
        val coins: Int,
        val warEffort: Int = 0,
        /** (flag key, player-facing label) */
        val flags: List<Pair<String, String>> = emptyList(),
    )

    sealed class Unmet {
        data class Coins(val need: Int, val have: Int) : Unmet()
        data class WarEffort(val need: Int, val have: Int) : Unmet()
        data class Flag(val flag: String, val label: String) : Unmet()
        data class NotNext(val next: Title?) : Unmet()
        object Maxed : Unmet()
    }

    /** Lifetime War Effort a player must have EARNED to be raised to each rank. TUNE. */
    private val WAR_EFFORT_FLOOR: Map<Title, Int> = mapOf(
        Title.SOLDIER to 50,
        Title.KNIGHT to 150,
        Title.LORD to 500,
        Title.MINISTER to 1_500,
        Title.KING to 4_000,
    )

    /** Milestone flags per rank. Minister's Veteran of Varrock is reserved for Block 2 — see the
     *  class doc — so the table is empty today. */
    private val FLAGS: Map<Title, List<Pair<String, String>>> = mapOf(
        // Title.MINISTER to listOf(Flags.Known.VETERAN_OF_VARROCK to "Veteran of Varrock"),
    )

    fun requirements(title: Title): Requirements =
        Requirements(coins = title.cost, warEffort = WAR_EFFORT_FLOOR[title] ?: 0, flags = FLAGS[title] ?: emptyList())

    /**
     * Everything still standing between [p] and [title]. Empty = eligible. Ladder rules first
     * (must be exactly the next rung), then coins carried, War Effort earned, milestone flags.
     */
    fun check(p: Player, title: Title): List<Unmet> {
        val next = p.nextTitle ?: return listOf(Unmet.Maxed)
        if (title != next) return listOf(Unmet.NotNext(next))
        val req = requirements(title)
        val out = ArrayList<Unmet>()
        val have = p.inventory.getItemCount(coinId)
        if (have < req.coins) out += Unmet.Coins(req.coins, have)
        val we = p.points(PointKind.WAR_EFFORT)
        if (we < req.warEffort) out += Unmet.WarEffort(req.warEffort, we)
        req.flags.forEach { (flag, label) -> if (!Flags.has(p, flag)) out += Unmet.Flag(flag, label) }
        return out
    }

    fun isEligible(p: Player, title: Title): Boolean = check(p, title).isEmpty()

    /** A player-facing phrase for one shortfall. */
    fun describe(u: Unmet): String = when (u) {
        is Unmet.Coins -> "${fmt(u.need)} coins (you carry ${fmt(u.have)})"
        is Unmet.WarEffort -> "${fmt(u.need)} lifetime War Effort (you have ${fmt(u.have)})"
        is Unmet.Flag -> u.label
        is Unmet.NotNext -> u.next?.let { "the rank of ${it.display} first" } ?: "no further rank"
        Unmet.Maxed -> "no further rank"
    }

    fun describeAll(unmet: List<Unmet>): String = unmet.joinToString(", ") { describe(it) }

    /** "150,000 coins · 150 War Effort" — the full requirement line for [title], with progress. */
    fun summary(p: Player, title: Title): String {
        val req = requirements(title)
        val parts = ArrayList<String>()
        parts += "${fmt(req.coins)} coins (carrying ${fmt(p.inventory.getItemCount(coinId))})"
        if (req.warEffort > 0) parts += "${fmt(req.warEffort)} lifetime War Effort (have ${fmt(p.points(PointKind.WAR_EFFORT))})"
        req.flags.forEach { (flag, label) -> parts += "$label (${if (Flags.has(p, flag)) "earned" else "not yet"})" }
        return parts.joinToString(" · ")
    }

    private val coinId: Int by lazy { getRSCM("item.coins_995") }
    private fun fmt(n: Int): String = "%,d".format(n)
}
