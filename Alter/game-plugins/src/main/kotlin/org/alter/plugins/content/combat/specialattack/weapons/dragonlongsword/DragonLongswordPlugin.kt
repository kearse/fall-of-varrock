package org.alter.plugins.content.combat.specialattack.weapons.dragonlongsword

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon longsword (1305) special attack: "Cleave".
 * 25% energy, +25% damage, single hit.
 */
class DragonLongswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_longsword", 25) {
            player.animate(id = 1058)
            player.graphic(id = 248, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.25)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)
        }
    }
}
