package org.alter.plugins.content.weapons.custom

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.mechanics.poison.poison

/**
 * Plaguebringer Bow - a venomous ranged weapon. On a landing hit it applies (or
 * refreshes) poison whose strength scales with the damage just dealt, so bigger
 * shots leave a nastier wound. Implemented as a passive on-hit effect via
 * [WeaponEffects].
 *
 * [org.alter.plugins.content.mechanics.poison.Poison.poison] already refuses to
 * downgrade existing poison (it only re-applies when the new strength is higher),
 * so repeated hits ramp the poison up to the per-hit cap rather than resetting it.
 *
 *  - PK-safe (`twisted_bow`): poison strength up to 6.
 *  - PvM/donor (`corrupted_twisted_bow`): poison strength up to 10.
 */
class PlaguebringerBowPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        registerVenom("item.twisted_bow", maxStrength = 6)
        registerVenom("item.corrupted_twisted_bow", maxStrength = 10)
    }

    private fun registerVenom(
        item: String,
        maxStrength: Int,
    ) {
        WeaponEffects.registerOnHit(item) {
            if (landed && damage > 0) {
                // Scale poison with the hit: ~1 strength per 4 damage, min 2, capped.
                val strength = (damage / 4).coerceIn(2, maxStrength)
                target.poison(initialDamage = strength) {}
            }
        }
    }
}
