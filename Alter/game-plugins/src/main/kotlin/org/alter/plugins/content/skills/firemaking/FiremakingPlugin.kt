package org.alter.plugins.content.skills.firemaking

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Firemaking** (Phase 2). Use a tinderbox on logs to light a fire: consumes the log
 * (a sink for Woodcutting output), grants xp, drops a temporary fire on your tile, and
 * steps you back. The fire burns away after a while.
 */
class FiremakingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Burn(val log: String, val name: String, val level: Int, val xp: Double)

    private val burns = listOf(
        Burn("item.logs", "logs", 1, 40.0),
        Burn("item.oak_logs", "oak logs", 15, 60.0),
        Burn("item.willow_logs", "willow logs", 30, 90.0),
        Burn("item.maple_logs", "maple logs", 45, 135.0),
        Burn("item.yew_logs", "yew logs", 60, 202.5),
        Burn("item.magic_logs", "magic logs", 75, 303.8),
    ).filter { res(it.log) }

    init {
        burns.forEach { burn ->
            onItemOnItem("item.tinderbox", burn.log) { player.queue { light(this, player, burn) } }
        }
    }

    private suspend fun light(task: QueueTask, player: Player, burn: Burn) {
        if (player.getSkills().getCurrentLevel(Skills.FIREMAKING) < burn.level) {
            player.message("You need a Firemaking level of ${burn.level} to light ${burn.name}.")
            return
        }
        val tile = player.tile
        if (world.getObject(tile, FIRE_TYPE) != null) {
            player.message("You can't light a fire here.")
            return
        }
        player.message("You attempt to light the logs...")
        player.animate(LIGHT_ANIM)
        task.wait(2)
        if (player.tile != tile) return
        if (player.inventory.remove(item = getRSCM(burn.log), amount = 1).completed == 0) return

        player.addXp(Skills.FIREMAKING, burn.xp)
        val fire = DynamicObject(id = FIRE_OBJ, type = FIRE_TYPE, rot = 0, tile = tile)
        world.spawn(fire)
        player.message("The fire catches and the logs begin to burn.")
        // Step off the fire (west, like OSRS).
        player.walkTo(Tile(tile.x - 1, tile.z, tile.height))
        // Burn out after a while.
        world.queue {
            wait(FIRE_TICKS)
            if (world.getObject(tile, FIRE_TYPE)?.id == FIRE_OBJ) world.remove(fire)
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val LIGHT_ANIM = 733
        const val FIRE_OBJ = 3769 // a "Fire" object
        const val FIRE_TYPE = 10
        const val FIRE_TICKS = 120 // ~72s before the fire burns out
    }
}
