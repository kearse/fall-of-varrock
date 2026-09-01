package org.alter.plugins.content.combat.specialattack.weapons.armadylcrossbow

import org.alter.api.Skills
import org.alter.api.ext.heal
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.combat.strategy.ranged.BoltEnchantments
import org.alter.plugins.content.mechanics.poison.Poison

/**
 * Armadyl crossbow (11785) special attack: "Armadyl Eye".
 * 50% energy, double accuracy, and the equipped enchanted bolt's effect has DOUBLE the
 * normal chance to trigger (OSRS Wiki, Armadyl crossbow — it is not a guaranteed proc).
 */
class ArmadylCrossbowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.armadyl_crossbow", 50) {
            player.animate(id = 4230)
            player.fireAmmoProjectile(target)

            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            // Double the proc chance rather than forcing it: the spec doubles the bolt's
            // base rate, not a guarantee. A rolled miss falls through to a normal hit.
            val effect = BoltEnchantments.effectFor(player)?.takeIf {
                BoltEnchantments.rollProc(it, world.random(99), doubleChance = true)
            }
            var maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            var landHit = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0) >= world.randomDouble()

            val rangedLvl = player.getSkills().getCurrentLevel(Skills.RANGED)
            when (effect) {
                BoltEnchantments.Effect.DIAMOND -> {
                    landHit = true
                    maxHit = (maxHit * 1.15).toInt()
                }
                BoltEnchantments.Effect.ONYX -> maxHit = (maxHit * 1.2).toInt()
                BoltEnchantments.Effect.OPAL -> maxHit += rangedLvl / 10
                BoltEnchantments.Effect.PEARL -> maxHit += rangedLvl / 20
                BoltEnchantments.Effect.DRAGONSTONE -> maxHit += rangedLvl / 5
                else -> {}
            }

            if (effect == BoltEnchantments.Effect.RUBY && player.getCurrentHp() * 10 > player.getMaxHp()) {
                val sacrifice = BoltEnchantments.rubySacrifice(player.getCurrentHp())
                if (sacrifice > 0) {
                    player.setCurrentHp(player.getCurrentHp() - sacrifice)
                }
                player.dealExactHit(target = target, damage = BoltEnchantments.rubyDamage(target.getCurrentHp()), delay = delay)
            } else {
                val victim = target
                val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = delay)
                if (effect != null) {
                    pawnHit.hit.addAction {
                        val damage = hitmarks.sumOf { it.damage }
                        when (effect) {
                            BoltEnchantments.Effect.EMERALD -> if (damage > 0) Poison.poison(victim, initialDamage = 5)
                            BoltEnchantments.Effect.ONYX -> if (damage > 0) player.heal(damage / 4)
                            BoltEnchantments.Effect.SAPPHIRE ->
                                if (damage > 0 && victim is Player) {
                                    val drain = victim.getSkills().getCurrentLevel(Skills.PRAYER) / 20
                                    if (drain > 0) {
                                        victim.getSkills().decrementCurrentLevel(Skills.PRAYER, drain, capped = false)
                                        player.getSkills().alterCurrentLevel(Skills.PRAYER, drain / 4)
                                    }
                                }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
