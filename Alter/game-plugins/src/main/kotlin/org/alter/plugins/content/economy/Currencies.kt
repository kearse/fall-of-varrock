package org.alter.plugins.content.economy

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.BOSS_POINTS_ATTR
import org.alter.game.model.attr.DONOR_POINTS_ATTR
import org.alter.game.model.attr.LMS_POINTS_ATTR
import org.alter.game.model.attr.PRESTIGE_POINTS_ATTR
import org.alter.game.model.attr.VOTE_POINTS_ATTR
import org.alter.game.model.attr.WAR_EFFORT_POINTS_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.rscm.RSCM.getRSCM

/**
 * The reward-point currencies of the economy (content-first economy, see the roadmap).
 * Points are persistent per-player counters (NOT inventory items) spent at point reward
 * shops via [PointsCurrency]. Earned from content: Slayer tasks, PvM, voting.
 *
 * gp ([item.coins_995]) and Blood Money ([item.blood_money]) stay as inventory items
 * (use the existing ItemCurrency for those); points are counters so they can't be
 * dropped/traded/duped and are simple to award from any plugin.
 *
 * A kind with [spendable] = false is a **lifetime record**, never a balance: it only ever
 * goes up ([Player.addPoints] with a positive amount) and no shop or sink may debit it.
 * **War Effort** is the one such record — the player's personal lifetime service to the
 * kingdom (design authority §8), read by rank eligibility and the daily bonus, never spent.
 * The shared consumable stockpile the commanders spend is a different thing entirely:
 * Realm Supplies ([org.alter.plugins.content.war.RealmSupply]).
 */
enum class PointKind(
    val display: String,
    val attr: AttributeKey<Int>,
    val ticketKey: String? = null,
    /** False = a lifetime record that can never be debited (see the class doc). */
    val spendable: Boolean = true,
) {
    // BOSS/VOTE are now a tradeable, stackable ITEM currency (coins-like) — earned as ticket items,
    // spent directly in the reward shop via ItemCurrency (DECISIONS.md §8). The [attr] counter is
    // retained but vestigial for these two. PRESTIGE/DONOR stay pure counters.
    BOSS("Boss Tickets", BOSS_POINTS_ATTR, "item.boss_ticket"),
    VOTE("Vote Tickets", VOTE_POINTS_ATTR, "item.vote_ticket"),
    /** Lifetime service record — earned by wars, skilling, contracts, rogue hunting; NEVER spent. */
    WAR_EFFORT("War Effort", WAR_EFFORT_POINTS_ATTR, spendable = false),
    PRESTIGE("Prestige", PRESTIGE_POINTS_ATTR),
    DONOR("Donor points", DONOR_POINTS_ATTR),
    // LMS points are a pure counter (like OSRS) spent at the Last Man Standing reward shop.
    LMS("LMS Points", LMS_POINTS_ATTR),
}

/** True if the player has the donor privilege power (the campaign donor-bonus + future donor perks). */
val Player.isDonor: Boolean get() = privilege.powers.contains(Privilege.DONOR_POWER.lowercase())

/** Current balance of [kind]. */
fun Player.points(kind: PointKind): Int = attr[kind.attr] ?: 0

/** Add (or, with a negative [amount], subtract) [kind] points; returns the new balance. A
 *  non-[PointKind.spendable] record ignores negative amounts (it only ever climbs). */
fun Player.addPoints(kind: PointKind, amount: Int): Int {
    if (amount < 0 && !kind.spendable) return points(kind)
    val next = (points(kind) + amount).coerceAtLeast(0)
    attr[kind.attr] = next
    if (kind == PointKind.WAR_EFFORT && amount > 0) {
        // War Effort earned feeds the daily XP/drop bonus (master design brief §3A) …
        WarEffortBonus.recordEarned(this, amount)
        // … and every earn site, old or new, reaches the observers (achievements, quests, boards).
        WarEffortEvents.fire(this, amount)
    }
    return next
}

/**
 * **Admin/test only** — overwrite a point counter outright, the one path that can LOWER a
 * non-spendable record such as War Effort. Logged as a warning naming [by] so it never hides.
 * `::setpoints` and `WarEffortApi.adminSet` route here; content never calls it.
 */
fun Player.adminSetPoints(kind: PointKind, amount: Int, by: String): Int {
    val before = points(kind)
    val next = amount.coerceAtLeast(0)
    attr[kind.attr] = next
    warEffortLogger.warn { "[ADMIN] $by set ${kind.display} of $username from $before to $next" }
    return next
}

private val warEffortLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger("WarEffortEvents")

/**
 * **War Effort earned** observers. Fired from [Player.addPoints] for every positive War Effort
 * credit — the single choke point all nine earn sites already pass through — so a listener sees
 * the depot, war payouts, contracts, bounties, quest rewards and `WarEffortApi.add` alike.
 * Descending [priority], each isolated. Register from any plugin `init`.
 */
object WarEffortEvents {
    private class Listener(val priority: Int, val fn: (Player, Int) -> Unit)

    private val listeners = ArrayList<Listener>()

    fun onEarned(priority: Int = 0, listener: (Player, Int) -> Unit) {
        listeners += Listener(priority, listener)
        listeners.sortByDescending { it.priority }
    }

    internal fun fire(p: Player, amount: Int) {
        for (l in listeners.toList()) {
            runCatching { l.fn(p, amount) }
                .onFailure { warEffortLogger.error(it) { "War Effort listener failed for ${p.username} (+$amount)" } }
        }
    }
}

/** Spend [amount] of [kind] if affordable; returns true on success (balance debited). Always false
 *  for a non-[PointKind.spendable] record — War Effort is a service record, not a purse. */
fun Player.spendPoints(kind: PointKind, amount: Int): Boolean {
    if (!kind.spendable) return false
    if (amount <= 0) return true
    if (points(kind) < amount) return false
    attr[kind.attr] = points(kind) - amount
    return true
}

/**
 * Award the tradeable **ticket-currency** item for [kind] (BOSS/VOTE only) — coins-like money the
 * player spends directly in the reward shop (DECISIONS.md §8). Added to the inventory; any overflow
 * drops at the player's feet so a full pack never voids it (same policy as coin payouts). Kinds with
 * no ticket item (WAR_EFFORT/PRESTIGE/DONOR) are a no-op.
 */
fun Player.awardTickets(kind: PointKind, amount: Int) {
    if (amount <= 0) return
    val id = kind.ticketKey?.let { runCatching { getRSCM(it) }.getOrNull() } ?: return
    val added = inventory.add(item = id, amount = amount, assureFullInsertion = false)
    val leftover = amount - added.completed
    if (leftover > 0) world.spawn(GroundItem(id, leftover, tile, this))
}
