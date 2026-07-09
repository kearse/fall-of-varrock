package org.alter.plugins.content.economy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce

private val logger = KotlinLogging.logger {}

/**
 * **Supply Drive rotation** (Phase C of the Mire war-supply loop). Every [DRIVE_TICKS] a new
 * category of Quartermaster supplies goes into high demand — deposits of that category pay
 * [SupplyDrive.MULTIPLIER]× War Effort AND fill the realm supply meter faster. The rotation is
 * broadcast server-wide, and `::drive` shows the current window.
 *
 * State lives in [SupplyDrive]; the payout hook is in [SupplyDepot.deposit].
 */
class SupplyDrivePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val driveTimer = TimerKey()

    init {
        onWorldInit {
            rotate()
            world.timers[driveTimer] = DRIVE_TICKS
        }
        onTimer(driveTimer) {
            rotate()
            world.timers[driveTimer] = DRIVE_TICKS
        }
        onCommand("drive", description = "Show the current Supply Drive") {
            val cat = SupplyDrive.active
            if (cat == null) {
                player.message("No Supply Drive is running right now.")
            } else {
                player.message(
                    "<col=801700>Supply Drive:</col> ${cat.display} pay ${SupplyDrive.MULTIPLIER}x War Effort at the Quartermaster.",
                )
            }
        }
    }

    /** Advance to the next category (round-robin, so every category gets its window). */
    private fun rotate() {
        val cats = SupplyDrive.Category.entries
        val current = SupplyDrive.active
        val next = cats[((current?.ordinal ?: -1) + 1) % cats.size]
        SupplyDrive.active = next
        Announce.broadcast(
            world,
            "<col=ffae00>Supply Drive!</col> The war effort calls for <col=ffae00>${next.display}</col> — " +
                "deposits pay ${SupplyDrive.MULTIPLIER}x War Effort at the Quartermaster.",
        )
        logger.info { "supply-drive: rotated to ${next.name}" }
    }

    private companion object {
        /** ~20 minutes per window (600ms ticks). */
        const val DRIVE_TICKS = 2000
    }
}
