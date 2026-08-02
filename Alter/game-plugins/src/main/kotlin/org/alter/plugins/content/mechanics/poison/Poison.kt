package org.alter.plugins.content.mechanics.poison

import org.alter.api.EquipmentType
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
    private const val HP_ORB_VARP = 102

    fun getDamageForTicks(ticks: Int) = (ticks / 5) + 1

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
        if (isEnvenomed(pawn)) {
            return false // venom outranks poison; the two share one timer and never coexist
        }
        val ticks = (initialDamage * 5) - 4
        val oldDamage = getDamageForTicks(pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0)
        if (oldDamage > getDamageForTicks(ticks)) {
            return false
        }
        pawn.timers[POISON_TIMER] = 1
        pawn.attr[POISON_TICKS_LEFT_ATTR] = ticks
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
        if (pawn is Player) {
            setHpOrb(pawn, OrbState.VENOM)
        }
        return true
    }

    /** Convert active venom into regular poison at the venom's current damage (what a
     *  dose of ordinary antipoison does in OSRS). */
    fun convertVenomToPoison(pawn: Pawn) {
        val damage = pawn.attr[VENOM_DAMAGE_ATTR] ?: 0
        pawn.attr.remove(VENOM_DAMAGE_ATTR)
        if (damage > 0) {
            pawn.attr[POISON_TICKS_LEFT_ATTR] = (damage * 5) - 4
            pawn.timers[POISON_TIMER] = 1
            if (pawn is Player) {
                setHpOrb(pawn, OrbState.POISON)
            }
        }
    }

    const val VENOM_START_DAMAGE = 6
    const val VENOM_DAMAGE_CAP = 20
    const val VENOM_DAMAGE_STEP = 2

    fun setHpOrb(
        player: Player,
        state: OrbState,
    ) {
        val value =
            when (state) {
                OrbState.NONE -> 0
                OrbState.POISON -> 1
                OrbState.VENOM -> 1_000_000
            }
        player.setVarp(HP_ORB_VARP, value)
    }

    enum class OrbState {
        NONE,
        POISON,
        VENOM,
    }
}
