package org.alter.plugins.content.combat.specialattack.weapons.saradominsblessedsword

import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Saradomin's blessed sword (12809) special attack: "Flare".
 * 65% energy. Deals a melee hit and restores the attacker's Prayer by 50% of
 * the damage dealt.
 */
class SaradominsBlessedSwordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.saradomins_blessed_sword", 65) {
            player.animate(id = 1133)
            player.graphic(id = 1204, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            val landHit = accuracy >= world.randomDouble()
            val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)

            if (landHit) {
                val dealt = pawnHit.hit.hitmarks.sumOf { it.damage }
                if (dealt > 0) {
                    player.getSkills().alterCurrentLevel(Skills.PRAYER, dealt / 2, capValue = 0)
                }
            }
        }
    }
}
