package org.alter.plugins.content.combat.specialattack.weapons.dragonthrownaxe

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon thrownaxe (20849) special attack: "Power Throw".
 * 25% energy, +25% damage single throw.
 */
class DragonThrownaxePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_thrownaxe", 25) {
            player.animate(id = 7521)
            player.fireAmmoProjectile(target)

            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.25)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 2)
        }
    }
}
