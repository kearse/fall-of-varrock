package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.hit
import org.alter.api.ext.message
import org.alter.game.model.attr.RECOIL_DAMAGE_LEFT_ATTR
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * **Ring of recoil.** Victim-side: reflects ceil(10%) of every hit taken (minimum 1
 * for any damaging hit) back to the attacker. A ring carries 40 points of reflected
 * damage and shatters when they run out (OSRS Wiki, Ring of recoil). Driven from
 * [WeaponEffects.applyOnHit] alongside Vengeance so it fires for player and NPC
 * attackers alike.
 */
object RingOfRecoil {
    const val CHARGES = 40

    private val RING_ID: Int by lazy { getRSCM("item.ring_of_recoil") }

    fun onDamaged(victim: Pawn, attacker: Pawn, pawnHit: PawnHit) {
        if (victim !is Player) return
        val ring = victim.getEquipment(EquipmentType.RING) ?: return
        if (ring.id != RING_ID) return
        val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
        if (dealt <= 0 || attacker.isDead()) return

        val remaining = victim.attr[RECOIL_DAMAGE_LEFT_ATTR] ?: CHARGES
        // ceil(damage / 10), min 1, capped by the ring's remaining charge.
        val reflect = minOf(remaining, maxOf(1, (dealt + 9) / 10))
        if (reflect <= 0) return
        attacker.hit(damage = reflect, attackersIndex = victim.index)

        val left = remaining - reflect
        if (left <= 0) {
            victim.equipment[EquipmentType.RING.id] = null
            victim.attr.remove(RECOIL_DAMAGE_LEFT_ATTR)
            victim.message("Your Ring of Recoil has shattered.")
        } else {
            victim.attr[RECOIL_DAMAGE_LEFT_ATTR] = left
        }
    }
}
