package org.alter.game.task

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.World
import org.alter.game.service.GameService

private val logger = KotlinLogging.logger {}

/**
 * A [GameTask] responsible for executing [org.alter.game.model.entity.Player]
 * cycle logic, sequentially.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class PlayerCycleTask : GameTask {
    override fun execute(
        world: World,
        service: GameService,
    ) {
        world.players.forEach { p ->
            val start = System.currentTimeMillis()
            /*
             * Isolate each player's cycle: one player's exception (e.g. a clientless bot hitting
             * client-only sync code) must NOT abort the whole task and stall every other player's
             * queues (combat, movement). Log it and carry on.
             */
            try {
                p.queues.cycle()
                p.cycle()
                p.playerPreSynchronizationTask()
            } catch (e: Exception) {
                logger.error(e) { "PlayerCycleTask failed for '${p.username}' (skipped this cycle)." }
            }
            /*
             * Log the time it takes for task to handle the player's cycle
             * logic.
             */
            val time = System.currentTimeMillis() - start
            service.playerTimes.merge(p.username, time) { _, oldTime -> oldTime + time }
        }
    }
}
