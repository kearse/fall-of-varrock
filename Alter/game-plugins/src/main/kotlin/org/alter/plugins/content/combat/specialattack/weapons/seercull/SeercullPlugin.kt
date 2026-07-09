package org.alter.plugins.content.combat.specialattack.weapons.seercull

import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Seercull (6724) special attack: "Soul Shot".
 * 100% energy. A guaranteed ranged hit that also drains the target's Magic level
 * by the damage dealt.
 *
 * Animation reuses the bow draw (the seercull's own sequence isn't named in the
 * symbol tables).
 */
class SeercullPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.seercull", 100) {
            player.animate(id = 426)
            player.fireAmmoProjectile(target)

            // Guaranteed hit (bypasses the accuracy roll).
            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val damage = world.random(maxHit)
            val pawnHit = player.dealExactHit(target = target, damage = damage, delay = 2)

            val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
            if (dealt > 0) {
                val victim = target
                if (victim is Player) {
                    victim.getSkills().alterCurrentLevel(Skills.MAGIC, -dealt)
                }
            }
        }
    }
}
