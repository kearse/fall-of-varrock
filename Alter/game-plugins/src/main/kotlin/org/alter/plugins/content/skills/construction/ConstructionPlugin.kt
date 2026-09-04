package org.alter.plugins.content.skills.construction

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Construction** (Phase 2, workbench MVP). Use planks on the workbench by the Lumbridge
 * home → pick a piece of furniture to build → consumes planks for Construction xp (a sink
 * for Woodcutting→sawmill planks). This completes the trainable skill set; a full
 * Player-Owned House (instanced region + build hotspots) is a worthwhile future deluxe.
 */
class ConstructionPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val bench = "object.workbench"
    private val benchTile = Tile(3245, 3202, 0) // by the church side, 4 tiles east of the spinning wheel (3241,3202)

    private data class Furniture(val name: String, val planks: Int, val level: Int, val xp: Double)

    /** plank item key -> the furniture you can build from it. */
    private val menus = linkedMapOf(
        "item.plank" to listOf(
            Furniture("Wooden chair", 1, 1, 58.0),
            Furniture("Wooden bookcase", 2, 4, 115.0),
            Furniture("Wooden table", 2, 7, 90.0),
        ),
        "item.oak_plank" to listOf(
            Furniture("Oak chair", 1, 19, 120.0),
            Furniture("Oak table", 2, 22, 240.0),
            Furniture("Oak bookcase", 3, 29, 360.0),
        ),
        "item.teak_plank" to listOf(
            Furniture("Teak chair", 1, 35, 180.0),
            Furniture("Teak table", 2, 38, 270.0),
            Furniture("Teak bookcase", 3, 43, 360.0),
        ),
        "item.mahogany_plank" to listOf(
            Furniture("Mahogany table", 2, 52, 580.0),
            Furniture("Mahogany bookcase", 3, 40, 480.0),
        ),
    ).filterKeys { res(it) }

    init {
        if (res(bench)) {
            // Bench relocated to the Mire working yard (graveyard hub). Tile verified clear of walls/
            // water via the mapDump region scan (region 12849).
            onWorldInit { world.spawn(DynamicObject(getRSCM(bench), OBJ_TYPE, 0, benchTile)) }
            menus.keys.forEach { plank ->
                onItemOnObj(obj = bench, item = plank) { player.queue { buildMenu(this, player, plank) } }
            }
            // A bare click builds from the first plank type carried, or explains the loop
            // ("Construction: not sure how to start", 2026-09-03). The cache bench's verbs vary —
            // try the common ones; a miss just leaves use-plank-on-bench.
            listOf("Build", "Use", "Craft", "Work-at").forEach { verb ->
                runCatching {
                    onObjOption(obj = bench, option = verb) {
                        val plank = menus.keys.firstOrNull { player.inventory.contains(getRSCM(it)) }
                        if (plank != null) player.queue { buildMenu(this, player, plank) }
                        else player.message("Use <col=801700>planks</col> on the workbench to build furniture — the Sawmill Operator at the market sells them.")
                    }
                }
            }
        } else {
            logger.warn { "construction: workbench '$bench' not in cache; construction disabled." }
        }
    }

    private suspend fun QueueTask.buildMenu(task: QueueTask, player: Player, plank: String) {
        val list = menus[plank] ?: return
        val choice = options(player, *list.map { "${it.name} (${it.planks} plank${if (it.planks > 1) "s" else ""})" }.toTypedArray())
        val piece = list.getOrNull(choice - 1) ?: return
        build(task, player, plank, piece)
    }

    private suspend fun build(task: QueueTask, player: Player, plank: String, piece: Furniture) {
        val plankId = getRSCM(plank)
        while (player.inventory.getItemCount(plankId) >= piece.planks) {
            if (player.getSkills().getCurrentLevel(Skills.CONSTRUCTION) < piece.level) {
                player.message("You need a Construction level of ${piece.level} to build a ${piece.name.lowercase()}.")
                return
            }
            player.animate(BUILD_ANIM)
            task.wait(3)
            if (player.inventory.remove(item = plankId, amount = piece.planks).completed < piece.planks) break
            player.addXp(Skills.CONSTRUCTION, piece.xp)
            player.message("You build a ${piece.name.lowercase()}.")
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val BUILD_ANIM = 3676 // construction build motion
    }
}
