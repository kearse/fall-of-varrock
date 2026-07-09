package org.alter.plugins.content.skills.runecraft

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
 * **Runecraft** (Phase 2). Use rune (or pure) essence on the Rune Altar placed by the
 * Lumbridge home to craft runes — you craft the highest-tier rune your level allows,
 * one per essence. Runes are consumed by Magic combat, so this is the supply side of the
 * magic consumption loop. (A simplified single multi-rune altar, not per-rune altars.)
 */
class RunecraftPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val altar = "object.fire_altar"
    private val altarTile = Tile(3235, 3204, 0)
    private val essences = listOf("item.rune_essence", "item.pure_essence").filter { res(it) }

    private data class Rune(val rune: String, val name: String, val level: Int, val xp: Double)
    private val ladder = listOf(
        Rune("item.air_rune", "air runes", 1, 5.0),
        Rune("item.mind_rune", "mind runes", 2, 5.5),
        Rune("item.water_rune", "water runes", 5, 6.0),
        Rune("item.earth_rune", "earth runes", 9, 6.5),
        Rune("item.fire_rune", "fire runes", 14, 7.0),
        Rune("item.body_rune", "body runes", 20, 7.5),
        Rune("item.nature_rune", "nature runes", 44, 9.0),
        Rune("item.law_rune", "law runes", 54, 9.5),
    ).filter { res(it.rune) }

    init {
        // No home altar spawn — the Mire muster-yard fire altar @3152,3162 serves Runecraft now
        // (binding is global by object id). The old Lumbridge home altar near the church was removed.
        if (res(altar) && ladder.isNotEmpty() && essences.isNotEmpty()) {
            essences.forEach { ess ->
                onItemOnObj(obj = altar, item = ess) { player.queue { craft(this, player, ess) } }
            }
        }
    }

    private suspend fun craft(task: QueueTask, player: Player, essence: String) {
        while (player.inventory.contains(getRSCM(essence))) {
            val best = ladder.lastOrNull { player.getSkills().getCurrentLevel(Skills.RUNECRAFTING) >= it.level } ?: ladder.first()
            task.wait(1)
            if (player.inventory.remove(item = getRSCM(essence), amount = 1).completed == 0) break
            player.inventory.add(item = getRSCM(best.rune), amount = 1)
            player.addXp(Skills.RUNECRAFTING, best.xp)
        }
        player.animate(CRAFT_ANIM)
        player.message("You bind the temple's power into runes.")
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val CRAFT_ANIM = 791
    }
}
