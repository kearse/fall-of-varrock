package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
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

    /** Context handed to a registered on-attack effect (fires per swing, hit or miss). */
    data class OnAttackContext(
        val world: World,
        val player: Player,
        val target: Pawn,
    )

    private val onHitEffects = mutableMapOf<Int, OnHitContext.() -> Unit>()
    private val onAttackEffects = mutableMapOf<Int, OnAttackContext.() -> Unit>()

    fun registerOnHit(
        item: String,
        effect: OnHitContext.() -> Unit,
    ) {
        onHitEffects[getRSCM(item)] = effect
    }

    /**
     * Registers an effect that fires every time the wielder ATTACKS with [item], whether or
     * not the hit lands (Soulreaper axe soul stacks). Melee strategy only for now.
     */
    fun registerOnAttack(
        item: String,
        effect: OnAttackContext.() -> Unit,
    ) {
        onAttackEffects[getRSCM(item)] = effect
    }

    fun applyOnAttack(
        pawn: Pawn,
        target: Pawn,
    ) {
        if (pawn !is Player) return
        val weapon = pawn.getEquipment(EquipmentType.WEAPON) ?: return
        val effect = onAttackEffects[weapon.id] ?: return
        effect(OnAttackContext(pawn.world, pawn, target))
    }

    /**
     * Runs the equipped weapon's passive on-hit effect, if any, then the generic
     * poison/venom layer ([WeaponPoisons]). Only players carry weapon effects, so
     * this is a cheap no-op for NPC attackers.
     *
     * @param combatClass the class of the attack that produced this hit, captured by the
     *   strategy at fire time. Falls back to the wielder's current class when omitted.
     * @param ammoId the quiver ammo fired (bows/crossbows), for poisoned-ammo rolls.
     */
    fun applyOnHit(
        pawn: Pawn,
        target: Pawn,
        pawnHit: PawnHit,
        combatClass: CombatClass? = null,
        ammoId: Int? = null,
    ) {
        // Victim-side first: Vengeance and the ring of recoil react to the hit the TARGET
        // just took. Must run before the attacker-only early-returns below (the victim,
        // not the attacker, owns these effects, and the attacker may be an NPC).
        Vengeance.onDamaged(victim = target, attacker = pawn, pawnHit = pawnHit)
        RingOfRecoil.onDamaged(victim = target, attacker = pawn, pawnHit = pawnHit)

        if (pawn !is Player) return
        val weapon = pawn.getEquipment(EquipmentType.WEAPON)
        if (weapon != null) {
            onHitEffects[weapon.id]?.invoke(OnHitContext(pawn.world, pawn, target, pawnHit))
        }
        val resolvedClass = combatClass ?: CombatConfigs.getCombatClass(pawn)
        // Non-weapon-slot attacker passives (the registry above is weapon-keyed).
        BloodFury.onHit(pawn, pawnHit, resolvedClass)
        WeaponPoisons.onHit(pawn, target, pawnHit, resolvedClass, ammoId)
    }
}
