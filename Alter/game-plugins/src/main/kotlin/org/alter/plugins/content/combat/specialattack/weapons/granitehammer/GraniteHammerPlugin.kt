package org.alter.plugins.content.combat.specialattack.weapons.granitehammer

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Granite hammer (21742) special attack: "Smash".
 * 60% energy, +50% accuracy, and +5 flat damage added to the hit. Attacks instantly
 * (like the granite maul) — OSRS Wiki, Granite hammer.
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
        SpecialAttacks.register("item.granite_hammer", 60, executeInstantly = true) {
            player.animate(id = 1378)
            player.graphic(id = 1450, height = 100)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.5)
            val landHit = accuracy >= world.randomDouble()
            player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 1)
            // +5 flat damage added to a landed hit (Saradomin-sword pattern).
            if (landHit) {
                player.dealExactHit(target = target, damage = 5, delay = 1)
            }
        }
    }
}
