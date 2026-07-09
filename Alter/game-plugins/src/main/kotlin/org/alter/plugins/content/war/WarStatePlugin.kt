package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Lifecycle wiring for [WarState] — the foundational persistence layer for The
 * War. This is Phase 1, step 1 of the siege system (see `docs/war-system-design.md`):
 * a server-wide value that survives restarts, which everything else builds on.
 *
 * Responsibilities:
 * - Load war state on world init and register the fronts the server knows about.
 * - Periodically flush dirty state to disk (the engine has no save-on-shutdown,
 *   so we rely on a timer plus forced saves on meaningful events).
 * - Provide dev commands to inspect/poke pressure so the round-trip is verifiable
 *   before any gameplay is built on top.
 */
class WarStatePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // ~60s at 600ms/tick. save() is a no-op unless state is dirty, so a
        // frequent check is cheap; it just bounds how much a crash can lose.
        val saveCheckIntervalTicks = 100
        val warStateSaveTimer = TimerKey()

        onWorldInit {
            WarState.load()
            // Register every front the server knows about with its garrison size.
            Sieges.all.forEach { WarState.registerFront(it.frontId, it.knightPoolMax) }
            world.timers[warStateSaveTimer] = saveCheckIntervalTicks
        }

        onTimer(warStateSaveTimer) {
            WarState.save()
            world.timers[warStateSaveTimer] = saveCheckIntervalTicks
        }

        // ::war — print every tracked front's live status.
        onCommand("war", Privilege.ADMIN_POWER, description = "Print war front status") {
            val fronts = WarState.knownFronts().sorted()
            if (fronts.isEmpty()) {
                player.message("No fronts are being tracked.")
                return@onCommand
            }
            player.message("--- The War: front status ---")
            fronts.forEach { front ->
                val status = WarState.raidStatus(front)
                val phase = when (WarState.phaseOf(front)) {
                    WarState.Phase.PEACE -> "AT PEACE"
                    WarState.Phase.UNDER_RAID -> "${status.tierName} RAID — ${status.goblinsAlive} goblins"
                    WarState.Phase.CITY_FALLEN -> "FALLEN (~${WarState.cityFallenTicksLeft(front)} ticks)"
                }
                player.message("$front: $phase · knights ${WarState.getKnightPool(front)}/${WarState.knightPoolMax(front)}")
            }
        }

        // ::warpool <frontId> <n> — set a front's surviving knight pool and force a save,
        // so the value can be confirmed to persist across a restart.
        onCommand("warpool", Privilege.ADMIN_POWER, description = "Set a front's knight pool") {
            val args = player.getCommandArgs()
            if (args.size < 2) {
                player.message("Usage: ::warpool <frontId> <n>")
                return@onCommand
            }
            val frontId = args[0]
            val n = args[1].toIntOrNull()
            if (n == null || n < 0) {
                player.message("Knight count must be a non-negative number.")
                return@onCommand
            }
            WarState.setKnightPool(frontId, n)
            WarState.save(force = true)
            player.message("Set $frontId knight pool to ${WarState.getKnightPool(frontId)} and saved.")
        }
    }

    companion object {
        /** Phase-1 vertical-slice front: the frontier city's north gate. */
        const val FRONTIER_NORTH = "frontier_north"
    }
}
