package org.alter.plugins.content.weapons.custom

import org.alter.api.ext.heal
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealExactHit

/**
 * Bloodforged Scythe - a lifesteal melee weapon, the custom answer to the
 * "Holy Scythe of Vitur that heals you" inspiration. Implemented as a passive
 * on-hit effect (fires on every landed auto-attack) via [WeaponEffects].
 *
 * Two tiers share the same identity, branched by item id:
 *  - PK-safe (`holy_scythe_of_vitur`): small flat lifesteal on a chance roll,
 *    so it tops you up in a fight without dominating the duel meta.
 *  - PvM/donor (`corrupted_scythe_of_vitur`): heals a percentage of damage
 *    dealt every hit, plus a rare proc that repeats the hit (effective 2x).
 *
 * Healing is capped at your normal max HP by [heal] (capValue = 0).
 */
class BloodforgedScythePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // PK-safe: 12% chance on a landing hit to heal a flat 4 HP.
        WeaponEffects.registerOnHit("item.holy_scythe_of_vitur") {
            if (landed && damage > 0 && world.randomDouble() < 0.12) {
                player.heal(amount = 4)
            }
        }

        // PvM/donor: heal 15% of damage dealt every landing hit (min 1),
        // and a 1/50 chance to strike a second time for the same damage.
        WeaponEffects.registerOnHit("item.corrupted_scythe_of_vitur") {
            if (landed && damage > 0) {
                player.heal(amount = maxOf(1, damage * 15 / 100))
                if (world.chance(1, 50)) {
                    player.dealExactHit(target = target, damage = damage, delay = 1)
                }
            }
        }
    }
}
