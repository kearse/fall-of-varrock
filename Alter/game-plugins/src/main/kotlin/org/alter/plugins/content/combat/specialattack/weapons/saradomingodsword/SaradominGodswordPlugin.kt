package org.alter.plugins.content.combat.specialattack.weapons.saradomingodsword

import org.alter.api.Skills
import org.alter.api.ext.heal
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Saradomin godsword (11806) special attack: "Healing Blade".
 * 50% energy, +100% accuracy, +10% damage. On hit, restores the attacker's
 * Hitpoints by 50% of the damage dealt (minimum 10) and Prayer by 25%.
 */
class SaradominGodswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.saradomin_godsword", 50) {
            player.animate(id = 7640)
            player.graphic(id = 1209, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.10)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
            val landHit = accuracy >= world.randomDouble()
            val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
                val hpRestore = maxOf(dealt / 2, 10)
                player.heal(amount = hpRestore)
                if (dealt > 0) {
                    player.getSkills().alterCurrentLevel(Skills.PRAYER, dealt / 4, capValue = 0)
                }
            }
        }
    }
}
