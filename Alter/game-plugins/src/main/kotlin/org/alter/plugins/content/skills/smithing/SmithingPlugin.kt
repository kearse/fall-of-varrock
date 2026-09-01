package org.alter.plugins.content.skills.smithing

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
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
 *
 * Both stations open the client-drawn **making window** (`lofmake` — the reusable production
 * UI: recipe rows with real item icons, level/material checks, 1/5/10/ALL quantity picker):
 *  - **Smelt** ore on the furnace → pick a bar recipe; coal tiers consume coal.
 *  - **Smith** a bar on the anvil (with a hammer) → pick a piece for that metal.
 *
 * Channels (docs/overlay-design-system.md §8): recipes ride a `~LOFMAKE~` CONSOLE push
 * (header `H|<kind>|<title>|<n>` then one `R|<i>|resultId|level|xp10|matId:qty;...` line per
 * recipe), the window opens on a varp 4625 pulse (value = kind), and the make comes back as
 * `::make <resultId> <qty>` → `makeclick`.
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
        // OSRS level offsets above the metal's base (bronze: dagger 1, scimitar 5, full helm 7,
        // kiteshield 12, platebody 18). Kiteshield and platebody were 3 and 7 levels too low.
        Piece("dagger", "dagger", 1, 0),
        Piece("scimitar", "scimitar", 2, 4),
        Piece("full_helm", "full helm", 2, 6),
        Piece("kiteshield", "kiteshield", 3, 11),
        Piece("platebody", "platebody", 5, 17),
    )

    /** metal prefix, base smithing level, and Smithing xp PER BAR (OSRS: 12.5×metal tier). */
    private data class Metal(val prefix: String, val baseLevel: Int, val xpPerBar: Double)

    /** bar item key -> metal. */
    private val metalByBar = mapOf(
        "item.bronze_bar" to Metal("bronze", 1, 12.5),
        "item.iron_bar" to Metal("iron", 15, 25.0),
        "item.steel_bar" to Metal("steel", 30, 37.5),
        "item.mithril_bar" to Metal("mithril", 50, 50.0),
        "item.adamantite_bar" to Metal("adamant", 70, 62.5),
        "item.runite_bar" to Metal("rune", 85, 75.0),
    )

    init {
        // Spawn in onWorldInit so the region/collision is loaded first (matches MiningPlugin).
        onWorldInit {
            if (res(furnace)) world.spawn(DynamicObject(getRSCM(furnace), OBJ_TYPE, furnaceRot, furnaceTile))
            // Two anvils: the onItemOnObj(anvil) bind below fires for every anvil object, so
            // both work with no extra wiring.
            if (res(anvil)) anvilTiles.forEach { world.spawn(DynamicObject(getRSCM(anvil), OBJ_TYPE, 0, it)) }
        }

        // Using any ore on the furnace opens the smelt window.
        bars.flatMap { it.ores.keys }.distinct().forEach { ore ->
            onItemOnObj(obj = furnace, item = ore) { openFurnace(player, stationOf(player)) }
        }
        // Using a bar on the anvil opens the smith window for that metal.
        metalByBar.keys.filter { res(it) }.forEach { bar ->
            onItemOnObj(obj = anvil, item = bar) { openAnvil(player, bar, stationOf(player)) }
        }
        // ...and the objects' OWN options, which is how a player actually uses them (right-click
        // the furnace → Smelt, the anvil → Smith). Without these binds the option was inert.
        // Guarded like MiningPlugin's "Mine": onObjOption THROWS when the cache def lacks the
        // exact action, and a throwing init drops the WHOLE plugin.
        bindObjOptions(furnace, "Smelt", "Smelt-ore", "Use") { openFurnace(player, stationOf(player)) }
        bindObjOptions(anvil, "Smith", "Smith-armour") { openAnvilAuto(player, stationOf(player)) }

        // The window's make channel ("::make <resultId> <qty>" → makeclick). Also testable directly.
        onCommand("makeclick", description = "Making window action (client overlay channel)") {
            val a = player.getCommandArgs()
            val resultId = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val qty = (a.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 28 * 5)
            // The whole ::-routed command path is otherwise silent; log so a dead MAKE button leaves a trace.
            logger.info { "smithing: makeclick result=$resultId qty=$qty station=${player.attr[STATION_ATTR]} at=${player.tile}" }
            make(player, resultId, qty)
        }
    }

    // ---------------------------------- binds ----------------------------------

    /**
     * Bind every [options] entry the cache def actually carries. `onObjOption(name)` THROWS when
     * the action is missing, and a plugin whose init throws is silently dropped by the loader —
     * so the def is checked first (same guard as MiningPlugin's "Mine").
     */
    private fun bindObjOptions(obj: String, vararg options: String, logic: (org.alter.game.plugin.Plugin).() -> Unit) {
        val id = resOrNull(obj) ?: return
        val actions = try {
            getObject(id).actions?.filterNotNull().orEmpty()
        } catch (e: Exception) { emptyList() }
        val bound = options.filter { opt -> actions.any { it.equals(opt, true) } }
        if (bound.isEmpty()) {
            logger.warn { "smithing: object $obj ($id) has none of ${options.toList()} — options=$actions" }
            return
        }
        bound.forEach { onObjOption(obj = id, option = it, logic = logic) }
    }

    /**
     * The tile of the station being used, remembered so [make] can verify the player is still
     * standing at it. Falls back to the player's OWN tile when the interaction object can't be
     * resolved — never to a hard-coded forge. The interacting object is a WeakReference
     * ([org.alter.api.ext.getInteractingGameObj]) that doesn't always resolve at open time
     * (e.g. the Mire furnace at 3237,3192), and the old cellar fallback (3208,9620) then put the
     * station ~6400 tiles away, so [make]'s radius check rejected a player standing at the very
     * furnace they'd just opened ("You need to be at a furnace to smelt."). The window only opens
     * AT a station and closes on walk, so the player's own tile is always adjacent to it — the
     * radius check then passes at any furnace/anvil in the world.
     */
    private fun stationOf(p: Player): Tile {
        val tile = try { p.getInteractingGameObj().tile } catch (e: Exception) { null }
        return tile ?: p.attr[STATION_ATTR] ?: p.tile
    }

    // ---------------------------------- window push ----------------------------------

    /** Push the smelt recipe list and pulse the window open (kind 1 = furnace). */
    private fun openFurnace(p: Player, station: Tile) {
        p.attr[STATION_ATTR] = station
        p.message("${PREFIX}H|1|Furnace|${bars.size}", ChatMessageType.CONSOLE)
        bars.forEachIndexed { i, b ->
            val sb = StringBuilder(PREFIX)
                .append("R|").append(i).append('|').append(getRSCM(b.bar)).append('|')
                .append(b.level).append('|').append((b.xp * 10).toInt()).append('|')
            b.ores.forEach { (ore, n) -> sb.append(getRSCM(ore)).append(':').append(n).append(';') }
            if (b.coal > 0) sb.append(getRSCM("item.coal")).append(':').append(b.coal).append(';')
            p.message(sb.toString(), ChatMessageType.CONSOLE)
        }
        pulse(p, kind = 1)
    }

    /**
     * Clicking the anvil's own "Smith" option carries no bar, so pick the best metal the player
     * is actually carrying (highest smithing level they can use), the way the real anvil would.
     */
    private fun openAnvilAuto(p: Player, station: Tile) {
        val level = p.getSkills().getCurrentLevel(Skills.SMITHING)
        val best = metalByBar.entries
            .filter { res(it.key) && p.inventory.contains(getRSCM(it.key)) }
            .filter { it.value.baseLevel <= level }
            .maxByOrNull { it.value.baseLevel }
        if (best == null) {
            p.message("You have no bars you can smith here.")
            return
        }
        openAnvil(p, best.key, station)
    }

    /** Push the smith recipe list for [barKey]'s metal and pulse the window open (kind 2 = anvil). */
    private fun openAnvil(p: Player, barKey: String, station: Tile) {
        val (metal, baseLevel, xpPerBar) = metalByBar[barKey] ?: return
        if (!p.inventory.contains(getRSCM("item.hammer"))) {
            p.message("You need a hammer to work the metal.")
            return
        }
        p.attr[STATION_ATTR] = station
        val barId = getRSCM(barKey)
        val makeable = pieces.mapNotNull { piece -> resOrNull("item.${metal}_${piece.key}")?.let { it to piece } }
        if (makeable.isEmpty()) return
        val title = "Anvil — ${metal.replaceFirstChar { it.uppercase() }}"
        p.message("${PREFIX}H|2|$title|${makeable.size}", ChatMessageType.CONSOLE)
        makeable.forEachIndexed { i, (resultId, piece) ->
            p.message(
                "${PREFIX}R|$i|$resultId|${(baseLevel + piece.levelOffset).coerceAtMost(99)}|${(piece.bars * xpPerBar * 10).toInt()}|$barId:${piece.bars};",
                ChatMessageType.CONSOLE,
            )
        }
        pulse(p, kind = 2)
    }

    private fun pulse(p: Player, kind: Int) {
        p.setVarp(OPEN_VARP, kind)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }

    // ---------------------------------- making ----------------------------------

    /**
     * Route a make request to the matching smelt/smith recipe by RESULT item id. The station
     * check keeps the old onItemOnObj invariant: `::make` arrives as a raw chat token from
     * anywhere, but production only ever happens AT the furnace/anvil.
     */
    private fun make(p: Player, resultId: Int, qty: Int) {
        // Validate against the station the window was OPENED at, not a hard-coded tile: the
        // furnace/anvil object ids exist all over the world, so a fixed cellar tile silently
        // refused every other forge.
        val station = p.attr[STATION_ATTR]
        bars.firstOrNull { resOrNull(it.bar) == resultId }?.let { bar ->
            if (station == null || !p.tile.isWithinRadius(station, STATION_RADIUS)) {
                p.message("You need to be at a furnace to smelt.")
                return
            }
            p.queue { smelt(this, p, bar, qty) }
            return
        }
        for ((barKey, metalInfo) in metalByBar) {
            val (metal, baseLevel, xpPerBar) = metalInfo
            for (piece in pieces) {
                if (resOrNull("item.${metal}_${piece.key}") == resultId) {
                    if (station == null || !p.tile.isWithinRadius(station, STATION_RADIUS)) {
                        p.message("You need to be at an anvil to smith.")
                        return
                    }
                    p.queue { smith(this, p, barKey, metal, baseLevel, xpPerBar, piece, qty) }
                    return
                }
            }
        }
        // No bar or smithable piece matched the requested result id: never a silent no-op.
        logger.warn { "smithing: make() no recipe for result=$resultId (qty=$qty)" }
        p.message("You can't make that here.")
    }

    private suspend fun smelt(task: QueueTask, player: Player, bar: Bar, qty: Int) {
        var left = qty
        while (left > 0) {
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
            // Re-verify AFTER the suspension: the inventory can change during the wait
            // (e.g. dropping ores), and unchecked removes would still mint the bar.
            if (bar.ores.any { (ore, n) -> player.inventory.getItemCount(getRSCM(ore)) < n } ||
                (bar.coal > 0 && player.inventory.getItemCount(getRSCM("item.coal")) < bar.coal)
            ) {
                player.message("You don't have enough ore to smelt a ${bar.label.lowercase()}.")
                return
            }
            if (bar.ores.any { (ore, n) -> player.inventory.remove(item = getRSCM(ore), amount = n).completed < n }) {
                return
            }
            if (bar.coal > 0 && player.inventory.remove(item = getRSCM("item.coal"), amount = bar.coal).completed < bar.coal) {
                return
            }
            // Iron ore fails to smelt 50% of the time (OSRS), consuming the ore for nothing —
            // without a ring of forging / superheat. The ore was already removed above.
            if (bar.bar == "item.iron_bar" && world.chance(1, 2)) {
                player.message("The iron is too impure and you fail to refine it.")
                left--
                continue
            }
            player.inventory.add(item = getRSCM(bar.bar), amount = 1)
            player.addXp(Skills.SMITHING, bar.xp)
            player.message("You smelt a ${bar.label.lowercase()}.")
            left--
        }
    }

    private suspend fun smith(task: QueueTask, player: Player, bar: String, metal: String, baseLevel: Int, xpPerBar: Double, piece: Piece, qty: Int) {
        val result = resOrNull("item.${metal}_${piece.key}") ?: return
        val needLevel = (baseLevel + piece.levelOffset).coerceAtMost(99)
        if (!player.inventory.contains(getRSCM("item.hammer"))) {
            player.message("You need a hammer to work the metal.")
            return
        }
        var left = qty
        while (left > 0) {
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
            // Checked remove AFTER the suspension: bars dropped during the wait must
            // abort the piece, not still produce it.
            if (player.inventory.remove(item = getRSCM(bar), amount = piece.bars).completed < piece.bars) {
                player.message("You need ${piece.bars} ${metal} bars to make a ${piece.label}.")
                return
            }
            player.inventory.add(item = result, amount = 1)
            player.addXp(Skills.SMITHING, piece.bars * xpPerBar)
            player.message("You hammer the metal into a $metal ${piece.label}.")
            left--
        }
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }
    private fun resOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val OBJ_TYPE = 10
        const val SMELT_ANIM = 899
        const val SMITH_ANIM = 898
        const val BAR_SMITH_XP = 25.0 // smithing xp per bar used

        /** Overlay-open varp (docs/overlay-design-system.md §8): pulsed value = kind (1 furnace, 2 anvil). */
        const val OPEN_VARP = 4625
        const val PREFIX = "~LOFMAKE~"

        /** How close a `::make` must be to its station (the furnace block is 3x3 from its SW tile). */
        const val STATION_RADIUS = 6

        /** The station tile the making window was last opened at (see [make]). */
        val STATION_ATTR = AttributeKey<Tile>()
    }
}
