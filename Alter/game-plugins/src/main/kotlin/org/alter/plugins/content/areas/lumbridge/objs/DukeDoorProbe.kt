package org.alter.plugins.content.areas.lumbridge.objs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * TEMPORARY DIAGNOSTIC — Duke's door (reported un-openable at 3207,3222 level 1).
 *
 * A few ticks after boot (once region 12850 has fully loaded), dumps exactly what the
 * SERVER actually has around the door: every GameObject in a small box on levels 0 and 1
 * (id/type/rot/tile + the cache def's name & actions), plus the collision state on the
 * door tile and its neighbours. This replaces guesswork — we read the ground truth.
 *
 * Remove once the door is fixed.
 */
class DukeDoorProbe(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            world.queue {
                wait(5) // let the war force-load + NPC-spawn region loads settle
                probe(world)
            }
        }
    }

    private fun probe(world: World) {
        logger.info { "===== DUKE-DOOR-PROBE start =====" }
        for (level in 0..1) {
            for (x in 3204..3210) {
                for (z in 3219..3225) {
                    val tile = Tile(x, z, level)
                    for (type in 0..22) {
                        val o = world.getObject(tile, type) ?: continue
                        val info = try {
                            val def = o.getDef()
                            "name='${def.name}' actions=${def.actions.filterNotNull().filter { it.isNotBlank() }}"
                        } catch (e: Exception) {
                            "def-error=${e.message}"
                        }
                        logger.info { "DUKE-DOOR-PROBE obj @(${o.tile.x},${o.tile.z},${o.tile.height}) id=${o.id} type=${o.type} rot=${o.rot} $info" }
                    }
                }
            }
        }
        val door = Tile(3207, 3222, 1)
        logger.info {
            "DUKE-DOOR-PROBE collision L1: door(3207,3222)=${world.collision.isClipped(door)} " +
                "W(3206,3222)=${world.collision.isClipped(Tile(3206, 3222, 1))} " +
                "E(3208,3222)=${world.collision.isClipped(Tile(3208, 3222, 1))} " +
                "N(3207,3223)=${world.collision.isClipped(Tile(3207, 3223, 1))} " +
                "S(3207,3221)=${world.collision.isClipped(Tile(3207, 3221, 1))}"
        }
        logger.info { "===== DUKE-DOOR-PROBE end =====" }
    }
}
