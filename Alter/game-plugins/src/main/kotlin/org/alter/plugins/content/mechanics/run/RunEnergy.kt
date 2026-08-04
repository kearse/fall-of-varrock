package org.alter.plugins.content.mechanics.run

import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.cfg.Varp
import org.alter.api.ext.*
import org.alter.game.model.bits.INFINITE_VARS_STORAGE
import org.alter.game.model.bits.InfiniteVarsType
import org.alter.game.model.entity.Player
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.timer.TimerKey
import kotlin.math.max
import kotlin.math.min

/**
 * @author Tom <rspsmods@gmail.com>
 */
object RunEnergy {
    val RUN_DRAIN = TimerKey()

    /**
     * Reduces run energy depletion by 70%
     */
    val STAMINA_BOOST = TimerKey("stamina_boost", tickOffline = false)

    const val RUN_ENABLED_VARP = Varp.RUN_MODE_VARP

    fun toggle(p: Player) {
        // runEnergy is on a 0..10000 scale; the old >= 100.0 check let run be enabled at 1%.
        if (p.runEnergy > 0.0) {
            p.toggleVarp(RUN_ENABLED_VARP)
        } else {
            p.setVarp(RUN_ENABLED_VARP, 0)
            p.message("You don't have enough run energy left.")
        }
    }

    /**
     * OSRS rates on the 0..10000 scale (OSRS Wiki, Energy): drain per running tick is
     * 67 + 67 x clamp(weight, 0..64)/64 (so 0 kg -> ~0.67%/tick, 64+ kg -> ~1.34%/tick),
     * stamina cuts drain by 70%. OSRS restore per non-running tick is (floor(agility/6) + 8)
     * hundredths of a percent, +30% with full graceful; this server restores at 2x that
     * rate by default. The previous restore expression was computed then discarded in
     * favour of a flat +5%/tick (0->100% in 12 seconds, agility/graceful/weight all no-ops).
     */
    fun drain(p: Player) {
        if (p.isRunning() && p.hasMoveDestination()) {
            if (!p.hasStorageBit(INFINITE_VARS_STORAGE, InfiniteVarsType.RUN)) {
                val weight = min(64.0, max(0.0, p.weight))
                var decrement = 67.0 + (67.0 * weight / 64.0)
                if (p.timers.has(STAMINA_BOOST)) {
                    decrement *= 0.3
                }
                p.runEnergy = max(0.0, (p.runEnergy - decrement))
                if (p.runEnergy <= 0) {
                    p.varps.setState(RUN_ENABLED_VARP, 0)
                }
                p.sendRunEnergy(p.runEnergy.toInt())
            }
        } else if (p.runEnergy < 10000.0 && p.lock.canRestoreRunEnergy()) {
            // Restores at 2x the OSRS rate by default.
            var recovery = (p.getSkills().getCurrentLevel(Skills.AGILITY) / 6 + 8).toDouble() * 2.0
            if (isWearingFullGrace(p)) {
                recovery *= 1.3
            }
            p.runEnergy = min(10000.0, (p.runEnergy + recovery))
            p.sendRunEnergy(p.runEnergy.toInt())
        }
    }

    private fun isWearingFullGrace(p: Player): Boolean =
        (p.equipment[EquipmentType.HEAD.id]?.id ?: -1) in GRACEFUL_HOODS &&
            (p.equipment[EquipmentType.CAPE.id]?.id ?: -1) in GRACEFUL_CAPE &&
            (p.equipment[EquipmentType.CHEST.id]?.id ?: -1) in GRACEFUL_TOP &&
            (p.equipment[EquipmentType.LEGS.id]?.id ?: -1) in GRACEFUL_LEGS &&
            (p.equipment[EquipmentType.GLOVES.id]?.id ?: -1) in GRACEFUL_GLOVES &&
            (p.equipment[EquipmentType.BOOTS.id]?.id ?: -1) in GRACEFUL_BOOTS


    /**
     * @TODO Magic Numbers
     */
    private val GRACEFUL_HOODS = intArrayOf(11850, 13579, 13591, 13603, 13615, 13627, 13667, 21061)

    private val GRACEFUL_CAPE = intArrayOf(11852, 13581, 13593, 13605, 13617, 13629, 13669, 21064)

    private val GRACEFUL_TOP = intArrayOf(11854, 13583, 13595, 13607, 13619, 13631, 13671, 21067)

    private val GRACEFUL_LEGS = intArrayOf(11856, 13585, 13597, 13609, 13621, 13633, 13673, 21070)

    private val GRACEFUL_GLOVES = intArrayOf(11858, 13587, 13599, 13611, 13623, 13635, 13675, 21073)

    private val GRACEFUL_BOOTS = intArrayOf(11860, 13589, 13601, 13613, 13625, 13637, 13677, 21076)
}
