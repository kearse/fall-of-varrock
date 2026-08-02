package org.alter.plugins.content.magic

import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey

/**
 * **Tele Block** state. A teleblocked player can't teleport (any method) until the timer expires.
 * The block is enforced in [canTeleport] (which every spell + jewellery teleport already calls),
 * applied by the Tele Block spell effect, and announced as worn-off via an onTimer binding.
 */
object TeleBlock {
    val TIMER = TimerKey()

    /** OSRS: 5 minutes = 500 ticks; halved by Protect from Magic at cast time (see TeleBlockPlugin). */
    const val DURATION_TICKS = 500

    fun isBlocked(p: Player): Boolean = p.timers.has(TIMER)

    fun apply(p: Player, ticks: Int = DURATION_TICKS) {
        p.timers[TIMER] = ticks
    }
}
