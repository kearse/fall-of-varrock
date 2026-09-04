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
            // The tick counter is reset-on-death too, but that races this hook — clear it here
            // so the orb refresh below can't re-read a stale poison value.
            player.attr.remove(POISON_TICKS_LEFT_ATTR)
            Poison.refreshHpOrb(player)
        }

        // The poison/venom ATTRS persist across sessions but POISON_TIMER does not — without
        // this re-arm a relog was a free permanent cure (and a persisted venom value blocked
        // every future poison/venom application forever). OSRS: poison resumes on login.
        onLogin {
            val venom = player.attr[VENOM_DAMAGE_ATTR] ?: 0
            val poisonTicks = player.attr[POISON_TICKS_LEFT_ATTR] ?: 0
            if (venom > 0 || poisonTicks != 0) {
                player.timers[POISON_TIMER] = POISON_TICK_DELAY
            }
            // Always: varp 102 is persisted with the save, so a player cured since their last
            // save would otherwise log in with a stale green bar.
            Poison.refreshHpOrb(player)
        }

        onTimer(POISON_TIMER) {
            val pawn = pawn

            // Venom outranks poison on the shared timer: escalating +2 per proc, capped at 20.
            val venomDamage = pawn.attr[VENOM_DAMAGE_ATTR] ?: 0
            if (venomDamage > 0) {
                pawn.hit(damage = venomDamage, type = HitType.VENOM)
                pawn.attr[VENOM_DAMAGE_ATTR] = Math.min(Poison.VENOM_DAMAGE_CAP, venomDamage + Poison.VENOM_DAMAGE_STEP)
                Poison.refreshHpOrb(pawn)
                pawn.timers[POISON_TIMER] = POISON_TICK_DELAY
                return@onTimer
            }

            val ticksLeft = pawn.attr[POISON_TICKS_LEFT_ATTR] ?: 0

            if (ticksLeft == 0) {
                Poison.refreshHpOrb(pawn)
                return@onTimer
            }

            if (ticksLeft > 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft - 1
                pawn.hit(damage = Poison.getDamageForTicks(ticksLeft), type = HitType.POISON)
            } else if (ticksLeft < 0) {
                pawn.attr[POISON_TICKS_LEFT_ATTR] = ticksLeft + 1
            }
            // Keep the client's countdown (and the bar colour once it reaches 0) in step.
            Poison.refreshHpOrb(pawn)

            pawn.timers[POISON_TIMER] = POISON_TICK_DELAY
        }
    }
}
