package org.alter.plugins.content.minigames.duel

import org.alter.api.EquipmentType
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Server driver for the **themed client-overlay** duel rules screen
 * (`net.runelite.client.plugins.lofduel`). Same faithful flow as the cache-interface
 * [DuelRulesScreen] — both players see the same grid, either side toggles, any change resets both
 * accepts, both Accept → [onAccepted] fires and the stake screen opens — but it's drawn by a client
 * overlay instead of a cache interface, so there's **no JS5-load crash risk** (the reason the cache
 * grid stayed disabled).
 *
 * No custom packets: state is published to each player via one packed varp [STATE_VARP], and the
 * overlay's toggle / accept / decline clicks come back as `::duel …` public chat, intercepted in
 * `MessagePublicHandler` → the `duelclick` command → [toggle]/[accept]/[cancel].
 *
 * Packed [STATE_VARP] (per player — the accept bits are from THAT player's perspective):
 *   bit 0       open   (1 = show the overlay)
 *   bits 1-12   the 12 rule toggles (see [LABELS])
 *   bit 13      this player has accepted
 *   bit 14      the opponent has accepted
 */
object DuelRulesClientMenu {
    /** Must match the client overlay (net.runelite.client.plugins.lofduel LofDuelOverlay). */
    const val STATE_VARP = 4630
    const val TOGGLES = 12

    /** The 12 rule toggles, in grid order — MUST match the client overlay's label order. */
    val LABELS = listOf(
        "No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
        "No Movement", "Boxing (no gear)", "Whip only", "DDS only", "Fun weapons", "Allow companions",
    )

    private class Session(val a: Player, val b: Player, val onAccepted: (DuelRules) -> Unit) {
        val toggles = BooleanArray(TOGGLES)
        var acceptedA = false
        var acceptedB = false
        var done = false
    }

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    /** Open the shared rules overlay for both players. */
    fun open(a: Player, b: Player, onAccepted: (DuelRules) -> Unit) {
        val s = Session(a, b, onAccepted)
        sessions[a.index] = s
        sessions[b.index] = s
        publish(s)
    }

    fun toggle(p: Player, index: Int) {
        val s = sessions[p.index] ?: return
        if (index < 0 || index >= TOGGLES) return
        s.toggles[index] = !s.toggles[index]
        // Like OSRS: ANY rule change revokes both accepts (nobody gets rule-switched at the buzzer).
        s.acceptedA = false
        s.acceptedB = false
        publish(s)
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
            close(s)
            s.onAccepted(rules)
        } else {
            publish(s)
        }
    }

    /** Decline / close / logout by either side — tear down for both. */
    fun cancel(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.done) return
        s.done = true
        close(s)
        forEachSide(s) { side ->
            if (side.index >= 0) {
                side.message(if (side === p) "You declined the duel." else "${p.username} declined the duel.")
            }
        }
    }

    /** Clear the overlay for both sides (open bit → 0 hides it) and drop the session. */
    private fun close(s: Session) {
        forEachSide(s) { side ->
            sessions.remove(side.index)
            if (side.index >= 0) side.setVarp(STATE_VARP, 0)
        }
    }

    private fun publish(s: Session) {
        var toggleBits = 0
        for (i in 0 until TOGGLES) if (s.toggles[i]) toggleBits = toggleBits or (1 shl (i + 1))
        if (s.a.index >= 0) s.a.setVarp(STATE_VARP, pack(toggleBits, s.acceptedA, s.acceptedB))
        if (s.b.index >= 0) s.b.setVarp(STATE_VARP, pack(toggleBits, s.acceptedB, s.acceptedA))
    }

    private fun pack(toggleBits: Int, mine: Boolean, theirs: Boolean): Int {
        var v = 1 or toggleBits // bit 0 = open
        if (mine) v = v or (1 shl 13)
        if (theirs) v = v or (1 shl 14)
        return v
    }

    private inline fun forEachSide(s: Session, block: (Player) -> Unit) {
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
