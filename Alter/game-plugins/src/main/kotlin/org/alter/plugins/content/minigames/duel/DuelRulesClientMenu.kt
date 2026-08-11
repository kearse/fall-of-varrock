package org.alter.plugins.content.minigames.duel

import org.alter.api.EquipmentType
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/** Last AGREED rules-grid state (packed toggle+slot bits) — the "Last" preset. Persists. */
val DUEL_PRESET_ATTR = AttributeKey<Int>(persistenceKey = "duel_last_rules")

/** The player's explicitly SAVED rules preset (packed toggle+slot bits) — Save/Load. Persists. */
val DUEL_SAVED_PRESET_ATTR = AttributeKey<Int>(persistenceKey = "duel_saved_rules")

/**
 * Server driver for the **themed client-overlay** duel rules screen — a faithful rebuild of the
 * classic Duel Arena *Options* screen (`net.runelite.client.plugins.lofduel`): the combat-rule
 * checkboxes **and** the equipment paper-doll (click a worn slot to forbid it), plus the classic
 * preset buttons — the official **Whip** and **Boxing** rule sets and per-player **Save / Load /
 * Load-last-duel** (2015 Rework feature set). Drawn by a client overlay, so no cache interface →
 * no crash risk.
 *
 * Both players share one session; either side toggles, any change resets both accepts, both Accept →
 * [onAccepted] fires and the stake screen opens. No custom packets: state is one packed varp, and
 * clicks come back as `::duel …` (→ `duelclick` command).
 *
 * Packed [STATE_VARP] (per player — accept bits are that player's perspective):
 *   bit 0        open
 *   bits 1-16    rule toggles ([RULES]; [RULE_BITS] bits reserved so Phase 3/4 additions — Show
 *                Inventories, Obstacles — won't reshuffle the layout again)
 *   bit 17       this player accepted
 *   bit 18       opponent accepted
 *   bits 19-29   the 11 forbidden-equipment slots ([SLOT_IDS])
 */
object DuelRulesClientMenu {
    /** Must match the client overlay (LofDuelOverlay). */
    const val STATE_VARP = 4630
    const val RULE_BITS = 16
    const val SLOT_COUNT = 11

    /** The rule toggles, in order — MUST match the client overlay's RULES order. */
    val RULES = listOf(
        "No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
        "No Movement", "No Forfeit", "Whip only", "DDS only", "Fun weapons", "Allow companions",
        "No Weapon Switch", "No Special Attacks", "Show Inventories",
    )
    val RULE_COUNT = RULES.size

    /** Ticks an Accept stays locked after any rules mutation (~3 s) — the 2015 anti-scam lockout. */
    const val CHANGE_LOCK_TICKS = 5

    /** Paper-doll slots (doll index → EquipmentType id) — MUST match the client overlay's SLOT order. */
    val SLOT_IDS = intArrayOf(
        EquipmentType.HEAD.id, EquipmentType.CAPE.id, EquipmentType.AMULET.id, EquipmentType.WEAPON.id,
        EquipmentType.CHEST.id, EquipmentType.SHIELD.id, EquipmentType.LEGS.id, EquipmentType.GLOVES.id,
        EquipmentType.BOOTS.id, EquipmentType.RING.id, EquipmentType.AMMO.id,
    )

    // Rule indices used by the official presets and the rules→bits packer.
    private const val R_NO_RANGED = 1
    private const val R_NO_MAGIC = 2
    private const val R_NO_PRAYER = 3
    private const val R_NO_FOOD = 4
    private const val R_NO_DRINKS = 5
    private const val R_NO_MOVEMENT = 6
    private const val R_NO_FORFEIT = 7
    private const val R_NO_SPEC = 13
    private const val R_SHOW_INV = 14
    private const val WEAPON_DOLL_SLOT = 3

    /**
     * The official **Whip** preset (per the decoded cache bitmask): Show Inventories + No
     * Ranged/Magic/Prayer/Food/Drinks/Movement/Forfeit/Special Attacks + every equipment slot
     * forbidden EXCEPT the weapon. Any one-handed weapon is legal — the forbidden shield slot is
     * what bans 2H weapons (see [DuelRules.barsWorn]).
     */
    val WHIP_PRESET: Int = run {
        var v = 0
        intArrayOf(R_NO_RANGED, R_NO_MAGIC, R_NO_PRAYER, R_NO_FOOD, R_NO_DRINKS, R_NO_MOVEMENT, R_NO_FORFEIT, R_NO_SPEC, R_SHOW_INV)
            .forEach { v = v or (1 shl (it + 1)) }
        for (i in 0 until SLOT_COUNT) if (i != WEAPON_DOLL_SLOT) v = v or (1 shl (i + SLOT_BIT_BASE))
        v
    }

    /** The official **Boxing** preset: Whip plus the weapon slot forbidden → bare fists. */
    val BOXING_PRESET: Int = WHIP_PRESET or (1 shl (WEAPON_DOLL_SLOT + SLOT_BIT_BASE))

    private class Session(val a: Player, val b: Player, val onAccepted: (DuelRules) -> Unit) {
        val rules = BooleanArray(RULE_COUNT)
        val slots = BooleanArray(SLOT_COUNT)
        var acceptedA = false
        var acceptedB = false
        var done = false

        /** World tick until which Accept is locked (the ~3 s post-change lockout). */
        var lockUntil = 0
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

    /** Load the rules both players agreed in [p]'s LAST duel into the live session. */
    fun loadLast(p: Player) {
        applyPacked(p, p.attr[DUEL_PRESET_ATTR] ?: run { p.message("You have no previous duel to load."); return })
    }

    /** Save the session's current rules as [p]'s personal preset. */
    fun savePreset(p: Player) {
        val s = sessions[p.index] ?: return
        p.attr[DUEL_SAVED_PRESET_ATTR] = packState(s.rules, s.slots)
        p.message("Duel rules saved.")
    }

    /** Load [p]'s personal saved preset into the live session. */
    fun loadSaved(p: Player) {
        applyPacked(p, p.attr[DUEL_SAVED_PRESET_ATTR] ?: run { p.message("You have no saved duel rules yet."); return })
    }

    /** Load an official preset ([WHIP_PRESET] / [BOXING_PRESET]) into the live session. */
    fun loadOfficial(p: Player, preset: Int) = applyPacked(p, preset)

    private fun applyPacked(p: Player, packed: Int) {
        val s = sessions[p.index] ?: return
        unpackInto(packed, s.rules, s.slots)
        resetAccepts(s)
    }

    private fun resetAccepts(s: Session) {
        // Like OSRS since the 2015 Rework: ANY change revokes both accepts AND locks Accept for
        // ~3 s (the client overlay shows "Wait…" by diffing the state varp — the enforcement
        // below in [accept] is what a spoofed ::lofduel click runs into).
        val hadAccept = s.acceptedA || s.acceptedB
        s.acceptedA = false
        s.acceptedB = false
        s.lockUntil = s.a.world.currentCycle + CHANGE_LOCK_TICKS
        if (hadAccept) {
            forEachSide(s) { side -> side.message("An option or stake has changed - check before accepting!") }
        }
        publish(s)
    }

    fun accept(p: Player) {
        val s = sessions[p.index] ?: return
        if (p.world.currentCycle < s.lockUntil) {
            p.message("The rules just changed - check them before accepting.")
            return
        }
        // The single winnability gate (DuelRules.validate) — same matrix as the grid and menus.
        buildRules(s.rules, s.slots).validate()?.let { err -> p.message(err); return }
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

    // Bit layout shared with the client overlay AND the persisted preset attrs (a saved preset is
    // just the packed toggle+slot bits with the transient open/accept bits zero).
    private const val ACCEPT_MINE_BIT = RULE_BITS + 1 // 17
    private const val ACCEPT_THEIRS_BIT = RULE_BITS + 2 // 18
    private const val SLOT_BIT_BASE = RULE_BITS + 3 // 19

    /** Toggle+slot bits only (bit 0 open and accept bits added per-player in [publish]). */
    private fun packState(rules: BooleanArray, slots: BooleanArray): Int {
        var v = 0
        for (i in 0 until RULE_COUNT) if (rules[i]) v = v or (1 shl (i + 1))
        for (i in 0 until SLOT_COUNT) if (slots[i]) v = v or (1 shl (i + SLOT_BIT_BASE))
        return v
    }

    private fun unpackInto(packed: Int, rules: BooleanArray, slots: BooleanArray) {
        for (i in 0 until RULE_COUNT) rules[i] = (packed and (1 shl (i + 1))) != 0
        for (i in 0 until SLOT_COUNT) slots[i] = (packed and (1 shl (i + SLOT_BIT_BASE))) != 0
    }

    private fun acc(mine: Boolean, theirs: Boolean): Int =
        (if (mine) 1 shl ACCEPT_MINE_BIT else 0) or (if (theirs) 1 shl ACCEPT_THEIRS_BIT else 0)

    private inline fun forEachSide(s: Session, block: (Player) -> Unit) {
        if (s.a.index >= 0) block(s.a)
        if (s.b.index >= 0) block(s.b)
    }

    private fun buildRules(t: BooleanArray, slots: BooleanArray): DuelRules {
        val weapons = HashSet<Int>()
        val weaponLabels = ArrayList<String>()
        if (t[8]) { weapons += DuelRules.WHIP_WEAPONS; weaponLabels += "Whip only" }
        if (t[9]) { weapons += DuelRules.DDS_WEAPONS; weaponLabels += "DDS only" }
        if (t[10]) { weapons += DuelRules.FUN_WEAPONS; weaponLabels += "Fun weapons" }
        val disabled = SLOT_IDS.filterIndexed { i, _ -> slots[i] }.toSet()
        val gearLabel = when {
            disabled.size == SLOT_IDS.size -> "Boxing"
            weaponLabels.isNotEmpty() && disabled.isNotEmpty() -> weaponLabels.joinToString("/") + " + restricted gear"
            weaponLabels.isNotEmpty() -> weaponLabels.joinToString("/")
            disabled.size == SLOT_IDS.size - 1 && EquipmentType.WEAPON.id !in disabled -> "No armour"
            disabled.isNotEmpty() -> "Restricted gear"
            else -> null
        }
        return DuelRules(
            noMelee = t[0], noRanged = t[1], noMagic = t[2],
            noPrayer = t[3], noFood = t[4], noDrinks = t[5], noMovement = t[6], noForfeit = t[7],
            noWeaponSwitch = t[12], noSpec = t[13], funWeapons = t[10], showInventories = t[14],
            allowCompanions = t[11],
            disabledSlots = disabled,
            allowedWeapons = weapons.takeIf { it.isNotEmpty() },
            gearLabel = gearLabel,
        )
    }

    /**
     * Pack an agreed [DuelRules] back into the [STATE_VARP] toggle+slot bit layout — the
     * confirmation screen republishes the rules this way (varp 4685, [DuelConfirmScreen]) so the
     * client renders its "During the duel:" lines from the exact agreed bits. Weapon whitelists
     * that came from the chatbox menus (no toggle set) surface through the closest toggle.
     */
    fun packRules(r: DuelRules): Int {
        var v = 0
        fun rule(i: Int, on: Boolean) { if (on) v = v or (1 shl (i + 1)) }
        rule(0, r.noMelee); rule(R_NO_RANGED, r.noRanged); rule(R_NO_MAGIC, r.noMagic)
        rule(R_NO_PRAYER, r.noPrayer); rule(R_NO_FOOD, r.noFood); rule(R_NO_DRINKS, r.noDrinks)
        rule(R_NO_MOVEMENT, r.noMovement); rule(R_NO_FORFEIT, r.noForfeit)
        rule(8, r.allowedWeapons?.any { it in DuelRules.WHIP_WEAPONS } == true)
        rule(9, r.allowedWeapons?.any { it in DuelRules.DDS_WEAPONS } == true)
        rule(10, r.funWeapons)
        rule(11, r.allowCompanions)
        rule(12, r.noWeaponSwitch); rule(R_NO_SPEC, r.noSpec); rule(R_SHOW_INV, r.showInventories)
        SLOT_IDS.forEachIndexed { i, slotId -> if (slotId in r.disabledSlots) v = v or (1 shl (i + SLOT_BIT_BASE)) }
        return v
    }
}
