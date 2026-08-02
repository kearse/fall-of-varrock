package org.alter.plugins.content.combat.specialattack.weapons.dragonwarhammer

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
            val victim = target
            val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = 0)

            if (landHit) {
                // OSRS: drains 30% of the target's CURRENT Defence on hit, stacking
                // multiplicatively across specs — and it works on NPCs (its whole
                // purpose is lowering boss defence).
                pawnHit.hit.addAction {
                    val current = victim.currentCombatStat(Skills.DEFENCE, NpcSkills.DEFENCE)
                    victim.drainCombatStat(Skills.DEFENCE, NpcSkills.DEFENCE, (current * 0.30).toInt())
                }
            }
        }
    }
}
