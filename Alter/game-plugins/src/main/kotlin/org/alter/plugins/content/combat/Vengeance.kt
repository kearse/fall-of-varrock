package org.alter.plugins.content.combat

import org.alter.api.ext.*
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey

/**
 * **Lunar Vengeance.** A self-cast buff that reflects 75% of the next hit you take back to the
 * attacker, then expires. Reflect is driven from [WeaponEffects.applyOnHit] (the victim-side
 * hook that runs for every melee/ranged/magic hit), so no engine change is needed.
 *
 * State + cooldown live here so the spell plugin ([VengeancePlugin]) stays thin and the reflect
 * hook has a single place to look.
 */
object Vengeance {
    /** Set while a vengeance is "loaded" and waiting to reflect the next hit. */
    val ACTIVE = AttributeKey<Boolean>()

    /** Re-cast cooldown (OSRS: 30s = 50 ticks). */
    val COOLDOWN = TimerKey()
    const val COOLDOWN_TICKS = 50

    fun isReady(p: Player): Boolean = !p.timers.has(COOLDOWN)
    fun isActive(p: Player): Boolean = p.attr[ACTIVE] == true

    fun activate(p: Player) {
        p.attr[ACTIVE] = true
        p.timers[COOLDOWN] = COOLDOWN_TICKS
        p.animate(4410)
        p.graphic(726)
        p.forceChat("Vengeance!")
    }

    /**
     * Victim-side reflect. [victim] is the pawn that just took [pawnHit] from [attacker].
     * Reflects floor(75% of the hit's total damage) back to the attacker, then clears the buff.
     */
    fun onDamaged(victim: Pawn, attacker: Pawn, pawnHit: PawnHit) {
        if (victim !is Player || victim.attr[ACTIVE] != true) return
        val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
        val reflect = dealt * 3 / 4
        // A miss or 0 must NOT consume the buff — vengeance waits for a real hit.
        if (reflect <= 0) return
        victim.attr[ACTIVE] = false
        victim.forceChat("Taste vengeance!")
        // A plain hit (not routed through a combat strategy) so it never re-triggers this hook.
        attacker.hit(damage = reflect, attackersIndex = victim.index)
    }
}
