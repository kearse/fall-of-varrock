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
 * **Ring of recoil / ring of suffering.** Victim-side: reflects 10% + 1 of every hit taken back to
 * the attacker (OSRS Wiki). A ring of recoil carries 40 points of reflected damage and shatters
 * when they run out; every ring of suffering variant recoils without charges (OSRS charges it
 * with rings of recoil — this server, like most, treats the suffering as permanently charged;
 * it did nothing at all before 2026-09-03). Driven from [WeaponEffects.applyOnHit] alongside
 * Vengeance so it fires for player and NPC attackers alike.
 */
object RingOfRecoil {
    const val CHARGES = 40

    private val RECOIL_ID: Int by lazy { getRSCM("item.ring_of_recoil") }

    private val SUFFERING_IDS: Set<Int> by lazy {
        listOf(
            "item.ring_of_suffering", "item.ring_of_suffering_i",
            "item.ring_of_suffering_r", "item.ring_of_suffering_ri",
            "item.ring_of_suffering_i_25246", "item.ring_of_suffering_ri_25248",
            "item.ring_of_suffering_i_26761", "item.ring_of_suffering_ri_26762",
        ).mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
    }

    fun onDamaged(victim: Pawn, attacker: Pawn, pawnHit: PawnHit) {
        if (victim !is Player) return
        val ring = victim.getEquipment(EquipmentType.RING) ?: return
        val suffering = ring.id in SUFFERING_IDS
        if (ring.id != RECOIL_ID && !suffering) return
        val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
        if (dealt <= 0 || attacker.isDead()) return

        // OSRS: floor(damage / 10) + 1 (the old ceil(damage / 10) reflected one short on every
        // exact multiple of ten).
        val recoil = dealt / 10 + 1
        if (suffering) {
            attacker.hit(damage = recoil, attackersIndex = victim.index)
            return
        }

        val remaining = victim.attr[RECOIL_DAMAGE_LEFT_ATTR] ?: CHARGES
        val reflect = minOf(remaining, recoil)
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
