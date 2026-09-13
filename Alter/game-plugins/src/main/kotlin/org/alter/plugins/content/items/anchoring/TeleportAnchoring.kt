package org.alter.plugins.content.items.anchoring

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **Teleport anchoring** — the permanent unlock a [teleport anchoring scroll][SCROLL] grants.
 *
 * The scroll (item 29455, "A formidable magic spell which prevents unwanted teleportation.") is a
 * rare drop from every zombie in the drop dump — 110 npc rows carry it — and until 2026-09-13 it
 * had no binding at all: reading it did nothing, which is what the player report said. It is the
 * only anti-forced-teleport item in the game.
 *
 * Once read, the unlock is **permanent and account-wide** (persisted): the scroll is consumed and
 * the player is anchored from then on. There is no toggle, because there is nothing here you would
 * want to turn it back off for — see below.
 *
 * **What it actually stops.** The one mechanic on this server that teleports a player against
 * their will is the Chaos Elemental's displacement attack (`bosses/wilderness/
 * WildernessBossesCombatPlugin.chaosElementalCombat`), which yanks you 1–4 tiles off your square
 * mid-fight — in deep Wilderness, frequently into another PKer's lap. An anchored player takes the
 * attack but keeps their footing. The Elemental's *disarm* is deliberately NOT covered: that is a
 * separate mechanic with its own counter (keep your inventory full), and the scroll is an anchor,
 * not a shield.
 *
 * Kept as a plain object with a single [isAnchored] predicate so any future forced-teleport
 * mechanic can honour it in one line, rather than each boss re-implementing the check.
 */
object TeleportAnchoring {

    const val SCROLL = "item.teleport_anchoring_scroll"

    /** Permanent, account-wide, survives death — the scroll is spent to set it. */
    val ANCHORED_ATTR = AttributeKey<Boolean>(persistenceKey = "teleport_anchored")

    /** True once the player has read a scroll. Call this before any involuntary teleport of [p]. */
    fun isAnchored(p: Player): Boolean = p.attr[ANCHORED_ATTR] == true

    /**
     * Apply the anchor to a forced teleport: returns true when [p] resisted (and tells them so),
     * false when the mover should go ahead. The message names the anchor rather than the attacker
     * so it reads the same wherever it is used.
     */
    fun resists(p: Player): Boolean {
        if (!isAnchored(p)) return false
        p.message("<col=7f007f>Your teleport anchor holds you in place.</col>")
        return true
    }
}
