package org.alter.plugins.content.minigames.duel

import org.alter.api.EquipmentType
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/** Last agreed rules-grid state (packed toggle+slot bits), for the "Load last duel" preset. */
val DUEL_PRESET_ATTR = AttributeKey<Int>()

/**
 * Server driver for the **themed client-overlay** duel rules screen — a faithful rebuild of the
 * classic Duel Arena *Options* screen (`net.runelite.client.plugins.lofduel`): the combat-rule
 * checkboxes **and** the equipment paper-doll (click a worn slot to forbid it), plus a
 * "Load last duel" preset. Drawn by a client overlay, so no cache interface → no crash risk.
 *
 * Both players share one session; either side toggles, any change resets both accepts, both Accept →
 * [onAccepted] fires and the stake screen opens. No custom packets: state is one packed varp, and
 * clicks come back as `::duel …` (→ `duelclick` command).
 *
 * Packed [STATE_VARP] (per player — accept bits are that player's perspective):
 *   bit 0        open
 *   bits 1-12    the 12 rule toggles ([RULES])
 *   bit 13       this player accepted
 *   bit 14       opponent accepted
 *   bits 15-25   the 11 forbidden-equipment slots ([SLOT_IDS])
 */
object DuelRulesClientMenu {
    /** Must match the client overlay (LofDuelOverlay). */
    const val STATE_VARP = 4630
    const val RULE_COUNT = 12
    const val SLOT_COUNT = 11

    /** The 12 rule toggles, in order — MUST match the client overlay's RULES order. */
    val RULES = listOf(
        "No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
        "No Movement", "No Forfeit", "Whip only", "DDS only", "Fun weapons", "Allow companions",
    )

    /** Paper-doll slots (doll index → EquipmentType id) — MUST match the client overlay's SLOT order. */
    val SLOT_IDS = intArrayOf(
        EquipmentType.HEAD.id, EquipmentType.CAPE.id, EquipmentType.AMULET.id, EquipmentType.WEAPON.id,
        EquipmentType.CHEST.id, EquipmentType.SHIELD.id, EquipmentType.LEGS.id, EquipmentType.GLOVES.id,
        EquipmentType.BOOTS.id, EquipmentType.RING.id, EquipmentType.AMMO.id,
    )

    private class Session(val a: Player, val b: Player, val onAccepted: (DuelRules) -> Unit) {
        val rules = BooleanArray(RULE_COUNT)
        val slots = BooleanArray(SLOT_COUNT)
        var acceptedA = false
        var acceptedB = false
        var done = false
    }

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    fun open(a: Player, b: Player, onAccepted: (DuelRules) -> Unit) {
        val s = Session(a, b, onAccepted)
        sessions[a.index] = s
        sessions[b.index] = s
        publish(s)
    }

    fun toggle(p: Player, index: Int) {
        val s = sessions[p.index] ?: return
        if (index < 0 || index >= RULE_COUNT) return
        s.rules[index] = !s.rules[index]
        resetAccepts(s)
    }

    fun toggleSlot(p: Player, index: Int) {
        val s = sessions[p.index] ?: return
        if (index < 0 || index >= SLOT_COUNT) return
        s.slots[index] = !s.slots[index]
        resetAccepts(s)
    }

    /** Load the player's last-agreed rules preset into the live session. */
    fun loadPreset(p: Player) {
        val s = sessions[p.index] ?: return
        val preset = p.attr[DUEL_PRESET_ATTR] ?: run { p.message("You have no saved duel rules yet."); return }
        unpackInto(preset, s.rules, s.slots)
        resetAccepts(s)
    }

    private fun resetAccepts(s: Session) {
        // Like OSRS: ANY change revokes both accepts.
        s.acceptedA = false
        s.acceptedB = false
        publish(s)
    }

    fun accept(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.rules[0] && s.rules[1] && s.rules[2]) {
            p.message("You must leave at least one combat style available."); return
        }
        if (s.rules[7] && s.rules[0]) {
            p.message("No Forfeit can't be set with No Melee — a fighter could run out of ammo/runes."); return
        }
        if (p === s.a) s.acceptedA = true else s.acceptedB = true
        if (s.acceptedA && s.acceptedB) {
            s.done = true
            val packed = packState(s.rules, s.slots)
            s.a.attr[DUEL_PRESET_ATTR] = packed // remember for "Load last duel"
            s.b.attr[DUEL_PRESET_ATTR] = packed
            val rules = buildRules(s.rules, s.slots)
            close(s)
            s.onAccepted(rules)
        } else {
            publish(s)
        }
    }

    fun cancel(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.done) return
        s.done = true
        close(s)
        forEachSide(s) { side ->
            if (side.index >= 0) side.message(if (side === p) "You declined the duel." else "${p.username} declined the duel.")
        }
    }

    private fun close(s: Session) {
        forEachSide(s) { side ->
            sessions.remove(side.index)
            if (side.index >= 0) side.setVarp(STATE_VARP, 0)
        }
    }

    private fun publish(s: Session) {
        val base = packState(s.rules, s.slots)
        if (s.a.index >= 0) s.a.setVarp(STATE_VARP, base or 1 or acc(s.acceptedA, s.acceptedB))
        if (s.b.index >= 0) s.b.setVarp(STATE_VARP, base or 1 or acc(s.acceptedB, s.acceptedA))
    }

    /** Toggle+slot bits only (bit 0 open and accept bits added per-player in [publish]). */
    private fun packState(rules: BooleanArray, slots: BooleanArray): Int {
        var v = 0
        for (i in 0 until RULE_COUNT) if (rules[i]) v = v or (1 shl (i + 1))
        for (i in 0 until SLOT_COUNT) if (slots[i]) v = v or (1 shl (i + 15))
        return v
    }

    private fun unpackInto(packed: Int, rules: BooleanArray, slots: BooleanArray) {
        for (i in 0 until RULE_COUNT) rules[i] = (packed and (1 shl (i + 1))) != 0
        for (i in 0 until SLOT_COUNT) slots[i] = (packed and (1 shl (i + 15))) != 0
    }

    private fun acc(mine: Boolean, theirs: Boolean): Int =
        (if (mine) 1 shl 13 else 0) or (if (theirs) 1 shl 14 else 0)

    private inline fun forEachSide(s: Session, block: (Player) -> Unit) {
        if (s.a.index >= 0) block(s.a)
        if (s.b.index >= 0) block(s.b)
    }

    private fun buildRules(t: BooleanArray, slots: BooleanArray): DuelRules {
        val weapons = HashSet<Int>()
        val weaponLabels = ArrayList<String>()
        if (t[8]) { weapons += ids("item.abyssal_whip"); weaponLabels += "Whip only" }
        if (t[9]) { weapons += ids("item.dragon_dagger", "item.dragon_dagger_p", "item.dragon_dagger_p+", "item.dragon_dagger_p++"); weaponLabels += "DDS only" }
        if (t[10]) { weapons += ids("item.rubber_chicken", "item.stale_baguette", "item.giant_frog_legs", "item.mole_slippers", "item.frozen_whip_mix"); weaponLabels += "Fun weapons" }
        val disabled = SLOT_IDS.filterIndexed { i, _ -> slots[i] }.toSet()
        val gearLabel = when {
            disabled.size == SLOT_IDS.size -> "Boxing"
            weaponLabels.isNotEmpty() && disabled.isNotEmpty() -> weaponLabels.joinToString("/") + " + restricted gear"
            weaponLabels.isNotEmpty() -> weaponLabels.joinToString("/")
            disabled.isNotEmpty() -> "Restricted gear"
            else -> null
        }
        return DuelRules(
            noMelee = t[0], noRanged = t[1], noMagic = t[2],
            noPrayer = t[3], noFood = t[4], noDrinks = t[5], noMovement = t[6], noForfeit = t[7],
            allowCompanions = t[11],
            disabledSlots = disabled,
            allowedWeapons = weapons.takeIf { it.isNotEmpty() },
            gearLabel = gearLabel,
        )
    }

    private fun ids(vararg names: String): Set<Int> =
        names.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
}
