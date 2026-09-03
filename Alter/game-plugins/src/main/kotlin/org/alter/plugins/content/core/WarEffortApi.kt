package org.alter.plugins.content.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.WarEffortEvents
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.adminSetPoints
import org.alter.plugins.content.economy.points

private val logger = KotlinLogging.logger {}

/**
 * **War Effort** — the player's personal, lifetime service record (design authority 03 §4).
 * Earned through wars, skilling contracts, the depot, rogue content, quests, convoys; read by
 * rank eligibility, achievements, titles and leaderboards. **Never spent, never traded, never
 * lowered** by gameplay — [add] only ever climbs, and there is no `spend`.
 *
 * `addWarEffort(player, amount)` → [add]; `getWarEffort(player)` → [get].
 */
object WarEffortApi {

    fun get(p: Player): Int = p.points(PointKind.WAR_EFFORT)

    fun atLeast(p: Player, amount: Int): Boolean = get(p) >= amount

    /**
     * Credit [amount] War Effort to [p] for [source] (a short tag for the log: `"rogue_camp"`,
     * `"quest:last_free_city"`, `"convoy"`). Non-positive amounts are ignored. Returns the new total.
     * Every earn site — old or new — fires [onEarned].
     */
    fun add(p: Player, amount: Int, source: String): Int {
        if (amount <= 0) return get(p)
        val total = p.addPoints(PointKind.WAR_EFFORT, amount)
        logger.debug { "[WAR EFFORT] ${p.username} +$amount ($source) -> $total" }
        return total
    }

    /**
     * React to War Effort being earned by anyone, from any source (the depot, a war payout, a
     * contract, [add]). Listeners run in descending [priority] order, isolated.
     */
    fun onEarned(priority: Int = 0, listener: (Player, Int) -> Unit) = WarEffortEvents.onEarned(priority, listener)

    /**
     * **Admin/test only.** Overwrite the record (the one way it can go down). Logged as a warning
     * with the caller's name; never call this from content.
     */
    fun adminSet(p: Player, amount: Int, by: String): Int = p.adminSetPoints(PointKind.WAR_EFFORT, amount, by)
}
