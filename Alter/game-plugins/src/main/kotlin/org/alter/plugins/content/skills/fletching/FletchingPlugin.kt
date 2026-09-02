package org.alter.plugins.content.skills.fletching

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Fletching** (Phase 2). Knife on logs → unstrung bow (shortbow/longbow per log tier,
 * or arrow shafts from regular logs); then bow string on the unstrung bow → finished bow.
 * Consumes Woodcutting logs and produces ranged gear — feeds the consumption economy.
 */
class FletchingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** A log tier: what its short/long unstrung bows are + level/xp to cut them. */
    private data class Cut(
        val log: String, val name: String,
        val shortU: String, val shortLevel: Int, val shortXp: Double,
        val longU: String, val longLevel: Int, val longXp: Double,
    )

    private val cuts = listOf(
        Cut("item.logs", "logs", "item.shortbow_u", 5, 5.0, "item.longbow_u", 10, 10.0), // wiki: shortbow needs 5
        Cut("item.oak_logs", "oak logs", "item.oak_shortbow_u", 20, 16.5, "item.oak_longbow_u", 25, 25.0),
        Cut("item.willow_logs", "willow logs", "item.willow_shortbow_u", 35, 33.3, "item.willow_longbow_u", 40, 41.5),
        Cut("item.maple_logs", "maple logs", "item.maple_shortbow_u", 50, 50.0, "item.maple_longbow_u", 55, 58.3),
        Cut("item.yew_logs", "yew logs", "item.yew_shortbow_u", 65, 67.5, "item.yew_longbow_u", 70, 75.0),
        Cut("item.magic_logs", "magic logs", "item.magic_shortbow_u", 80, 83.3, "item.magic_longbow_u", 85, 91.5),
    ).filter { res(it.log) }

    /** unstrung bow -> (strung bow, level, xp) for the bow-string step. */
    private data class Stringing(val strung: String, val level: Int, val xp: Double)

    private val stringings = mapOf(
        "item.shortbow_u" to Stringing("item.shortbow", 5, 5.0),
        "item.longbow_u" to Stringing("item.longbow", 10, 10.0),
        "item.oak_shortbow_u" to Stringing("item.oak_shortbow", 20, 16.5),
        "item.oak_longbow_u" to Stringing("item.oak_longbow", 25, 25.0),
        "item.willow_shortbow_u" to Stringing("item.willow_shortbow", 35, 33.3),
        "item.willow_longbow_u" to Stringing("item.willow_longbow", 40, 41.5),
        "item.maple_shortbow_u" to Stringing("item.maple_shortbow", 50, 50.0),
        "item.maple_longbow_u" to Stringing("item.maple_longbow", 55, 58.3),
        "item.yew_shortbow_u" to Stringing("item.yew_shortbow", 65, 67.5),
        "item.yew_longbow_u" to Stringing("item.yew_longbow", 70, 75.0),
        "item.magic_shortbow_u" to Stringing("item.magic_shortbow", 80, 83.3),
        "item.magic_longbow_u" to Stringing("item.magic_longbow", 85, 91.5),
    ).filter { res(it.key) && res(it.value.strung) }

    init {
        cuts.forEach { c -> onItemOnItem("item.knife", c.log) { player.queue { cutMenu(this, player, c) } } }
        stringings.forEach { (unstrung, s) ->
            onItemOnItem("item.bow_string", unstrung) { player.queue { stringLoop(this, player, unstrung, s) } }
        }
    }

    private suspend fun QueueTask.cutMenu(task: QueueTask, player: Player, c: Cut) {
        val opts = mutableListOf("Shortbow" to Triple(c.shortU, c.shortLevel, c.shortXp),
            "Longbow" to Triple(c.longU, c.longLevel, c.longXp))
        if (c.log == "item.logs" && res("item.arrow_shaft")) opts.add("Arrow shafts" to Triple("item.arrow_shaft", 1, 5.0))
        val choice = options(player, *opts.map { it.first }.toTypedArray())
        val pick = opts.getOrNull(choice - 1) ?: return
        val (result, level, xp) = pick.second
        makeLoop(task, player, c.log, result, level, xp, arrows = result == "item.arrow_shaft")
    }

    private suspend fun makeLoop(task: QueueTask, player: Player, log: String, result: String, level: Int, xp: Double, arrows: Boolean) {
        while (player.inventory.contains(getRSCM(log))) {
            if (player.getSkills().getCurrentLevel(Skills.FLETCHING) < level) {
                player.message("You need a Fletching level of $level to make that.")
                return
            }
            task.wait(2)
            if (player.inventory.remove(item = getRSCM(log), amount = 1).completed == 0) break
            player.inventory.add(item = getRSCM(result), amount = if (arrows) 15 else 1)
            player.addXp(Skills.FLETCHING, xp)
        }
        player.message("You finish fletching.")
    }

    private suspend fun stringLoop(task: QueueTask, player: Player, unstrung: String, s: Stringing) {
        while (player.inventory.contains(getRSCM(unstrung)) && player.inventory.contains(getRSCM("item.bow_string"))) {
            if (player.getSkills().getCurrentLevel(Skills.FLETCHING) < s.level) {
                player.message("You need a Fletching level of ${s.level} to string that.")
                return
            }
            task.wait(2)
            if (player.inventory.remove(item = getRSCM(unstrung), amount = 1).completed == 0) break
            if (player.inventory.remove(item = getRSCM("item.bow_string"), amount = 1).completed == 0) {
                player.inventory.add(item = getRSCM(unstrung), amount = 1) // refund
                break
            }
            player.inventory.add(item = getRSCM(s.strung), amount = 1)
            player.addXp(Skills.FLETCHING, s.xp)
        }
        player.message("You add a string to the bow.")
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }
}
