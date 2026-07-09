package org.alter.plugins.content.combat.specialattack.weapons.dragonwarhammer

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
 * Dragon warhammer (13576) special attack: "Smash".
 * 50% energy, +50% damage. On a successful hit it lowers the target's current
 * Defence level by 30%.
 */
class DragonWarhammerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_warhammer", 50) {
            player.animate(id = 1378)
            player.graphic(id = 1292, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.5)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val victim = target
                if (victim is Player) {
                    val current = victim.getSkills().getCurrentLevel(Skills.DEFENCE)
                    victim.getSkills().setCurrentLevel(Skills.DEFENCE, (current * 0.70).toInt())
                }
            }
        }
    }
}
