package org.alter.plugins.content.skills.woodcutting

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Woodcutting** (Phase 2, gathering skill). Mirrors Mining: click a tree with an axe to
 * chop logs; trees never deplete and auto-chop until the inventory is full. A grove is
 * placed near the Lumbridge home. Logs feed Firemaking/Fletching/economy later.
 */
class WoodcuttingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Tree(val objId: Int, val log: String, val name: String, val level: Int, val xp: Double, val chopTicks: Int)
    private data class TreeSpawn(val tree: Tree, val tile: Tile)

    // Object ids confirmed (by a cache scan for the "Chop down" action) in this cache.
    private val regular = Tree(1276, "item.logs", "logs", 1, 25.0, 3)
    private val oak = Tree(8467, "item.oak_logs", "oak logs", 15, 37.5, 4)
    private val willow = Tree(8488, "item.willow_logs", "willow logs", 30, 67.5, 5)
    private val maple = Tree(8444, "item.maple_logs", "maple logs", 45, 100.0, 6)
    private val yew = Tree(8513, "item.yew_logs", "yew logs", 60, 175.0, 8)
    private val magic = Tree(8409, "item.magic_logs", "magic logs", 75, 250.0, 11)

    private val trees = listOf(regular, oak, willow, maple, yew, magic)

    // Woodcutting grove — The Mire collection grounds (swamp, just south of the graveyard yard).
    // (The old Lumbridge home grove near the church/graveyard was removed; skilling lives in the swamp.)
    private val spawns = listOf(
        // Trees placed per the owner's layout (east side of the yard + the church).
        TreeSpawn(regular, Tile(3240, 3202, 0)),
        TreeSpawn(oak, Tile(3249, 3203, 0)),
        TreeSpawn(willow, Tile(3251, 3203, 0)),
        TreeSpawn(maple, Tile(3253, 3202, 0)),
        TreeSpawn(yew, Tile(3253, 3200, 0)),
        TreeSpawn(magic, Tile(3253, 3198, 0)),
        // The DROWNED GROVE — a second full arc on the NW meadow of the collection grounds
        // (swamp buildout 2026-07-04; tiles from the region-12849 mapDump, west of the north
        // pond). Doubles capacity + gives willows their thematic waterside spot. 2x willow —
        // the heavy-traffic mid tier.
        TreeSpawn(regular, Tile(3204, 3196, 0)),
        TreeSpawn(oak, Tile(3206, 3194, 0)),
        TreeSpawn(willow, Tile(3208, 3192, 0)),
        TreeSpawn(willow, Tile(3206, 3190, 0)),
        TreeSpawn(maple, Tile(3203, 3189, 0)),
        TreeSpawn(yew, Tile(3205, 3187, 0)),
        TreeSpawn(magic, Tile(3202, 3186, 0)),
    )

    init {
        // Spawn in onWorldInit (matching MiningPlugin) so the region/collision is ready;
        // spawning directly in init{} can place objects before the world is loaded.
        onWorldInit {
            spawns.forEach { world.spawn(DynamicObject(id = it.tree.objId, type = OBJ_TYPE, rot = 0, tile = it.tile)) }
            // Starter axe REMOVED with the home grove — the Mire muster yard already spawns a bronze axe.
        }

        trees.distinctBy { it.objId }.forEach { tree ->
            var bound = false
            CHOP_OPTIONS.forEach { opt ->
                try {
                    onObjOption(obj = tree.objId, option = opt) {
                        val obj = player.getInteractingGameObj()
                        player.queue { chop(this, player, obj, tree) }
                    }
                    bound = true
                } catch (e: Exception) { /* this object id lacks this specific action verb */ }
            }
            // De-silenced: a tree that bound NONE of its chop verbs is dead in-game. Surface it
            // loudly (the swallowing try/catch above would otherwise hide a wrong object id).
            if (!bound) {
                logger.warn { "woodcutting: tree object ${tree.objId} (${tree.name}) has none of $CHOP_OPTIONS in the cache — it will be unclickable." }
            }
        }
    }

    private suspend fun chop(task: QueueTask, player: Player, obj: GameObject, tree: Tree) {
        // Resolve the BEST axe the player carries so the chop animation matches the real tool
        // (this used to always play the bronze swing, 879, regardless of tier).
        val axe = org.alter.plugins.content.skills.GatheringTools.bestAxe(player)
        if (!axe.anyHeld) {
            player.message("You need an axe to chop down this tree.")
            return
        }
        val chopAnim = axe.tool?.anim
        if (chopAnim == null) {
            player.message("You do not have an axe which you have the Woodcutting level to use.")
            return
        }
        if (player.getSkills().getCurrentLevel(Skills.WOODCUTTING) < tree.level) {
            player.message("You need a Woodcutting level of ${tree.level} to chop this tree.")
            return
        }
        player.faceTile(obj.tile)
        val spot = player.tile
        while (player.tile == spot) {
            player.animate(chopAnim)
            task.wait(tree.chopTicks)
            if (player.tile != spot) break
            if (player.inventory.isFull) {
                player.message("Your inventory is too full to hold any more ${tree.name}.")
                break
            }
            player.inventory.add(item = getRSCM(tree.log), amount = 1)
            player.addXp(Skills.WOODCUTTING, tree.xp)
            player.message("You get some ${tree.name}.")
            org.alter.plugins.content.skills.slayer.ResourceContracts.onGather(player, getRSCM(tree.log)) // Vannaka resource contract
        }
    }

    private companion object {
        const val OBJ_TYPE = 10
        const val AXE_RESPAWN = 50
        val CHOP_OPTIONS = listOf("Chop down", "Chop-down", "Chop")
    }
}
