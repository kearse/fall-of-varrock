package org.alter.plugins.content.combat.specialattack.weapons.eldermaul

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Executioner's Maul (elder maul, 21003) special attack: "Headsman's Drop".
 * 50% energy, +50% damage and a heavy accuracy boost (shreds through the
 * target's defence). If the target is already below 30% of their max HP, the
 * blow is a guaranteed execute - it cannot be blocked.
 */
class ElderMaulPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.elder_maul", 50) {
            player.animate(id = 1667)
            player.graphic(id = 340, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.5)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)

            // Execute: a target under 30% HP cannot block the blow.
            val executing = target.getCurrentHp() <= (target.getMaxHp() * 0.30).toInt()
            val landHit = executing || accuracy >= world.randomDouble()

            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)
        }
    }
}
