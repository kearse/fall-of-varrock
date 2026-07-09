package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.game.model.attr.DROP_BONUS_PERCENT_ATTR
import org.alter.game.model.attr.WAR_EFFORT_BONUS_DAY_ATTR
import org.alter.game.model.attr.WAR_EFFORT_TODAY_ATTR
import org.alter.game.model.entity.Player

/**
 * **Daily War Effort bonus** (master design brief §3A — War Effort's daily-retention use). The War
 * Effort a player EARNS in a day (tracked separately from their spendable balance) unlocks tiered
 * bonuses to **skilling XP and monster drops** for the rest of that day — a "hit your war quota"
 * daily hook that rewards logging in and running contracts.
 *
 * Guardrails (master design brief §6): the bonus is **earned** (not bought), applies to **skilling
 * XP + monster drops only** — never to War Effort itself (which would feed back on itself) — and is
 * independent of the (future) paid membership/donor boost cap.
 */
object WarEffortBonus {

    private const val BASE_XP_RATE = 10.0 // matches Player.xpRate default

    /** (daily-earned threshold, XP bonus %, drop bonus %), highest first. TUNABLE. */
    private val TIERS = listOf(
        Triple(150, 15, 8),
        Triple(80, 10, 5),
        Triple(40, 5, 3),
        Triple(0, 0, 0),
    )

    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    private fun tierFor(earned: Int): Triple<Int, Int, Int> = TIERS.first { earned >= it.first }

    /** War Effort earned so far TODAY (0 if the stored total is from a previous day). */
    fun dailyEarned(p: Player): Int =
        if (p.attr[WAR_EFFORT_BONUS_DAY_ATTR] == today()) (p.attr[WAR_EFFORT_TODAY_ATTR] ?: 0) else 0

    /** Called whenever War Effort is earned (positive amount): tracks the daily total + re-applies. */
    fun recordEarned(p: Player, amount: Int) {
        if (amount <= 0) return
        rollDayIfStale(p)
        val before = tierFor(dailyEarned(p))
        val total = (p.attr[WAR_EFFORT_TODAY_ATTR] ?: 0) + amount
        p.attr[WAR_EFFORT_TODAY_ATTR] = total
        apply(p, total)
        val after = tierFor(total)
        if (after.first != before.first && (after.second > 0 || after.third > 0)) {
            p.message("<col=801700>War Effort bonus up!</col> Today's haul ($total) grants +${after.second}% XP and +${after.third}% drops for the rest of the day.")
        }
    }

    /** On login, reset if it's a new day, else re-apply today's tier (xpRate defaults to base on login). */
    fun applyOnLogin(p: Player) {
        rollDayIfStale(p)
        apply(p, dailyEarned(p))
    }

    private fun rollDayIfStale(p: Player) {
        if (p.attr[WAR_EFFORT_BONUS_DAY_ATTR] != today()) {
            p.attr[WAR_EFFORT_BONUS_DAY_ATTR] = today()
            p.attr[WAR_EFFORT_TODAY_ATTR] = 0
            apply(p, 0) // clear yesterday's bonus
        }
    }

    private fun apply(p: Player, earned: Int) {
        val (_, xpBonus, dropBonus) = tierFor(earned)
        p.xpRate = BASE_XP_RATE * (1.0 + xpBonus / 100.0)
        p.attr[DROP_BONUS_PERCENT_ATTR] = dropBonus
    }

    /** A status line for `::warbonus`. */
    fun status(p: Player): String {
        val earned = dailyEarned(p)
        val (_, xpBonus, dropBonus) = tierFor(earned)
        val next = TIERS.sortedBy { it.first }.firstOrNull { it.first > earned }
        val nextStr = if (next != null) " Next: ${next.first} War Effort → +${next.second}% XP / +${next.third}% drops." else " Max tier reached."
        return "Daily War Effort earned: <col=801700>$earned</col> → +$xpBonus% XP, +$dropBonus% drops today.$nextStr"
    }
}
