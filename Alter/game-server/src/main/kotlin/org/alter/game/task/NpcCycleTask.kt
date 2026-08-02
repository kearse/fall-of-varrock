package org.alter.game.task

import org.alter.game.model.World
import org.alter.game.service.GameService

/**
 * A [GameTask] responsible for executing [org.alter.game.model.entity.Npc]
 * cycle logic, sequentially.
 *
 * @author Tom <rspsmods@gmail.com>
 */
private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

class NpcCycleTask : GameTask {
    override fun execute(
        world: World,
        service: GameService,
    ) {
        world.npcs.forEach { n ->
            /*
             * Isolate each npc's cycle (mirrors PlayerCycleTask): one throwing npc must
             * not abort the cycle of every npc after it in the list for the tick.
             */
            try {
                n.queues.cycle()
                n.cycle()
                n.npcPreSynchronizationTask()
            } catch (e: Exception) {
                logger.error(e) { "NpcCycleTask failed for npc ${n.id} at ${n.tile} (skipped this cycle)." }
            }
        }
    }
}
