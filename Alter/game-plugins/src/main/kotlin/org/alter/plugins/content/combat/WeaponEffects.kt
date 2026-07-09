package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.World
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Registry for passive, per-weapon on-hit effects that fire on every normal
 * (auto-attack) hit - e.g. lifesteal, on-hit poison, chain splash.
 *
 * This mirrors [org.alter.plugins.content.combat.specialattack.SpecialAttacks]
 * but for non-special hits. The engine's [org.alter.game.plugin.KotlinPlugin.setItemCombatLogic]
 * hook exists but is never invoked by any combat strategy (see the @TODO on it),
 * so it cannot carry passive effects. Instead, the melee/ranged/magic strategies
 * call [applyOnHit] from their `dealHit` `onHit` callback, which runs when the
 * hit actually lands (after the projectile/animation delay).
 *
 * Effects are keyed by the equipped weapon's item id, so a custom weapon only
 * needs to register here - no change to the strategies per weapon.
 */
object WeaponEffects {
    /**
     * Context handed to a registered on-hit effect. [damage] is the total
     * damage of the triggering hit; [landed] mirrors the accuracy roll.
     */
    data class OnHitContext(
        val world: World,
        val player: Player,
        val target: Pawn,
        val pawnHit: PawnHit,
    ) {
        val damage: Int get() = pawnHit.hit.hitmarks.sumOf { it.damage }
        val landed: Boolean get() = pawnHit.landed
    }

    private val onHitEffects = mutableMapOf<Int, OnHitContext.() -> Unit>()

    fun registerOnHit(
        item: String,
        effect: OnHitContext.() -> Unit,
    ) {
        onHitEffects[getRSCM(item)] = effect
    }

    /**
     * Runs the equipped weapon's passive on-hit effect, if any. Only players
     * carry weapon effects, so this is a cheap no-op for NPC attackers and for
     * weapons without a registered effect.
     */
    fun applyOnHit(
        pawn: Pawn,
        target: Pawn,
        pawnHit: PawnHit,
    ) {
        // Victim-side first: Vengeance reflects 75% of this hit back to the attacker. Must run
        // before the attacker-only early-returns below (the victim, not the attacker, is venged,
        // and the attacker may be an NPC with no weapon effect).
        Vengeance.onDamaged(victim = target, attacker = pawn, pawnHit = pawnHit)

        if (pawn !is Player) return
        val weapon = pawn.getEquipment(EquipmentType.WEAPON) ?: return
        val effect = onHitEffects[weapon.id] ?: return
        effect(OnHitContext(pawn.world, pawn, target, pawnHit))
    }
}
