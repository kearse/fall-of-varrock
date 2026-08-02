package org.alter.plugins.content.mechanics.poison

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.model.timer.POISON_TIMER
import org.alter.game.plugin.*

class PoisonPluginPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        // OSRS: poison and venom both proc every 30 game ticks (18 seconds).
        val POISON_TICK_DELAY = 30

        onPlayerDeath {
            player.timers.remove(POISON_TIMER)
            player.attr.remove(VENOM_DAMAGE_ATTR)
            Poison.setHpOrb(player, Poison.OrbState.NONE)
        }

        onTimer(POISON_TIMER) {
            val pawn = pawn

            // Venom outranks poison on the shared timer: escalating +2 per proc, capped at 20.
            val venomDamage = pawn.attr[VENOM_DAMAGE_ATTR] ?: 0
            if (venomDamage > 0) {
                pawn.hit(damage = venomDamage, type = HitType.VENOM)
                pawn.attr[VENOM_DAMAGE_ATTR] = Math.min(Poison.VENOM_DAMAGE_CAP, venomDamage + Poison.VENOM_DAMAGE_STEP)
                if (pawn is Player) {
                    Poison.setHpOrb(pawn, Poison.OrbState.VENOM)
                }
                pawn.timers[POISON_TIMER] = POISON_TICK_DELAY
                return@onTimer
            }

            val ticksLeft = pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0

            if (ticksLeft == 0) {
                if (pawn is Player) {
                    Poison.setHpOrb(pawn, Poison.OrbState.NONE)
                }
                return@onTimer
            }

            if (ticksLeft > 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft - 1
                pawn.hit(damage = Poison.getDamageForTicks(ticksLeft), type = HitType.POISON)
            } else if (ticksLeft < 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft + 1
            }

            pawn.timers[POISON_TIMER] = POISON_TICK_DELAY
        }
    }
}
