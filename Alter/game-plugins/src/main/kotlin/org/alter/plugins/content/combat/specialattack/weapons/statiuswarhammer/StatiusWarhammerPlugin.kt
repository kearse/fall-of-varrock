package org.alter.plugins.content.combat.specialattack.weapons.statiuswarhammer

import org.alter.api.NpcSkills
import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.currentCombatStat
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.drainCombatStat
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Statius's warhammer (22622) special attack: "Raise Defences".
 * 35% energy, +25% damage. On a successful hit it lowers the target's current
 * Defence by 30%.
 *
 * Animation reuses the warhammer swing; the spec graphic (844) is exact.
 */
class StatiusWarhammerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.statiuss_warhammer", 35) {
            player.animate(id = 1378)
            player.graphic(id = 844, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.25)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            val landHit = accuracy >= world.randomDouble()
            val victim = target
            val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = 0)

            if (landHit) {
                // Drains 30% of the target's current Defence on hit — NPCs included.
                pawnHit.hit.addAction {
                    val current = victim.currentCombatStat(Skills.DEFENCE, NpcSkills.DEFENCE)
                    victim.drainCombatStat(Skills.DEFENCE, NpcSkills.DEFENCE, (current * 0.30).toInt())
                }
            }
        }
    }
}
