package org.alter.plugins.content.combat.specialattack.weapons.ancientmace

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
 * Ancient mace (11061) special attack: "Favour of the war god".
 * 100% energy. Drains the target's Prayer by the damage dealt and restores the
 * attacker's Prayer by the same amount.
 *
 * Animation/graphic reuse the mace spec (its own sequence isn't named in the
 * symbol tables).
 */
class AncientMacePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.ancient_mace", 100) {
            player.animate(id = 6147)
            player.graphic(id = 6148, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            val landHit = accuracy >= world.randomDouble()
            val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
                if (dealt > 0) {
                    val victim = target
                    if (victim is Player) {
                        victim.getSkills().alterCurrentLevel(Skills.PRAYER, -dealt)
                    }
                    player.getSkills().alterCurrentLevel(Skills.PRAYER, dealt, capValue = 0)
                }
            }
        }
    }
}
