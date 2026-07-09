package org.alter.plugins.content.combat.specialattack.weapons.dragonspear

import org.alter.api.ext.secondsToTicks
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.model.timer.STUN_TIMER
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon spear (1249) special attack: "Shove".
 * 25% energy. Deals no damage but stuns and locks the target in place for ~3
 * seconds (5 game ticks).
 */
class DragonSpearPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_spear", 25) {
            player.animate(id = 8184)
            player.graphic(id = 8185, height = 96)

            // No damage - pure crowd control.
            target.timers[STUN_TIMER] = 3.secondsToTicks()
            target.timers[FROZEN_TIMER] = 3.secondsToTicks()
        }
    }
}
