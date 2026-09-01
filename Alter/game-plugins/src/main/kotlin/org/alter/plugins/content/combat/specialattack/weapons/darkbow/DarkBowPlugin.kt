package org.alter.plugins.content.combat.specialattack.weapons.darkbow

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dark bow (11235) special attack: "Descent of Dragons".
 * 55% energy. Fires two arrows; with DRAGON arrows the damage bonus is +50% and each
 * landed arrow has a floor of 8, otherwise +30% with a floor of 5 (OSRS Wiki, Dark bow).
 * Per-arrow damage is capped at 48. Requires (and consumes) two arrows.
 */
class DarkBowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dark_bow", 55) {
            player.animate(id = 426)
            player.fireAmmoProjectile(target)

            // Dragon arrows: +50% / min 8; anything else: +30% / min 5.
            val ammo = player.getEquipment(EquipmentType.AMMO)
            val dragon = ammo != null && runCatching { org.alter.rscm.RSCM.getRSCM("item.dragon_arrow") }.getOrNull() == ammo.id
            val multiplier = if (dragon) 1.5 else 1.3
            val floor = if (dragon) 8 else 5

            for (i in 0 until 2) {
                val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = multiplier)
                val accuracy = RangedCombatFormula.getAccuracy(player, target)
                val landHit = accuracy >= world.randomDouble()
                val delay = 2 + i
                if (landHit) {
                    val damage = maxOf(floor, world.random(maxHit)).coerceAtMost(48)
                    player.dealExactHit(target = target, damage = damage, delay = delay)
                } else {
                    player.dealHit(target = target, maxHit = maxHit, landHit = false, delay = delay)
                }
            }
        }
    }
}
