package org.alter.plugins.content.bosses.wilderness

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Scorpia's cave doors: the three cavern mouths on the surface (object 26762, `Enter`) and the
 * three crevices inside the cave (object 26763, `Use`), paired in [WildernessBosses.CAVE_DOORS].
 * Neither was bound before — the portal was the only way in and there was NO way out ("Scorpia
 * exit doesn't work", 2026-09-03). The clicked object's tile picks its pair; the player lands on
 * the nearest open square beside the other end.
 */
class ScorpiaCavePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Verbs verified from the rev-228 object defs: the cavern's is "Enter", the crevice's
        // (inside the cave, leading out) is literally "Use".
        onObjOption(obj = WildernessBosses.CAVE_ENTRANCE_OBJ, option = "enter") {
            val obj = player.getInteractingGameObj()
            val door = nearest(obj.tile) { it.surface }
            travel(player, door.cave, "You squeeze through the cavern and climb down into Scorpia's lair.")
        }
        onObjOption(obj = WildernessBosses.CAVE_EXIT_OBJ, option = "use") {
            val obj = player.getInteractingGameObj()
            val door = nearest(obj.tile) { it.cave }
            travel(player, door.surface, "You climb up through the crevice into the Wilderness.")
        }
        logger.info { "scorpia-cave: ${WildernessBosses.CAVE_DOORS.size} cavern/crevice pairs bound." }
    }

    private fun nearest(from: Tile, end: (WildernessBosses.CaveDoor) -> Tile): WildernessBosses.CaveDoor =
        WildernessBosses.CAVE_DOORS.minBy { d -> end(d).let { t -> maxOf(kotlin.math.abs(t.x - from.x), kotlin.math.abs(t.z - from.z)) } }

    private fun travel(player: Player, doorTile: Tile, message: String) {
        // Land beside the far door, on open floor (the door tile itself is the object).
        val dest = world.snapToWalkable(Tile(doorTile.x, doorTile.z + 1, doorTile.height), maxRadius = 3)
        player.queue {
            player.lock()
            player.animate(CLIMB_ANIM)
            wait(1)
            player.moveTo(dest)
            player.animate(-1)
            player.unlock()
            player.message(message)
        }
    }

    private companion object {
        /** The generic climb/crawl sequence used by the Wilderness crevices. */
        const val CLIMB_ANIM = 828
    }
}
