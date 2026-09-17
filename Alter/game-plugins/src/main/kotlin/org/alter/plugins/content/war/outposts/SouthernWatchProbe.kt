package org.alter.plugins.content.war.outposts

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * TEMPORARY DIAGNOSTIC — the Southern Watch standard (invisible and unclickable at 3227,3372).
 *
 * Two fixes built on inference have now been disproved by this server's own boot log: the loc is
 * not varbit-gated (it resolves to itself), and it does have a model for the shape it is spawned in
 * (`shape 10`). Everything the server can assert about the object says it is fine, yet the player
 * sees empty ground and right-clicking offers only "Walk here". So stop inferring and read the
 * ground truth, the way [org.alter.plugins.content.areas.lumbridge.objs.DukeDoorProbe] does.
 *
 * Dumps, a few ticks after boot (once the frontier force-load and the post's own world-init have
 * settled):
 *
 *  - every GameObject in a box over the stone circle, in every shape slot — id, shape, rotation,
 *    tile and whether the server holds it as STATIC (from the map) or DYNAMIC (spawned by us), so
 *    we can see if our banner is actually in the chunk and what else shares its tile;
 *  - the full cache definition of the resolved standard loc — including `interactive`, which gates
 *    clickability independently of `actions` and which nothing has checked yet;
 *  - collision on the standard tile and its neighbours.
 *
 * Read-only: logs only, no gameplay effect. Remove once the standard is fixed.
 */
class SouthernWatchProbe(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            world.queue {
                wait(10) // after SouthernWatchPlugin's own world-init spawn + the frontier force-load
                runCatching { probe(world) }
                    .onFailure { logger.error(it) { "[sw-probe] probe threw (skipped)." } }
            }
        }
    }

    private fun probe(world: World) {
        logger.info { "===== SW-PROBE start =====" }

        logger.info {
            "SW-PROBE resolved: id=${SouthernWatch.standardId} option='${SouthernWatch.standardOption}' " +
                "shape=${SouthernWatch.standardShape} clickable=${SouthernWatch.standardClickable}"
        }
        dumpDef(SouthernWatch.standardId)

        // Every object over the ring, in every shape slot. DYNAMIC = spawned by us; STATIC = map.
        for (x in 3222..3233) {
            for (z in 3364..3376) {
                val tile = Tile(x, z, 0)
                for (shape in 0..22) {
                    val o = world.getObject(tile, shape) ?: continue
                    val def = runCatching { getObject(o.id) }.getOrNull()
                    val mark = if (tile.x == SouthernWatch.STANDARD_TILE.x && tile.z == SouthernWatch.STANDARD_TILE.z) " <<< STANDARD TILE" else ""
                    logger.info {
                        "SW-PROBE obj @(${o.tile.x},${o.tile.z},${o.tile.height}) id=${o.id} shape=${o.type} rot=${o.rot} " +
                            "entity=${o.entityType} name='${def?.name}' " +
                            "actions=${def?.actions?.filterNotNull()?.filter { it.isNotBlank() }}$mark"
                    }
                }
            }
        }

        val t = SouthernWatch.STANDARD_TILE
        logger.info {
            "SW-PROBE collision: standard(${t.x},${t.z})=${world.collision.isClipped(t)} " +
                "W=${world.collision.isClipped(Tile(t.x - 1, t.z, 0))} " +
                "E=${world.collision.isClipped(Tile(t.x + 1, t.z, 0))} " +
                "N=${world.collision.isClipped(Tile(t.x, t.z + 1, 0))} " +
                "S=${world.collision.isClipped(Tile(t.x, t.z - 1, 0))}"
        }
        logger.info { "===== SW-PROBE end =====" }
    }

    /**
     * The whole definition, because the last two theories each died on a field we had not read.
     * `interactive` is the one nothing has checked: a loc with actions but interactive=0 is drawn
     * without a click menu, which is exactly the reported symptom.
     */
    private fun dumpDef(id: Int) {
        if (id <= 0) {
            logger.warn { "SW-PROBE def: no standard resolved (id=$id) — nothing to dump." }
            return
        }
        val def = runCatching { getObject(id) }.getOrNull() ?: run {
            logger.warn { "SW-PROBE def: getObject($id) failed." }
            return
        }
        logger.info {
            "SW-PROBE def $id: name='${def.name}' interactive=${def.interactive} " +
                "actions=${def.actions.filterNotNull().filter { it.isNotBlank() }}"
        }
        logger.info {
            "SW-PROBE def $id: objectTypes=${def.objectTypes} objectModels=${def.objectModels} " +
                "sizeX=${def.sizeX} sizeY=${def.sizeY} isHollow=${def.isHollow}"
        }
        logger.info {
            "SW-PROBE def $id: varbit=${def.varbit} varp=${def.varp} varbitId=${def.varbitId} " +
                "varpId=${def.varpId} transforms=${def.transforms}"
        }
        logger.info {
            "SW-PROBE def $id: solid=${def.solid} impenetrable=${def.impenetrable} obstructive=${def.obstructive} " +
                "clipped=${def.clipped} modelClipped=${def.modelClipped} clipType=${def.clipType} " +
                "animationId=${def.animationId} mapSceneID=${def.mapSceneID}"
        }
    }
}
