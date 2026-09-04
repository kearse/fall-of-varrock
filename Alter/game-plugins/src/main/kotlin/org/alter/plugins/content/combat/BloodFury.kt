package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.heal
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * **Amulet of blood fury** (OSRS Wiki): on every landed MELEE hit, a 20% chance to heal the
 * wearer for 30% of the damage dealt. Attacker-side, keyed on the AMULET slot — the
 * [WeaponEffects] registry is weapon-slot keyed, so this sits beside it the way the victim-side
 * [RingOfRecoil] does. Uncharged (this server does not model charges — see TonalzticsOfRalos).
 * Nothing implemented it before 2026-09-03 ("blood fury does not heal").
 */
object BloodFury {
    private val AMULET_ID: Int? by lazy { runCatching { getRSCM("item.amulet_of_blood_fury") }.getOrNull() }

    private const val PROC_PERCENT = 20
    private const val HEAL_PERCENT = 30
    private const val HEAL_GFX = 1541

    fun onHit(attacker: Player, pawnHit: PawnHit, combatClass: CombatClass) {
        // Cheapest guard first: this runs on every hit any player lands.
        val amulet = attacker.getEquipment(EquipmentType.AMULET) ?: return
        if (amulet.id != AMULET_ID) return
        if (combatClass != CombatClass.MELEE || !pawnHit.landed) return
        val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
        if (dealt <= 0) return
        if (attacker.world.random(99) >= PROC_PERCENT) return
        attacker.heal(maxOf(1, dealt * HEAL_PERCENT / 100))
        attacker.graphic(HEAL_GFX)
    }
}
