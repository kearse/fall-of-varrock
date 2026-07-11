package org.alter.plugins.content.war.roguehunt

import org.alter.api.ext.message
import org.alter.game.model.attr.ROGUE_KILLS_ATTR
import org.alter.game.model.attr.ROGUE_MILESTONE_PAID_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.rscm.RSCM.getRSCM

/**
 * **Rogue hunting** (story-and-grind-design §4) — the solo hunter's parallel track. Fallen
 * Varrock's streets crawl with the rogue family (rogues, muggers, highwaymen, thugs, bandits,
 * outlaws); every kill counts toward lifetime **milestones** the Recruiting Sergeant pays out
 * in coin + War Effort. The counter survives death — the wilderness risk is the run itself,
 * never the progress.
 *
 * Kills are counted by [RogueHuntPlugin]'s death hook; payouts happen in the Sergeant's
 * dialogue (so the milestone moment routes players back to the character who trained them).
 * `::rogues` reports progress.
 */
object RogueHunt {

    data class Milestone(val kills: Int, val coins: Int, val warEffort: Int)

    /** Escalating lifetime milestones. TUNE freely — the top rung earns a Squire's ladder rung. */
    val MILESTONES = listOf(
        Milestone(10, 10_000, 20),
        Milestone(50, 40_000, 60),
        Milestone(250, 150_000, 200),
        Milestone(1_000, 500_000, 600),
    )

    /** Name fragments that mark the rogue family — Varrock's human occupiers. TUNE. */
    private val FAMILY = listOf("rogue", "mugger", "highwayman", "thug", "bandit", "outlaw")

    fun isRogue(npcName: String?): Boolean =
        npcName != null && FAMILY.any { npcName.contains(it, ignoreCase = true) }

    fun kills(p: Player): Int = p.attr[ROGUE_KILLS_ATTR] ?: 0

    private fun paidCount(p: Player): Int = p.attr[ROGUE_MILESTONE_PAID_ATTR] ?: 0

    /** The next milestone still ahead of (or unpaid for) [p], or null if all are done. */
    fun nextMilestone(p: Player): Milestone? = MILESTONES.getOrNull(paidCount(p))

    /** True if [p] has reached a milestone the Sergeant hasn't paid yet. */
    fun hasUnpaid(p: Player): Boolean = nextMilestone(p)?.let { kills(p) >= it.kills } == true

    /** Count a rogue-family kill; nudge the hunter when it crosses a milestone. */
    fun onKill(p: Player) {
        val kills = kills(p) + 1
        p.attr[ROGUE_KILLS_ATTR] = kills
        val next = nextMilestone(p) ?: return
        if (kills == next.kills) {
            p.message("<col=801700>Rogue hunting:</col> $kills cutthroats down — a milestone! <col=ffae00>Report to the Recruiting Sergeant for your bounty.</col>")
        }
    }

    /**
     * Pay every reached-but-unpaid milestone (coins to the pack, overflowing to the bank, + War
     * Effort), advancing the paid marker. Returns the milestones paid this visit (usually 0-1).
     */
    fun payout(p: Player): List<Milestone> {
        val paid = ArrayList<Milestone>()
        while (true) {
            val next = nextMilestone(p) ?: break
            if (kills(p) < next.kills) break
            giveCoins(p, next.coins)
            p.addPoints(PointKind.WAR_EFFORT, next.warEffort)
            p.attr[ROGUE_MILESTONE_PAID_ATTR] = paidCount(p) + 1
            p.message("<col=4f9b4f>Rogue bounty (${next.kills} kills):</col> +${"%,d".format(next.coins)} coins and +${next.warEffort} War Effort.")
            paid += next
        }
        return paid
    }

    /** One-line progress report (`::rogues` and the Sergeant's idle chatter). */
    fun statusLine(p: Player): String {
        val kills = kills(p)
        val next = nextMilestone(p)
            ?: return "Rogues slain: <col=801700>$kills</col> — every bounty claimed. The streets fear you."
        return "Rogues slain: <col=801700>$kills</col>. Next bounty at <col=ffae00>${next.kills}</col> (${"%,d".format(next.coins)} coins)."
    }

    /** Pay coins into the pack, overflowing to the bank (mirrors ResourceContracts). */
    private fun giveCoins(p: Player, amount: Int) {
        runCatching {
            val id = getRSCM("item.coins_995")
            val tx = p.inventory.add(id, amount, assureFullInsertion = false)
            val left = amount - tx.completed
            if (left > 0) p.bank.add(id, left)
        }
    }
}
