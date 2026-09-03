package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.hit
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Soulreaper axe soul stacks (OSRS Wiki, classic mechanics): every ATTACK with the axe — hit
 * or miss — costs the wielder 8 hitpoints and grants one soul stack, up to five. Each stack
 * boosts the effective Strength level by 6% (+30% at five). Stacks decay one per 50 ticks the
 * player has not attacked, and vanish when the axe is unequipped. The special "Behead"
 * consumes every stack: +12% accuracy and +6% max hit per stack, with a guaranteed minimum
 * hit of 6% of max per stack.
 *
 * Player report 2026-09-02: "Doesn't seem to be taking away HP and hitting like it should" —
 * the axe had no mechanics at all and behaved as a plain axe.
 */
object SoulreaperAxe {
    private val STACKS_ATTR = AttributeKey<Int>()
    private val LAST_ATTACK_CYCLE_ATTR = AttributeKey<Int>()

    const val MAX_STACKS = 5
    const val HP_COST = 8
    const val DECAY_TICKS = 50
    const val STRENGTH_PER_STACK = 0.06
    const val SPECIAL_ACCURACY_PER_STACK = 0.12
    const val SPECIAL_DAMAGE_PER_STACK = 0.06

    val ITEM_KEYS = listOf("item.soulreaper_axe_28338")

    private val AXES: Set<Int> by lazy { ITEM_KEYS.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet() }

    fun isWielding(player: Player): Boolean = player.getEquipment(EquipmentType.WEAPON)?.id in AXES

    /**
     * The wielder's current soul stacks, after lazy decay. Unequipping the axe clears them,
     * so a bare read is enough — no equip/unequip hooks needed.
     */
    fun stacks(player: Player): Int {
        if (!isWielding(player)) {
            clear(player)
            return 0
        }
        val stored = player.attr[STACKS_ATTR] ?: return 0
        if (stored <= 0) return 0
        val now = player.world.currentCycle
        val last = player.attr[LAST_ATTACK_CYCLE_ATTR] ?: now
        val decayed = (now - last) / DECAY_TICKS
        if (decayed <= 0) return stored
        val remaining = maxOf(0, stored - decayed)
        player.attr[STACKS_ATTR] = remaining
        player.attr[LAST_ATTACK_CYCLE_ATTR] = last + decayed * DECAY_TICKS
        return remaining
    }

    /** Strength-level multiplier for the melee formula: 1.0 without the axe. */
    fun strengthMultiplier(player: Player): Double = 1.0 + STRENGTH_PER_STACK * stacks(player)

    /** Called per swing (hit or miss). Pays 8 HP for a new stack while below the cap. */
    fun onAttack(player: Player) {
        val current = stacks(player)
        player.attr[LAST_ATTACK_CYCLE_ATTR] = player.world.currentCycle
        if (current >= MAX_STACKS) return
        // The axe never takes the last of your hitpoints: no stack (and no cost) at 8 HP or less.
        if (player.getCurrentHp() <= HP_COST) return
        player.hit(damage = HP_COST)
        player.attr[STACKS_ATTR] = current + 1
    }

    /** Spends every stack (for Behead) and returns how many were spent. */
    fun consume(player: Player): Int {
        val current = stacks(player)
        clear(player)
        return current
    }

    fun clear(player: Player) {
        player.attr.remove(STACKS_ATTR)
        player.attr.remove(LAST_ATTACK_CYCLE_ATTR)
    }
}
