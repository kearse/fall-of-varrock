package org.alter.plugins.content.combat.specialattack.weapons.toxicblowpipe

import org.alter.api.ext.heal
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Toxic blowpipe (12926) special attack: "Toxic Siphon".
 * 50% energy, +50% accuracy. Heals the attacker by 50% of the damage dealt.
 */
class ToxicBlowpipePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.toxic_blowpipe", 50) {
            player.animate(id = 876)
            player.graphic(id = 1043, height = 96)
            player.fireAmmoProjectile(target)

            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.5)
            val landHit = accuracy >= world.randomDouble()
            val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 2)

            if (landHit) {
                val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
                if (dealt > 0) {
                    player.heal(amount = dealt / 2)
                }
            }
        }
    }
}
