package org.alter.plugins.content.weapons.soulreaper

import org.alter.api.ext.message
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.SoulreaperAxe
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.MeleeCombatStrategy

/**
 * Wires the Soulreaper axe: per-swing soul-stack gain (see [SoulreaperAxe]) and the "Behead"
 * special, which costs no special energy but every soul stack the wielder has built.
 */
class SoulreaperAxePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (item in SoulreaperAxe.ITEM_KEYS) {
            runCatching {
                WeaponEffects.registerOnAttack(item) { SoulreaperAxe.onAttack(player) }

                SpecialAttacks.register(item, energy = 0) {
                    val stacks = SoulreaperAxe.consume(player)
                    if (stacks <= 0) {
                        // No stacks: Behead has nothing to spend, so swing normally instead of
                        // eating the attack tick.
                        player.message("You need at least one soul stack to unleash Behead.")
                        MeleeCombatStrategy.attack(player, target)
                        return@register
                    }
                    player.animate(CombatConfigs.getAttackAnimation(player))
                    val accuracy = MeleeCombatFormula.getAccuracy(
                        player, target, specialAttackMultiplier = 1.0 + SoulreaperAxe.SPECIAL_ACCURACY_PER_STACK * stacks,
                    )
                    val maxHit = MeleeCombatFormula.getMaxHit(
                        player, target, specialAttackMultiplier = 1.0 + SoulreaperAxe.SPECIAL_DAMAGE_PER_STACK * stacks,
                    )
                    val landHit = accuracy >= world.randomDouble()
                    if (landHit) {
                        val minHit = Math.floor(maxHit * SoulreaperAxe.SPECIAL_DAMAGE_PER_STACK * stacks).toInt()
                        val damage = minHit + world.random(maxOf(0, maxHit - minHit))
                        player.dealExactHit(target = target, damage = damage, delay = 0)
                    } else {
                        player.dealHit(target = target, maxHit = maxHit, landHit = false, delay = 0)
                    }
                }
            }
        }
    }
}
