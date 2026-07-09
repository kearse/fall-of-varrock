package org.alter.plugins.content.skills.crafting

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
 * **Crafting** (Phase 2). Three classic methods, all economy-feeding:
 *  - **Gem cutting**: chisel on an uncut gem → cut gem.
 *  - **Leatherworking**: needle on leather → pick a piece (consumes thread) → armour.
 *  - **Spinning**: flax/wool on a spinning wheel → bow string / ball of wool. Spinning
 *    bow strings ties Crafting into Fletching (string your bows).
 * A spinning wheel is placed by the Lumbridge home.
 */
class CraftingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val wheel = "object.spinning_wheel"
    private val wheelTile = Tile(3237, 3204, 0)

    private data class Gem(val uncut: String, val cut: String, val name: String, val level: Int, val xp: Double)
    private val gems = listOf(
        Gem("item.uncut_sapphire", "item.sapphire", "sapphire", 1, 50.0),
        Gem("item.uncut_emerald", "item.emerald", "emerald", 27, 67.5),
        Gem("item.uncut_ruby", "item.ruby", "ruby", 34, 85.0),
        Gem("item.uncut_diamond", "item.diamond", "diamond", 43, 107.5),
    ).filter { res(it.uncut) && res(it.cut) }

    private data class Hide(val item: String, val name: String, val level: Int, val xp: Double)
    private val leatherPieces = listOf(
        Hide("item.leather_gloves", "leather gloves", 1, 13.8),
        Hide("item.leather_boots", "leather boots", 7, 16.25),
        Hide("item.leather_cowl", "leather cowl", 9, 18.5),
        Hide("item.leather_vambraces", "leather vambraces", 11, 22.0),
        Hide("item.leather_body", "leather body", 14, 25.0),
        Hide("item.leather_chaps", "leather chaps", 18, 27.0),
    ).filter { res(it.item) }

    private data class Spin(val from: String, val to: String, val name: String, val level: Int, val xp: Double)
    private val spinnables = listOf(
        Spin("item.flax", "item.bow_string", "bow string", 1, 15.0),
        Spin("item.wool", "item.ball_of_wool", "ball of wool", 1, 2.5),
    ).filter { res(it.from) && res(it.to) }

    init {
        // Gem cutting.
        gems.forEach { g -> onItemOnItem("item.chisel", g.uncut) { player.queue { cutGems(this, player, g) } } }

        // Leatherworking.
        if (res("item.leather") && leatherPieces.isNotEmpty()) {
            onItemOnItem("item.needle", "item.leather") { player.queue { leatherMenu(this, player) } }
        }

        // Spinning. No home wheel spawn — the Mire muster-yard spinning wheel @3154,3162 serves this
        // now (binding is global by object id). The old Lumbridge home wheel near the church was removed.
        if (res(wheel)) {
            spinnables.forEach { s -> onItemOnObj(obj = wheel, item = s.from) { player.queue { spin(this, player, s) } } }
        }
    }

    private suspend fun cutGems(task: QueueTask, player: Player, g: Gem) {
        while (player.inventory.contains(getRSCM(g.uncut))) {
            if (player.getSkills().getCurrentLevel(Skills.CRAFTING) < g.level) {
                player.message("You need a Crafting level of ${g.level} to cut a ${g.name}.")
                return
            }
            task.wait(2)
            if (player.inventory.remove(item = getRSCM(g.uncut), amount = 1).completed == 0) break
            player.inventory.add(item = getRSCM(g.cut), amount = 1)
            player.addXp(Skills.CRAFTING, g.xp)
            player.message("You cut the ${g.name}.")
        }
    }

    private suspend fun QueueTask.leatherMenu(task: QueueTask, player: Player) {
        if (!player.inventory.contains(getRSCM("item.needle"))) { player.message("You need a needle."); return }
        if (!player.inventory.contains(getRSCM("item.thread"))) { player.message("You need some thread."); return }
        val choice = options(player, *leatherPieces.map { it.name }.toTypedArray())
        val piece = leatherPieces.getOrNull(choice - 1) ?: return
        makeLeather(task, player, piece)
    }

    private suspend fun makeLeather(task: QueueTask, player: Player, piece: Hide) {
        while (player.inventory.contains(getRSCM("item.leather")) && player.inventory.contains(getRSCM("item.thread"))) {
            if (player.getSkills().getCurrentLevel(Skills.CRAFTING) < piece.level) {
                player.message("You need a Crafting level of ${piece.level} to make ${piece.name}.")
                return
            }
            task.wait(2)
            if (player.inventory.remove(item = getRSCM("item.leather"), amount = 1).completed == 0) break
            player.inventory.remove(item = getRSCM("item.thread"), amount = 1)
            player.inventory.add(item = getRSCM(piece.item), amount = 1)
            player.addXp(Skills.CRAFTING, piece.xp)
            player.message("You make ${piece.name}.")
        }
    }

    private suspend fun spin(task: QueueTask, player: Player, s: Spin) {
        while (player.inventory.contains(getRSCM(s.from))) {
            if (player.getSkills().getCurrentLevel(Skills.CRAFTING) < s.level) {
                player.message("You need a Crafting level of ${s.level} to spin that.")
                return
            }
            task.wait(2)
            if (player.inventory.remove(item = getRSCM(s.from), amount = 1).completed == 0) break
            player.inventory.add(item = getRSCM(s.to), amount = 1)
            player.addXp(Skills.CRAFTING, s.xp)
            player.message("You spin the ${s.name}.")
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
    }
}
