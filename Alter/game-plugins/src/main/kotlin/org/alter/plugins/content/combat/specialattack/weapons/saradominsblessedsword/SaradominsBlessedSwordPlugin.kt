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
 * Saradomin's blessed sword (12809) special attack: "Blessed Blast".
 * 65% energy, +25% max hit (OSRS Wiki, Saradomin's blessed sword). No prayer restore —
 * that was a homebrew effect. (OSRS rolls the hit against magic defence; the shared
 * melee accuracy path is kept here since the sword carries no magic attack bonus.)
 */
class SaradominsBlessedSwordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.saradomins_blessed_sword", 65) {
            player.animate(id = 1133)
            player.graphic(id = 1213, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.25)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)
            if (landHit) {
                target.graphic(1196)
            }
        }
    }
}
