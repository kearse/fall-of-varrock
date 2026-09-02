package org.alter.plugins.content.skills.hunter

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
 * **Hunter** (Phase 2). Use a bird snare or box trap on a thicket at the Lumbridge
 * hunting ground to set a trap and catch prey, looping until your inventory is full or
 * you walk away. Bird snares catch birds (feathers/meat/bones); box traps catch
 * chinchompas. Robust onItemOnObj model (no trap-object state machine for the MVP).
 */
class HunterPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val thicket = "object.bush_patch"
    // The Mire — collection grounds thickets (swamp, south of the graveyard working yard).
    // (The old Lumbridge home thickets near the church/graveyard were removed.)
    private val thicketTiles = listOf(
        Tile(3230, 3173, 0), Tile(3231, 3173, 0), Tile(3232, 3173, 0),
        // Croaking Thickets expansion (swamp buildout 2026-07-04) — a second row south.
        Tile(3230, 3171, 0), Tile(3232, 3171, 0),
    )

    private data class Chin(val item: String, val name: String, val level: Int, val xp: Double)
    private val boxLadder = listOf(
        Chin("item.chinchompa", "grey chinchompa", 53, 198.4), // wiki: grey chin needs 53 Hunter (was 27)
        Chin("item.red_chinchompa", "red chinchompa", 63, 265.0),
    ).filter { res(it.item) }

    init {
        if (res(thicket)) {
            // Spawn in onWorldInit so the region/collision is loaded first (matches MiningPlugin).
            onWorldInit { thicketTiles.forEach { world.spawn(DynamicObject(getRSCM(thicket), OBJ_TYPE, 0, it)) } }
            if (res("item.bird_snare")) onItemOnObj(obj = thicket, item = "item.bird_snare") { player.queue { snare(this, player) } }
            if (res("item.box_trap") && boxLadder.isNotEmpty()) onItemOnObj(obj = thicket, item = "item.box_trap") { player.queue { box(this, player) } }
        } else {
            logger.warn { "hunter: thicket '$thicket' not in cache; hunter disabled." }
        }
    }

    private suspend fun snare(task: QueueTask, player: Player) {
        val spot = player.tile
        while (player.tile == spot) {
            if (player.inventory.isFull) { player.message("Your inventory is full."); break }
            player.animate(SET_ANIM)
            player.message("You set a bird snare in the thicket...")
            task.wait(CATCH_TICKS)
            if (player.tile != spot) break
            if (player.inventory.isFull) break
            // Always catch feathers; sometimes meat; usually bones.
            if (res("item.feather")) player.inventory.add(item = getRSCM("item.feather"), amount = world.random(3..12))
            if (res("item.raw_bird_meat") && world.random(1) == 0) player.inventory.add(item = getRSCM("item.raw_bird_meat"), amount = 1)
            if (res("item.bones")) player.inventory.add(item = getRSCM("item.bones"), amount = 1)
            player.addXp(Skills.HUNTER, SNARE_XP)
            player.message("You catch a bird.")
        }
    }

    private suspend fun box(task: QueueTask, player: Player) {
        val lvl = player.getSkills().getCurrentLevel(Skills.HUNTER)
        if (lvl < boxLadder.first().level) {
            player.message("You need a Hunter level of ${boxLadder.first().level} to use a box trap.")
            return
        }
        val spot = player.tile
        while (player.tile == spot) {
            if (player.inventory.isFull) { player.message("Your inventory is full."); break }
            val catch = boxLadder.lastOrNull { lvl >= it.level } ?: boxLadder.first()
            player.animate(SET_ANIM)
            player.message("You set a box trap...")
            task.wait(CATCH_TICKS)
            if (player.tile != spot) break
            if (player.inventory.isFull) break
            // Box trapping can fail; success improves with level.
            if (world.randomDouble() < successChance(lvl, catch.level)) {
                player.inventory.add(item = getRSCM(catch.item), amount = 1)
                player.addXp(Skills.HUNTER, catch.xp)
                player.message("You catch a ${catch.name}.")
            } else {
                player.message("The creature escapes your trap.")
            }
        }
    }

    private fun successChance(level: Int, reqLevel: Int): Double = (0.5 + (level - reqLevel) * 0.01).coerceIn(0.4, 0.95)

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val SET_ANIM = 827
        const val CATCH_TICKS = 4
        const val SNARE_XP = 40.0
    }
}
