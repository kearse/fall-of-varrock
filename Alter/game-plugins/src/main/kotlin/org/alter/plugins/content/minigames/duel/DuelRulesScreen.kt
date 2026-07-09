package org.alter.plugins.content.minigames.duel

import org.alter.api.EquipmentType
import org.alter.api.InterfaceDestination
import org.alter.api.ext.closeInterface
import org.alter.api.ext.message
import org.alter.api.ext.openInterface
import org.alter.api.ext.setComponentHidden
import org.alter.api.ext.setComponentText
import org.alter.api.ext.setInterfaceUnderlay
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Server driver for the **Duel Arena rules-grid** interface (cache group [IFACE], authored by
 * `org.alter.tools.duelrules.DuelRulesCacheTool` — component ids MUST stay in sync with it).
 *
 * The faithful classic flow: after a mutual challenge, BOTH players see the same grid; **either**
 * side clicks toggles (checkbox fills green, syncs live to both, and — like OSRS — any change
 * resets both accepts), then both click **Accept** → [onAccepted] fires with the built [DuelRules]
 * and the stake screen opens. Decline/close/logout by either side cancels for both.
 *
 * One [RulesSession] is shared by the pair (keyed under both player indexes).
 */
object DuelRulesScreen {
    const val IFACE = 1020
    const val TITLE_TXT = 4
    const val SUBTITLE_TXT = 8
    const val TICK_BASE = 21
    const val LBL_BASE = 33
    const val TOGGLES = 12
    const val STATUS_TXT = 45
    const val CLOSE_HIT = 54
    const val RULE_HIT_BASE = 55
    const val ACCEPT_HIT = 67
    const val DECLINE_HIT = 68
    private const val POPULATE_DELAY = 2 // ticks between open and populate (JS5 load race — TabbedPanel lesson)

    /** The 12 rule toggles, in grid order (col-major: 0..5 left column, 6..11 right). */
    private val LABELS = listOf(
        "No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
        "No Movement", "Boxing (no gear)", "Whip only", "DDS only", "Fun weapons", "Allow companions",
    )

    private class RulesSession(val a: Player, val b: Player, val onAccepted: (DuelRules) -> Unit) {
        val toggles = BooleanArray(TOGGLES)
        var acceptedA = false
        var acceptedB = false
        var done = false // set when completing/cancelling, so the close event doesn't double-fire
        fun other(p: Player): Player = if (p === a) b else a
    }

    private val sessions = HashMap<Int, RulesSession>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    fun open(a: Player, b: Player, onAccepted: (DuelRules) -> Unit) {
        val s = RulesSession(a, b, onAccepted)
        sessions[a.index] = s
        sessions[b.index] = s
        listOf(a, b).forEach { p ->
            p.setInterfaceUnderlay(color = -1, transparency = -1)
            p.openInterface(IFACE, InterfaceDestination.MAIN_SCREEN)
        }
        // Custom interfaces load ASYNC over JS5 — populating same-tick races the load (NPE/logout).
        a.world.queue {
            wait(POPULATE_DELAY)
            if (sessions[a.index] !== s) return@queue
            populate(s)
        }
    }

    private fun populate(s: RulesSession) {
        forEachSide(s) { p ->
            p.setComponentText(IFACE, TITLE_TXT, "Duel Rules — vs ${s.other(p).username}")
            for (i in 0 until TOGGLES) {
                val label = LABELS.getOrNull(i)
                p.setComponentText(IFACE, LBL_BASE + i, label ?: "")
                p.setComponentHidden(IFACE, TICK_BASE + i, !s.toggles[i])
                if (label == null) p.setComponentHidden(IFACE, RULE_HIT_BASE + i, true)
            }
        }
        syncStatus(s)
    }

    fun toggle(p: Player, index: Int) {
        val s = sessions[p.index] ?: return
        if (index >= LABELS.size) return
        s.toggles[index] = !s.toggles[index]
        // Like OSRS: ANY rule change revokes both accepts (nobody gets rule-switched at the buzzer).
        s.acceptedA = false
        s.acceptedB = false
        forEachSide(s) { side ->
            side.setComponentHidden(IFACE, TICK_BASE + index, !s.toggles[index])
        }
        syncStatus(s)
    }

    fun accept(p: Player) {
        val s = sessions[p.index] ?: return
        // A duel with every style blocked can never end — refuse the accept (classic behaviour).
        if (s.toggles[0] && s.toggles[1] && s.toggles[2]) {
            p.message("You must leave at least one combat style available.")
            return
        }
        if (p === s.a) s.acceptedA = true else s.acceptedB = true
        if (s.acceptedA && s.acceptedB) {
            s.done = true
            val rules = buildRules(s.toggles)
            forEachSide(s) { side ->
                sessions.remove(side.index)
                side.closeInterface(IFACE)
            }
            s.onAccepted(rules)
        } else {
            syncStatus(s)
        }
    }

    /** Decline/close/logout by either side — tear down for both. [silent] player initiated it. */
    fun cancel(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.done) return
        s.done = true
        forEachSide(s) { side ->
            sessions.remove(side.index)
            if (side.index >= 0) {
                side.closeInterface(IFACE)
                side.message(if (side === p) "You declined the duel." else "${p.username} declined the duel.")
            }
        }
    }

    /** The interface was closed client-side (esc / other UI) — treat as a decline unless completing. */
    fun onClosed(p: Player) {
        val s = sessions[p.index] ?: return
        if (!s.done) cancel(p)
        sessions.remove(p.index)
    }

    private fun syncStatus(s: RulesSession) {
        val status = when {
            s.acceptedA || s.acceptedB -> "Other player has accepted — waiting for you."
            else -> "" // nothing pending
        }
        forEachSide(s) { p ->
            val mine = if (p === s.a) s.acceptedA else s.acceptedB
            val theirs = if (p === s.a) s.acceptedB else s.acceptedA
            p.setComponentText(IFACE, STATUS_TXT, when {
                mine && !theirs -> "Waiting for the other player..."
                !mine && theirs -> "Other player has accepted."
                else -> status
            })
        }
    }

    private inline fun forEachSide(s: RulesSession, block: (Player) -> Unit) {
        if (s.a.index >= 0) block(s.a)
        if (s.b.index >= 0) block(s.b)
    }

    /** Map the toggle grid to a [DuelRules]. Weapon toggles UNION into the whitelist. */
    private fun buildRules(t: BooleanArray): DuelRules {
        val weapons = HashSet<Int>()
        val weaponLabels = ArrayList<String>()
        if (t[8]) { weapons += ids("item.abyssal_whip"); weaponLabels += "Whip only" }
        if (t[9]) { weapons += ids("item.dragon_dagger", "item.dragon_dagger_p", "item.dragon_dagger_p+", "item.dragon_dagger_p++"); weaponLabels += "DDS only" }
        if (t[10]) { weapons += ids("item.rubber_chicken", "item.stale_baguette", "item.giant_frog_legs", "item.mole_slippers", "item.frozen_whip_mix"); weaponLabels += "Fun weapons" }
        val gearLabel = when {
            t[7] -> "Boxing"
            weaponLabels.isNotEmpty() -> weaponLabels.joinToString("/")
            else -> null
        }
        return DuelRules(
            noMelee = t[0], noRanged = t[1], noMagic = t[2],
            noPrayer = t[3], noFood = t[4], noDrinks = t[5], noMovement = t[6],
            allowCompanions = t[11],
            disabledSlots = if (t[7]) EquipmentType.values.map { it.id }.toSet() else emptySet(),
            allowedWeapons = weapons.takeIf { it.isNotEmpty() },
            gearLabel = gearLabel,
        )
    }

    private fun ids(vararg names: String): Set<Int> =
        names.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
}
