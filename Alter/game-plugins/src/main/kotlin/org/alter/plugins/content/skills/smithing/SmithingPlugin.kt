package org.alter.plugins.content.skills.smithing

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
 * **Smithing** (Phase 2 / economy). Closes the mining → bars → gear chain and is a key
 * economy feeder/sink. A furnace + anvil are placed in the Lumbridge cellar beside the
 * mine + Smithing Apprentice (one underground crafting hub).
 *  - **Smelt** ore on the furnace → bars (a menu picks the bar; coal-based tiers consume coal).
 *  - **Smith** a bar on the anvil (with a hammer) → pick a piece to forge.
 */
class SmithingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val furnace = "object.furnace"
    private val anvil = "object.anvil"
    // Layout (per the user's plan): the mine room is opened up by MiningPlugin.openMineRoom()
    // and the ores line the east + south walls, so the forge takes the west + north sides.
    // Furnace against the WEST wall, SW corner (3208,9620) → 3x3 block 3208-3210 x 9620-9622,
    // facing east. Kept clear of the north wall (flush against z9624 made the model clip into
    // it / render transparent). TWO anvils along the NORTH at (3212,9624) and (3215,9624).
    private val furnaceTile = Tile(3208, 9620, 0)
    private val furnaceRot = 0 // face east (mouth toward the room). rot mapping: 0=E,1=S,2=W,3=N
    private val anvilTiles = listOf(Tile(3212, 9624, 0), Tile(3215, 9624, 0))

    /** A bar recipe: ore(s) + coal → bar. */
    private data class Bar(val label: String, val bar: String, val ores: Map<String, Int>, val coal: Int, val level: Int, val xp: Double)

    private val bars = listOf(
        Bar("Bronze bar", "item.bronze_bar", mapOf("item.copper_ore" to 1, "item.tin_ore" to 1), 0, 1, 6.2),
        Bar("Iron bar", "item.iron_bar", mapOf("item.iron_ore" to 1), 0, 15, 12.5),
        Bar("Steel bar", "item.steel_bar", mapOf("item.iron_ore" to 1), 2, 30, 17.5),
        Bar("Mithril bar", "item.mithril_bar", mapOf("item.mithril_ore" to 1), 4, 50, 30.0),
        Bar("Adamant bar", "item.adamantite_bar", mapOf("item.adamantite_ore" to 1), 6, 70, 37.5),
        Bar("Rune bar", "item.runite_bar", mapOf("item.runite_ore" to 1), 8, 85, 50.0),
    ).filter { res(it.bar) && it.ores.keys.all { o -> res(o) } }

    /** A smithable piece: bar count + level offset above the metal's base. */
    private data class Piece(val key: String, val label: String, val bars: Int, val levelOffset: Int)

    private val pieces = listOf(
        Piece("dagger", "dagger", 1, 0),
        Piece("scimitar", "scimitar", 2, 4),
        Piece("full_helm", "full helm", 2, 6),
        Piece("kiteshield", "kiteshield", 3, 8),
        Piece("platebody", "platebody", 5, 10),
    )

    /** bar item key -> (metal prefix, base smithing level). */
    private val metalByBar = mapOf(
        "item.bronze_bar" to ("bronze" to 1),
        "item.iron_bar" to ("iron" to 15),
        "item.steel_bar" to ("steel" to 30),
        "item.mithril_bar" to ("mithril" to 50),
        "item.adamantite_bar" to ("adamant" to 70),
        "item.runite_bar" to ("rune" to 85),
    )

    init {
        // Spawn in onWorldInit so the region/collision is loaded first (matches MiningPlugin).
        onWorldInit {
            if (res(furnace)) world.spawn(DynamicObject(getRSCM(furnace), OBJ_TYPE, furnaceRot, furnaceTile))
            // Two anvils: the onItemOnObj(anvil) bind below fires for every anvil object, so
            // both work with no extra wiring.
            if (res(anvil)) anvilTiles.forEach { world.spawn(DynamicObject(getRSCM(anvil), OBJ_TYPE, 0, it)) }
        }

        // Using any ore on the furnace opens the smelt menu.
        bars.flatMap { it.ores.keys }.distinct().forEach { ore ->
            onItemOnObj(obj = furnace, item = ore) { player.queue { smeltMenu(this, player) } }
        }
        // Using a bar on the anvil opens the smith menu for that metal.
        metalByBar.keys.filter { res(it) }.forEach { bar ->
            onItemOnObj(obj = anvil, item = bar) { player.queue { smithMenu(this, player, bar) } }
        }
    }

    private suspend fun QueueTask.smeltMenu(task: QueueTask, player: Player) {
        if (bars.isEmpty()) return
        val choice = options(player, *bars.map { it.label }.toTypedArray())
        val bar = bars.getOrNull(choice - 1) ?: return
        smelt(task, player, bar)
    }

    private suspend fun smelt(task: QueueTask, player: Player, bar: Bar) {
        while (true) {
            if (player.getSkills().getCurrentLevel(Skills.SMITHING) < bar.level) {
                player.message("You need a Smithing level of ${bar.level} to smelt a ${bar.label.lowercase()}.")
                return
            }
            if (bar.ores.any { (ore, n) -> player.inventory.getItemCount(getRSCM(ore)) < n } ||
                (bar.coal > 0 && player.inventory.getItemCount(getRSCM("item.coal")) < bar.coal)
            ) {
                player.message("You don't have enough ore to smelt a ${bar.label.lowercase()}.")
                return
            }
            player.animate(SMELT_ANIM)
            task.wait(3)
            bar.ores.forEach { (ore, n) -> player.inventory.remove(item = getRSCM(ore), amount = n) }
            if (bar.coal > 0) player.inventory.remove(item = getRSCM("item.coal"), amount = bar.coal)
            player.inventory.add(item = getRSCM(bar.bar), amount = 1)
            player.addXp(Skills.SMITHING, bar.xp)
            player.message("You smelt a ${bar.label.lowercase()}.")
        }
    }

    private suspend fun QueueTask.smithMenu(task: QueueTask, player: Player, bar: String) {
        val (metal, baseLevel) = metalByBar[bar] ?: return
        if (!player.inventory.contains(getRSCM("item.hammer"))) {
            player.message("You need a hammer to work the metal.")
            return
        }
        val makeable = pieces.mapNotNull { p -> resOrNull("item.${metal}_${p.key}")?.let { p } }
        if (makeable.isEmpty()) return
        val choice = options(player, *makeable.map { "${metal.replaceFirstChar { c -> c.uppercase() }} ${it.label} (${it.bars} bar${if (it.bars > 1) "s" else ""})" }.toTypedArray())
        val piece = makeable.getOrNull(choice - 1) ?: return
        smith(task, player, bar, metal, baseLevel, piece)
    }

    private suspend fun smith(task: QueueTask, player: Player, bar: String, metal: String, baseLevel: Int, piece: Piece) {
        val result = resOrNull("item.${metal}_${piece.key}") ?: return
        val needLevel = baseLevel + piece.levelOffset
        while (true) {
            if (player.getSkills().getCurrentLevel(Skills.SMITHING) < needLevel) {
                player.message("You need a Smithing level of $needLevel to make that.")
                return
            }
            if (player.inventory.getItemCount(getRSCM(bar)) < piece.bars) {
                player.message("You need ${piece.bars} ${metal} bars to make a ${piece.label}.")
                return
            }
            player.animate(SMITH_ANIM)
            task.wait(4)
            player.inventory.remove(item = getRSCM(bar), amount = piece.bars)
            player.inventory.add(item = result, amount = 1)
            player.addXp(Skills.SMITHING, piece.bars * BAR_SMITH_XP)
            player.message("You hammer the metal into a $metal ${piece.label}.")
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }
    private fun resOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val OBJ_TYPE = 10
        const val SMELT_ANIM = 899
        const val SMITH_ANIM = 898
        const val BAR_SMITH_XP = 25.0 // smithing xp per bar used
    }
}
