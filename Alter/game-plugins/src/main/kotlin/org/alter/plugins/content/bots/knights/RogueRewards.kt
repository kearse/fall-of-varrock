package org.alter.plugins.content.bots.knights

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints

/**
 * **War Effort from the Rogue Knights** (design authority 03 §4: Rogue content is a listed War
 * Effort source; 05 §5: camps + named bosses must pay War Effort). One funnel, all numbers TUNE:
 *
 *  - [GATE_KILL_WE] per tier-rogue kill while a camp's clearance gate is still open (bounded by
 *    the camps' `clearGoal`s — 55 lifetime — so it needs no cap; 0 once the camp is thinned).
 *  - [CAMP_CLEAR_WE_SAFE] / [CAMP_CLEAR_WE_WILD] once per camp per player when the gate clears.
 *  - [FIRST_KILL_BASE] + [FIRST_KILL_PER_RANK] × rank on the kill that advances the ladder
 *    (Brack 10 … Vexmar 75; once by construction — the rank advances).
 *  - [REPEAT_BASE] + rank/2 on every farmed re-kill, capped at [REPEAT_DAILY_CAP_PER_BOSS] paying
 *    kills per knight per day and [REPEAT_DAILY_CAP_TOTAL_WE] War Effort per day across all of
 *    them (14 knights × 5 × ~5 would otherwise out-earn the whole daily bonus from farming alone).
 *
 * The full ladder is worth ≈ 800 lifetime War Effort — on par with the 880 of the 1000-kill
 * RogueHunt milestone track, which is the intent (the ladder is the harder half of the Rogue
 * road). Vexmar's first kill (75) is three captain bounties. No coins / Blood Money here.
 *
 * War Effort is a lifetime, never-spent stat: the only write is `Player.addPoints(WAR_EFFORT)`
 * (swap [pay] to `WarEffortApi.add` when the core facade lands — one line).
 */
object RogueRewards {

    // ---- TUNE ---------------------------------------------------------------------------------
    const val GATE_KILL_WE = 1
    const val CAMP_CLEAR_WE_SAFE = 15
    const val CAMP_CLEAR_WE_WILD = 30
    const val FIRST_KILL_BASE = 10
    const val FIRST_KILL_PER_RANK = 5
    const val REPEAT_BASE = 2
    const val REPEAT_DAILY_CAP_PER_BOSS = 5
    const val REPEAT_DAILY_CAP_TOTAL_WE = 60

    // ---- state (local persisted attrs, CampClearance pattern) ---------------------------------
    /** Per camp: the one-time clearance payout has been made. Checked as a FLAG, not the kill
     *  count, so a legacy save already past a goal (or a goal raised later) never double-pays. */
    private val clearPaidAttrs: Map<String, AttributeKey<Boolean>> =
        RogueKnights.CAMPS.associate { it.key to AttributeKey<Boolean>("rogue_camp_we_paid_${it.key}") }

    /** Epoch day the repeat-kill counters below belong to (lazy reset on day change). */
    private val REPEAT_DAY_ATTR = AttributeKey<Int>("rogue_boss_we_day")
    /** War Effort paid for repeat kills today (all knights). */
    private val REPEAT_TODAY_WE_ATTR = AttributeKey<Int>("rogue_boss_we_today")
    /** Paying repeat kills today, per knight. */
    private val repeatCountAttrs: Map<String, AttributeKey<Int>> =
        RogueKnights.LADDER.associate { it.key to AttributeKey<Int>("rogue_boss_we_${it.key}") }

    /** A tier rogue of [camp] fell to [p] while the camp's gate was still open (silent — the
     *  gate's own "N/goal thinned" line already reports the kill). */
    fun onGateKill(p: Player, camp: KnightCamp) {
        pay(p, GATE_KILL_WE, "thinning ${camp.display}", quiet = true)
    }

    /** [p] has just thinned [camp] — paid once per camp for life. */
    fun onCampCleared(p: Player, camp: KnightCamp) {
        val flag = clearPaidAttrs[camp.key] ?: return
        if (p.attr[flag] == true) return
        p.attr[flag] = true
        pay(p, if (camp.safe) CAMP_CLEAR_WE_SAFE else CAMP_CLEAR_WE_WILD, "${camp.display} thinned")
    }

    /** The kill that advanced [p] past [def] (once by construction). */
    fun onKnightFirstKill(p: Player, def: RogueKnightDef) {
        pay(p, FIRST_KILL_BASE + FIRST_KILL_PER_RANK * def.rank, "${def.name} beaten")
    }

    /** A farmed re-kill of an already-beaten knight — small, daily-capped per knight and in total. */
    fun onKnightRepeatKill(p: Player, def: RogueKnightDef) {
        rollDay(p)
        val countAttr = repeatCountAttrs[def.key] ?: return
        val count = p.attr[countAttr] ?: 0
        if (count >= REPEAT_DAILY_CAP_PER_BOSS) return
        val paidToday = p.attr[REPEAT_TODAY_WE_ATTR] ?: 0
        val amount = (REPEAT_BASE + def.rank / 2).coerceAtMost(REPEAT_DAILY_CAP_TOTAL_WE - paidToday)
        if (amount <= 0) return
        p.attr[countAttr] = count + 1
        p.attr[REPEAT_TODAY_WE_ATTR] = paidToday + amount
        pay(p, amount, "${def.name} falls again")
    }

    /** What a player would still earn from repeat kills today (for ::knights). */
    fun repeatBudgetLeft(p: Player): Int {
        rollDay(p)
        return (REPEAT_DAILY_CAP_TOTAL_WE - (p.attr[REPEAT_TODAY_WE_ATTR] ?: 0)).coerceAtLeast(0)
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun pay(p: Player, amount: Int, source: String, quiet: Boolean = false) {
        if (amount <= 0) return
        p.addPoints(PointKind.WAR_EFFORT, amount)
        if (!quiet) p.message("<col=4f9b4f>+$amount War Effort</col> ($source).")
    }

    /** Reset the repeat-kill counters when the epoch day has moved on. */
    private fun rollDay(p: Player) {
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        if (p.attr[REPEAT_DAY_ATTR] == today) return
        p.attr[REPEAT_DAY_ATTR] = today
        p.attr[REPEAT_TODAY_WE_ATTR] = 0
        repeatCountAttrs.values.forEach { p.attr.remove(it) }
    }
}
