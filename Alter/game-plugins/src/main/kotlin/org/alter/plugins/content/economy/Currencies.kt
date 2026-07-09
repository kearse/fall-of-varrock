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
 */
enum class PointKind(val display: String, val attr: AttributeKey<Int>, val ticketKey: String? = null) {
    // BOSS/VOTE are now a tradeable, stackable ITEM currency (coins-like) — earned as ticket items,
    // spent directly in the reward shop via ItemCurrency (DECISIONS.md §8). The [attr] counter is
    // retained but vestigial for these two. WAR_EFFORT/PRESTIGE/DONOR stay pure counters.
    BOSS("Boss Tickets", BOSS_POINTS_ATTR, "item.boss_ticket"),
    VOTE("Vote Tickets", VOTE_POINTS_ATTR, "item.vote_ticket"),
    WAR_EFFORT("War Effort", WAR_EFFORT_POINTS_ATTR),
    PRESTIGE("Prestige", PRESTIGE_POINTS_ATTR),
    DONOR("Donor points", DONOR_POINTS_ATTR),
    // LMS points are a pure counter (like OSRS) spent at the Last Man Standing reward shop.
    LMS("LMS Points", LMS_POINTS_ATTR),
}

/** True if the player has the donor privilege power (the campaign donor-bonus + future donor perks). */
val Player.isDonor: Boolean get() = privilege.powers.contains(Privilege.DONOR_POWER.lowercase())

/** Current balance of [kind]. */
fun Player.points(kind: PointKind): Int = attr[kind.attr] ?: 0

/** Add (or, with a negative [amount], subtract) [kind] points; returns the new balance. */
fun Player.addPoints(kind: PointKind, amount: Int): Int {
    val next = (points(kind) + amount).coerceAtLeast(0)
    attr[kind.attr] = next
    // War Effort earned (not spent) feeds the daily XP/drop bonus (master design brief §3A).
    if (kind == PointKind.WAR_EFFORT && amount > 0) WarEffortBonus.recordEarned(this, amount)
    return next
}

/** Spend [amount] of [kind] if affordable; returns true on success (balance debited). */
fun Player.spendPoints(kind: PointKind, amount: Int): Boolean {
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
