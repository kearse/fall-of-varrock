package org.alter.plugins.content.minigames.duel

import org.alter.api.ChatMessageType
import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.World
import org.alter.game.model.entity.Player

/**
 * Server driver for the **themed confirmation screen** — the third screen of the classic
 * agreement flow, raised over the vanilla stake-confirm interface (group 334) while it is open.
 * The client overlay (`net.runelite.client.plugins.lofduel.LofDuelConfirmOverlay`) renders the
 * 2016-lesson layout: opponent name, combat level and individual combat stats, the "Before the
 * duel starts:" consequence lines, one "During the duel:" line per agreed rule, both stake lists
 * with `K/M (exact)` value expansions, and Accept with the change lockout.
 *
 * Data channels (design system §8 — no custom packets):
 *  - **[STATE_VARP] (4685)**: bit 0 = confirm screen open; the agreed rules re-published in the
 *    EXACT [DuelRulesClientMenu] toggle+slot bit layout (via [DuelRulesClientMenu.packRules]) so
 *    the client derives its rule lines from the agreed bits, not a re-parse; bit 30 = Accept
 *    lockout ("Wait…") — armed on open, cleared server-side by [tick].
 *  - **Opponent stats**: one machine CONSOLE line (`~LOFDUEL~stats|name|combat|a,s,d,h,r,m,p`),
 *    consumed client-side on arrival and display-filtered from the chat box.
 *  - **Item lists**: the overlay reads the confirm containers the trade screen already sends
 *    (key 90 / 90+32768) — nothing extra server-side.
 *
 * Raised/lowered by the [org.alter.plugins.content.mechanics.trading.impl.TradeSession] stake
 * confirm hook, wired in [DuelArenaPlugin.openStake]. The lockout the varp bit displays is
 * ENFORCED inside TradeSession — this object only drives the client presentation.
 */
object DuelConfirmScreen {
    /** Claimed in docs/overlay-design-system.md §8. Must match the client overlay. */
    const val STATE_VARP = 4685

    /** Container keys for the Show Inventories rule: the OPPONENT's backpack / worn gear,
     *  quantities masked to 1 (identities visible, stack sizes hidden — the classic semantics).
     *  Claimed beside the varp in docs/overlay-design-system.md §8. */
    const val OPP_BACKPACK_KEY = 4700
    const val OPP_WORN_KEY = 4701

    /** varp bit 30: Accept is locked ("Wait…"). Bits 1-29 are the rules layout; bit 0 = open. */
    private const val LOCK_BIT = 30

    /** Machine prefix of the opponent-stats CONSOLE line. Must match the client plugin. */
    const val STATS_PREFIX = "~LOFDUEL~"

    private class Entry(val player: Player, var lockUntil: Int)

    private val open = HashMap<Int, Entry>()

    /** Whether [STATE_VARP] is writable for [p] (see [DuelRulesClientMenu] / the varp ceiling). */
    private fun available(p: Player): Boolean = STATE_VARP < p.varps.maxVarps

    /** Raise the confirmation overlay for [p]: publish the agreed rules + entry lock, send the
     *  opponent's stats line. */
    fun open(p: Player, opponent: Player, rules: DuelRules) {
        if (p.index < 0 || !available(p)) return
        open[p.index] = Entry(p, p.world.currentCycle + DuelRulesClientMenu.CHANGE_LOCK_TICKS)
        p.setVarp(STATE_VARP, DuelRulesClientMenu.packRules(rules) or 1 or (1 shl LOCK_BIT))
        val s = opponent.getSkills()
        val stats = intArrayOf(
            Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS,
            Skills.RANGED, Skills.MAGIC, Skills.PRAYER,
        ).joinToString(",") { s.getBaseLevel(it).toString() }
        p.message("${STATS_PREFIX}stats|${opponent.username}|${opponent.combatLevel}|$stats", ChatMessageType.CONSOLE)
    }

    /** Lower the confirmation overlay for [p] (session ended — completed or declined). */
    fun close(p: Player) {
        open.remove(p.index)
        if (p.index >= 0 && available(p)) p.setVarp(STATE_VARP, 0)
    }

    /** Clear a player's "Wait…" bit once the entry lock expires. Ticked by [DuelArenaPlugin]. */
    fun tick(world: World) {
        open.values.forEach { e ->
            if (e.lockUntil != 0 && world.currentCycle >= e.lockUntil) {
                e.lockUntil = 0
                if (e.player.index >= 0 && available(e.player)) {
                    e.player.setVarp(STATE_VARP, e.player.varps.getState(STATE_VARP) and (1 shl LOCK_BIT).inv())
                }
            }
        }
    }
}
