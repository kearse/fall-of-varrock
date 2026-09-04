package org.alter.plugins.content.mechanics.poison

import org.alter.api.EquipmentType
import org.alter.api.ext.getVarp
import org.alter.api.ext.hasEquipped
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.attr.VENOM_DAMAGE_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.POISON_TIMER

/**
 * @author Tom <rspsmods@gmail.com>
 */
object Poison {
    /**
     * Varp 102 — the client's poison state. The HP orb/status bar colours off it (green for
     * poison, dark green for venom) and the stock poison plugin decodes the value:
     *   `0 < v < 1_000_000`  poisoned, damage = ceil(v / 5), natural cure in 18.2 s × v;
     *   `v >= 1_000_000`     envenomed, damage = min(20, (v - 1_000_000 + 3) × 2);
     *   `v <= 0`             nothing (a NEGATIVE value is decoded as a cure timer in the past,
     *                        so the antipoison immunity window writes 0, not the counter).
     * Our poison tick counter is the exact inverse of the client's damage decode
     * ([getDamageForTicks] = (ticks + 4) / 5 = ceil(ticks / 5)), so it is written raw.
     */
    private const val HP_ORB_VARP = 102
    private const val VENOM_BASE = 1_000_000

    // OSRS shape: severity 5d ticks, damage ceil(ticks/5), i.e. FIVE hits at each damage
    // value (d, d-1, ..., 1). The old `(ticks/5)+1` gave only 2 hits at the initial damage.
    fun getDamageForTicks(ticks: Int) = (ticks + 4) / 5

    fun isImmune(pawn: Pawn): Boolean =
        when (pawn) {
            is Player -> pawn.hasEquipped(EquipmentType.HEAD, "item.serpentine_helm", "item.tanzanite_helm", "item.magma_helm")
            is Npc -> pawn.combatDef.immunePoison
            else -> false
        }

    fun poison(
        pawn: Pawn,
        initialDamage: Int,
    ): Boolean {
        // Immunity is enforced HERE, not only in the item-use wrapper: NPC poisonChance,
        // emerald bolts and smoke spells all call this directly and were bypassing
        // serpentine helms / immunePoison NPCs.
        if (isImmune(pawn)) {
            return false
        }
        if (isEnvenomed(pawn)) {
            return false // venom outranks poison; the two share one timer and never coexist
        }
        // Antipoison immunity window (negative tick counter). venom() checked this but
        // poison() didn't — fresh poison overwrote the immunity counter, so cures granted
        // no re-poison protection at all.
        if ((pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0) < 0) {
            return false
        }
        val ticks = initialDamage * 5
        val oldDamage = getDamageForTicks(pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0)
        if (oldDamage > getDamageForTicks(ticks)) {
            return false
        }
        pawn.timers[POISON_TIMER] = 1
        pawn.attr[POISON_TICKS_LEFT_ATTR] = ticks
        // Every poison source (weapons, bolts, smoke spells, boss defs) lands here, and this
        // is the one place the HP bar learns about it — it used to be written only by an
        // item-use wrapper nothing called, so the bar never turned green (player report).
        refreshHpOrb(pawn)
        return true
    }

    fun isEnvenomed(pawn: Pawn): Boolean = (pawn.attr[VENOM_DAMAGE_ATTR] ?: 0) > 0

    fun isImmuneVenom(pawn: Pawn): Boolean =
        when (pawn) {
            is Player -> pawn.hasEquipped(EquipmentType.HEAD, "item.serpentine_helm", "item.tanzanite_helm", "item.magma_helm")
            is Npc -> pawn.combatDef.immuneVenom
            else -> false
        }

    /**
     * Envenom [pawn]: 6 damage on the next poison cycle, escalating +2 per proc to a cap
     * of 20. A venom-immune (but not poison-immune) target is poisoned at strength 6
     * instead, as in OSRS. Poison-cure immunity (negative tick counter) blocks both.
     */
    fun venom(pawn: Pawn): Boolean {
        if (isImmuneVenom(pawn)) {
            return !isImmune(pawn) && poison(pawn, initialDamage = 6)
        }
        if (isEnvenomed(pawn)) {
            return true // already envenomed — the existing escalation continues
        }
        if ((pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0) < 0) {
            return false // antipoison immunity window
        }
        pawn.attr.remove(POISON_TICKS_LEFT_ATTR)
        pawn.attr[VENOM_DAMAGE_ATTR] = VENOM_START_DAMAGE
        pawn.timers[POISON_TIMER] = 1
        refreshHpOrb(pawn)
        return true
    }

    /** Convert active venom into regular poison at the venom's current damage (what a
     *  dose of ordinary antipoison does in OSRS). */
    fun convertVenomToPoison(pawn: Pawn) {
        val damage = pawn.attr[VENOM_DAMAGE_ATTR] ?: 0
        pawn.attr.remove(VENOM_DAMAGE_ATTR)
        if (damage > 0) {
            pawn.attr[POISON_TICKS_LEFT_ATTR] = damage * 5
            pawn.timers[POISON_TIMER] = 1
        }
        refreshHpOrb(pawn)
    }

    const val VENOM_START_DAMAGE = 6
    const val VENOM_DAMAGE_CAP = 20
    const val VENOM_DAMAGE_STEP = 2

    /**
     * Re-derive varp 102 from the pawn's poison/venom state (see [HP_ORB_VARP]). Call after
     * every change to [POISON_TICKS_LEFT_ATTR] / [VENOM_DAMAGE_ATTR] — applying, each proc
     * (so the client's countdown stays live), curing, converting, death and login.
     */
    fun refreshHpOrb(pawn: Pawn) {
        if (pawn !is Player) return
        val venom = pawn.attr[VENOM_DAMAGE_ATTR] ?: 0
        val ticks = pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0
        val value =
            when {
                venom > 0 -> VENOM_BASE + venom / 2 - 3
                ticks > 0 -> ticks
                else -> 0 // cured, or the antipoison immunity window (negative counter)
            }
        if (pawn.getVarp(HP_ORB_VARP) != value) {
            pawn.setVarp(HP_ORB_VARP, value)
        }
    }
}
