package org.alter.plugins.content.combat.specialattack.weapons.saradominsword

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Saradomin sword (11838) special attack: "Saradomin's Lightning".
 * 100% energy: a melee hit with 10% extra damage, plus 1-16 additional magic
 * damage when the melee hit lands (OSRS Wiki, Saradomin sword).
 */
class SaradominSwordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.saradomin_sword", 100) {
            player.animate(id = 1132)
            player.graphic(id = 1194)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.1)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 0)
            if (landHit) {
                player.dealExactHit(target = target, damage = 1 + world.random(15), delay = 0)
            }
        }
    }
}
