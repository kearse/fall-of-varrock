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
    private val essences = listOf("item.rune_essence", "item.pure_essence").filter { res(it) }

    // multiStep = the level interval at which one EXTRA rune is produced per essence (OSRS
    // multiple runes): count = 1 + level/multiStep. e.g. air 11 → 2x@11..10x@99; nature 91 → 2x@91.
    private data class Rune(val rune: String, val name: String, val level: Int, val xp: Double, val multiStep: Int)
    private val ladder = listOf(
        Rune("item.air_rune", "air runes", 1, 5.0, 11),
        Rune("item.mind_rune", "mind runes", 2, 5.5, 14),
        Rune("item.water_rune", "water runes", 5, 6.0, 19),
        Rune("item.earth_rune", "earth runes", 9, 6.5, 26),
        Rune("item.fire_rune", "fire runes", 14, 7.0, 35),
        Rune("item.body_rune", "body runes", 20, 7.5, 46),
        Rune("item.nature_rune", "nature runes", 44, 9.0, 91),
        Rune("item.law_rune", "law runes", 54, 9.5, 95),
    ).filter { res(it.rune) }

    init {
        // No home altar spawn — the Mire's fire altar at (3238,3200) (SwampHubPlugin) serves Runecraft
        // (binding is global by object id). The old Lumbridge home altar near the church was removed.
        if (res(altar) && ladder.isNotEmpty() && essences.isNotEmpty()) {
            essences.forEach { ess ->
                onItemOnObj(obj = altar, item = ess) { player.queue { craft(this, player, ess) } }
            }
            // A bare click crafts whatever essence is carried, or explains the loop ("no direction",
            // 2026-09-03): the cache altar's own verbs are Light/hidden — "Craft-rune" is what OSRS
            // altars offer, so try that and fall back to the first verb the cache actually has.
            val bound = listOf("Craft-rune", "Light").any { verb ->
                runCatching { onObjOption(obj = altar, option = verb) { craftOrHint(player) } }.isSuccess
            }
            if (!bound) logger.info { "runecraft: fire altar has no clickable verb in the cache; use-essence-on-altar only." }
        }
    }

    private fun craftOrHint(player: Player) {
        val carried = essences.firstOrNull { player.inventory.contains(getRSCM(it)) }
        if (carried != null) {
            player.queue { craft(this, player, carried) }
        } else {
            player.message("The altar hums. Bring <col=801700>rune essence</col> — mine it from the two essence rocks just south (3237,3190) — and use it on the altar.")
        }
    }

    private suspend fun craft(task: QueueTask, player: Player, essence: String) {
        while (player.inventory.contains(getRSCM(essence))) {
            val lvl = player.getSkills().getCurrentLevel(Skills.RUNECRAFTING)
            val best = ladder.lastOrNull { lvl >= it.level } ?: ladder.first()
            task.wait(1)
            if (player.inventory.remove(item = getRSCM(essence), amount = 1).completed == 0) break
            // OSRS multiple runes: one essence yields several runes at the right levels.
            val count = 1 + (lvl / best.multiStep)
            player.inventory.add(item = getRSCM(best.rune), amount = count)
            player.addXp(Skills.RUNECRAFTING, best.xp) // xp is per essence, not per rune
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
