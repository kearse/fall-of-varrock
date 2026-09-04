package org.alter.plugins.content.combat.specialattack.weapons.dragonthrownaxe

import org.alter.api.EquipmentType
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.rscm.RSCM.getRSCM

/**
 * Dragon thrownaxe (20849) special attack: "Power Throw".
 * 25% energy, +25% ACCURACY (damage unchanged) — OSRS Wiki, Dragon thrownaxe.
 *
 * NOT `executeInstantly`: that flag routes the spec-orb click through the instant (granite-maul)
 * path, which only fires at a MELEE-ADJACENT target — thrownaxes are used from four tiles, so
 * the spec never fired and the player got "You don't have enough power left" with a full bar
 * (player report 2026-09-03). The orb now arms the spec like every other ranged weapon and the
 * next attack fires it; the throw itself is as fast as the weapon's attack speed.
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

            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.25)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = delay)
            // The thrown axe is the ammo: one leaves the weapon slot per throw (the spec used to
            // throw for free).
            player.equipment.remove(getRSCM("item.dragon_thrownaxe"), 1, beginSlot = EquipmentType.WEAPON.id)
        }
    }
}
