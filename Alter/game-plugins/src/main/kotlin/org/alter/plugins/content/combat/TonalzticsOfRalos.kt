package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Tonalztics of ralos (OSRS Wiki): a 7-tick two-handed thrown weapon whose every hit rolls
 * 0-75% of the wielder's max ranged hit. The charged variant (28922) throws TWICE per attack
 * with two independent damage rolls; the uncharged one (28919) once. Charges are not modelled
 * (the charged item never runs dry — same policy as the tridents/shadow here).
 *
 * Player report 2026-09-02: "spec does not work; attacks sometimes fast sometimes slow" —
 * there was no plugin at all, so the weapon fired a single full-damage generic thrown attack
 * and the spec bar printed the energy message. The speed swing is the rapid style (6 ticks)
 * vs accurate/longrange (7) and is correct.
 */
object TonalzticsOfRalos {
    private val UNCHARGED: Int by lazy { runCatching { getRSCM("item.tonalztics_of_ralos_uncharged") }.getOrDefault(-1) }
    private val CHARGED: Int by lazy { runCatching { getRSCM("item.tonalztics_of_ralos") }.getOrDefault(-1) }

    const val DAMAGE_PERCENT = 75

    /** Special "Division": +50% accuracy; each landed hit drains Defence by target Magic / 8. */
    const val SPECIAL_ENERGY = 50
    const val SPECIAL_ACCURACY_MULTIPLIER = 1.5
    const val SPECIAL_DEFENCE_DRAIN_DIVISOR = 8

    fun isWielding(player: Player): Boolean {
        val weapon = player.getEquipment(EquipmentType.WEAPON)?.id ?: return false
        return weapon == UNCHARGED || weapon == CHARGED
    }

    fun isCharged(player: Player): Boolean = player.getEquipment(EquipmentType.WEAPON)?.id == CHARGED

    /** Throws per attack: two when charged, one when uncharged. */
    fun hitsPerAttack(player: Player): Int = if (isCharged(player)) 2 else 1

    /** Attack range: 6 uncharged, 7 charged (+2 on longrange, applied by the caller). */
    fun attackRange(player: Player): Int = if (isCharged(player)) 7 else 6

    fun scaleMaxHit(maxHit: Int): Int = maxHit * DAMAGE_PERCENT / 100
}
