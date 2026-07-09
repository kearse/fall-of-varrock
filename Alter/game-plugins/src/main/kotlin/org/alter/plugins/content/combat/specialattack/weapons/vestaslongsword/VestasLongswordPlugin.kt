package org.alter.plugins.content.combat.specialattack.weapons.vestaslongsword

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Vesta's longsword (22613) special attack: "Lunging Slice".
 * 25% energy, +20% damage and +25% accuracy single hit.
 *
 * Animation/graphic reuse the longsword spec (Vesta's own sequence isn't named
 * in the symbol tables; the modern Voidwaker replaced it).
 */
class VestasLongswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.vestas_longsword", 25) {
            player.animate(id = 7515)
            player.graphic(id = 1369, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.2)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
        }
    }
}
