package org.alter.plugins.content.combat.specialattack.weapons.barrelchestanchor

import org.alter.api.NpcSkills
import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.drainCombatStat
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Barrelchest anchor (10887) special attack: "Sunder".
 * 50% energy, +10% damage. On a successful hit it lowers the target's current
 * Defence level by 10% of the damage dealt.
 */
class BarrelchestAnchorPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.barrelchest_anchor", 50) {
            player.animate(id = 5870)
            player.graphic(id = 1027, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.1)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.1)
            val landHit = accuracy >= world.randomDouble()
            val victim = target
            val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = 0)

            if (landHit) {
                // Drains Defence by a tenth of the damage dealt — NPCs included.
                pawnHit.hit.addAction {
                    val dealt = hitmarks.sumOf { it.damage }
                    victim.drainCombatStat(Skills.DEFENCE, NpcSkills.DEFENCE, dealt / 10)
                }
            }
        }
    }
}
