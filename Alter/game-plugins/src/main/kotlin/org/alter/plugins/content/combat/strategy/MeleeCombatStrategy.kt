package org.alter.plugins.content.combat.strategy

import org.alter.api.Skills
import org.alter.api.WeaponType
import org.alter.api.ext.hasWeaponType
import org.alter.api.ext.playSound
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.XpMode
import org.alter.game.model.entity.AreaSound
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import java.lang.IllegalStateException

/**
 * @author Tom <rspsmods@gmail.com>
 */
object MeleeCombatStrategy : CombatStrategy {
    override fun getAttackRange(pawn: Pawn): Int {
        if (pawn is Player) {
            val halberd = pawn.hasWeaponType(WeaponType.HALBERD)
            return if (halberd) 2 else 1
        }
        return 1
    }

    override fun canAttack(
        pawn: Pawn,
        target: Pawn,
    ): Boolean {
        return true
    }

    override fun attack(
        pawn: Pawn,
        target: Pawn,
    ) {
        val world = pawn.world
        val animation = CombatConfigs.getAttackAnimation(pawn)
        pawn.animate(animation)
        if (target is Player) {
            when (pawn) {
                is Npc -> {
                    CombatConfigs.getCombatDef(pawn)!!.let {
                        if (it.defaultAttackSoundArea) {
                            world.spawn(
                                AreaSound(pawn.tile, it.defaultAttackSound, it.defaultAttackSoundRadius, it.defaultAttackSoundVolume),
                            )
                        } else {
                            target.playSound(pawn.combatDef.defaultAttackSound, pawn.combatDef.defaultAttackSoundVolume)
                        }
                    }
                }
                is Player -> {
                    // @TODO Later
                }
            }
        }
        val formula = MeleeCombatFormula
        val accuracy = formula.getAccuracy(pawn, target)
        val maxHit = formula.getMaxHit(pawn, target)
        val landHit = accuracy >= world.randomDouble()

        // Melee damage applies on the defender's next cycle (delay 0) — the OSRS "same
        // tick" semantics. XP is awarded when the hit lands, from the damage actually
        // dealt (hitmarks are clamped to remaining HP at application).
        val pawnHit =
            pawn.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 0) {
                WeaponEffects.applyOnHit(pawn, target, it, combatClass = CombatClass.MELEE)
            }
        // Per-swing effects (Soulreaper soul stacks) fire whether or not the hit lands.
        WeaponEffects.applyOnAttack(pawn, target)
        pawnHit.hit.addAction {
            val damage = hitmarks.sumOf { it.damage }
            if (damage > 0 && pawn.entityType.isPlayer) {
                addCombatXp(pawn as Player, target, damage)
            }
        }
    }

    private fun addCombatXp(
        player: Player,
        target: Pawn,
        damage: Int,
    ) {
        val modDamage = damage
        val mode = CombatConfigs.getXpMode(player)
        val multiplier = Combat.COMBAT_XP_MULTIPLIER * (if (target is Npc) Combat.getNpcXpMultiplier(target) else 1.0)

        when (mode) {
            XpMode.ATTACK -> {
                player.addXp(Skills.ATTACK, modDamage * 4.0 * multiplier)
                player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
            }
            XpMode.STRENGTH -> {
                player.addXp(Skills.STRENGTH, modDamage * 4.0 * multiplier)
                player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
            }
            XpMode.DEFENCE -> {
                player.addXp(Skills.DEFENCE, modDamage * 4.0 * multiplier)
                player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
            }
            XpMode.SHARED -> {
                player.addXp(Skills.ATTACK, modDamage * 1.33 * multiplier)
                player.addXp(Skills.STRENGTH, modDamage * 1.33 * multiplier)
                player.addXp(Skills.DEFENCE, modDamage * 1.33 * multiplier)
                player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
            }
            else -> {
                // A weapon whose xp-mode table reports a non-melee mode while melee-attacking
                // (e.g. salamander tables) must not throw — default to Attack xp.
                player.addXp(Skills.ATTACK, modDamage * 4.0 * multiplier)
                player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
            }
        }
    }
}
