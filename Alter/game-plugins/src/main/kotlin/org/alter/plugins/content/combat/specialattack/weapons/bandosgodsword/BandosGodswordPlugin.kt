package org.alter.plugins.content.combat.specialattack.weapons.bandosgodsword

import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Bandos godsword (11804) special attack: "Warstrike".
 * 50% energy, +100% accuracy, +21% damage. On a successful hit it drains the
 * target's Defence level by the amount of damage dealt.
 */
class BandosGodswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.bandos_godsword", 50) {
            player.animate(id = 7642)
            player.graphic(id = 1210, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.21)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
            val landHit = accuracy >= world.randomDouble()
            val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
                val victim = target
                if (victim is Player && dealt > 0) {
                    victim.getSkills().alterCurrentLevel(Skills.DEFENCE, -dealt)
                }
            }
        }
    }
}
