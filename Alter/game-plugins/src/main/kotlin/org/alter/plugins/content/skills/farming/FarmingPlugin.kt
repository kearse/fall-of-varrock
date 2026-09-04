package org.alter.plugins.content.skills.farming

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
 * **Farming** (Phase 2, instant MVP). Use a seed on a patch by the Lumbridge home → the
 * crop grows on a short fixed timer → harvest produce + xp. Herb crops feed Herblore and
 * vegetables feed Cooking (the consumption loop). Simplified: a brief grow wait then
 * harvest, no per-crop growth-stage visuals/varbits (those can come later).
 */
class FarmingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    // Use a Flowerbed (raised, colourful, bordered bed — model 1569) as the planting spot. The real
    // farming patches (allotment/belladonna) ARE static and render, but their model is FLAT tilled
    // soil that blends into the swamp ground, so players couldn't see where to plant. The flowerbed
    // stands out clearly. Binding is "use seed on object", which works on any object id regardless of
    // its own actions. (Verified static via the mapDump `objinfo` scan: models=[1569], no varbit/varp.)
    private val patch = "object.flowerbed"
    // The Mire — collection grounds herb/allotment patches (swamp, south of the graveyard yard).
    // (The old Lumbridge home patches near the church/graveyard were removed.)
    private val patchTiles = listOf(
        Tile(3236, 3175, 0), Tile(3237, 3175, 0), Tile(3238, 3175, 0), Tile(3239, 3175, 0), Tile(3240, 3175, 0),
        // Fen Gardens second row (swamp buildout 2026-07-04) — herb-leaning row north of the allotments.
        Tile(3236, 3177, 0), Tile(3238, 3177, 0), Tile(3240, 3177, 0),
    )

    private data class Crop(val seed: String, val produce: String, val name: String, val level: Int, val xp: Double, val yield: Int)

    private val crops = listOf(
        Crop("item.potato_seed", "item.potato", "potatoes", 1, 8.0, 3),
        Crop("item.onion_seed", "item.onion", "onions", 5, 10.5, 3),
        Crop("item.cabbage_seed", "item.cabbage", "cabbages", 7, 11.5, 3),
        Crop("item.guam_seed", "item.guam_leaf", "guam", 9, 12.5, 2),
        Crop("item.marrentill_seed", "item.marrentill", "marrentill", 14, 15.0, 2),
        Crop("item.harralander_seed", "item.harralander", "harralander", 26, 19.0, 2),
        Crop("item.ranarr_seed", "item.ranarr_weed", "ranarr", 32, 30.5, 2),
        Crop("item.irit_seed", "item.irit_leaf", "irit", 44, 43.0, 2),
    ).filter { res(it.seed) && res(it.produce) }

    init {
        if (res(patch)) {
            // Spawn in onWorldInit so the region/collision is loaded first (matches MiningPlugin).
            onWorldInit { patchTiles.forEach { world.spawn(DynamicObject(getRSCM(patch), OBJ_TYPE, 0, it)) } }
            crops.forEach { c -> onItemOnObj(obj = patch, item = c.seed) { player.queue { grow(this, player, c) } } }
            // A bare click plants the best seed carried, or lists what grows here ("Farming: unsure
            // what to do", 2026-09-03). The flowerbed's own verbs vary by cache — try the usual ones.
            listOf("Inspect", "Rake", "Pick", "Search").forEach { verb ->
                runCatching {
                    onObjOption(obj = patch, option = verb) {
                        val lvl = player.getSkills().getCurrentLevel(Skills.FARMING)
                        val carried = crops.lastOrNull { lvl >= it.level && player.inventory.contains(getRSCM(it.seed)) }
                        if (carried != null) {
                            player.queue { grow(this, player, carried) }
                        } else {
                            val list = crops.joinToString(", ") { "${it.name} (${it.level})" }
                            player.message("Use a seed on the bed to plant it. Grows here: $list. Seeds are sold at the market's seed stalls.")
                        }
                    }
                }
            }
        } else {
            logger.warn { "farming: patch '$patch' not in cache; farming disabled." }
        }
    }

    private suspend fun grow(task: QueueTask, player: Player, c: Crop) {
        if (player.getSkills().getCurrentLevel(Skills.FARMING) < c.level) {
            player.message("You need a Farming level of ${c.level} to plant ${c.name}.")
            return
        }
        if (player.inventory.remove(item = getRSCM(c.seed), amount = 1).completed == 0) return
        player.message("You plant the ${c.name} seed in the patch...")
        task.wait(GROW_TICKS)
        val add = player.inventory.add(item = getRSCM(c.produce), amount = c.yield, assureFullInsertion = false)
        player.addXp(Skills.FARMING, c.xp)
        if (add.completed > 0) {
            player.message("You harvest ${add.completed} ${c.name}.")
        } else {
            player.message("Your inventory is too full to harvest the ${c.name}.")
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val GROW_TICKS = 17 // ~10s grow time (instant-MVP fixed timer)
    }
}
