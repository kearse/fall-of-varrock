package org.alter.plugins.content.combat.specialattack.weapons.granitemaul

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Granite maul (4153) special attack: "Quick Smash".
 * 50% energy (kept at the ornate-handle cost so an AGS 50% + maul 50% combo fits one
 * energy bar). **0-tick**: clicking the spec bar/orb fires an instant extra hit at your
 * current opponent, ignoring the attack cooldown entirely — chainable while energy
 * lasts, exactly like OSRS. Registered with executeInstantly so the toggle path in
 * AttackTabPlugin executes it immediately instead of arming the next attack.
 */
class GraniteMaulPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.granite_maul", 50, executeInstantly = true) {
            player.animate(id = 1667)
            player.graphic(id = 340, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 0)
        }
    }
}
