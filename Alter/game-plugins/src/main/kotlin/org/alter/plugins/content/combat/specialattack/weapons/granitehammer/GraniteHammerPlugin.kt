package org.alter.plugins.content.combat.specialattack.weapons.granitehammer

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Granite hammer (21742) special attack: "Smash".
 * 50% energy, +50% accuracy single hit (partially ignores the target's defence).
 *
 * Animation reuses the warhammer swing (the granite hammer's own player spec
 * sequence isn't named in the symbol tables); the spec graphic (1450) is exact.
 */
class GraniteHammerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.granite_hammer", 50) {
            player.animate(id = 1378)
            player.graphic(id = 1450, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.5)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1)
        }
    }
}
