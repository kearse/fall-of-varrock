package org.alter.tools.shortcutscan

import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.loadLocations
import dev.openrune.cache.filestore.loadTerrain
import dev.openrune.cache.util.XteaLoader
import java.io.File
import java.nio.file.Path

/**
 * **Agility-shortcut scan** — find every object in the cache that carries an agility-shortcut
 * action verb (Climb-over, Cross, Squeeze-through, Jump, Balance, Grapple, ...) and then locate
 * every placement of those objects in the world. Output is two TSVs under [OUT_DIR]:
 *
 *   - `shortcut-defs.tsv`  — one row per matching object def: id, name, actions
 *   - `shortcut-locs.tsv`  — one row per world placement: worldX, worldZ, level, id, name,
 *                            actions, type, orient, sizeX, sizeY
 *
 * This is ground truth for building [org.alter.plugins.content.skills.agility] bindings: real
 * rev-228 object ids at their real tiles, so the shortcut plugin binds to objects that actually
 * exist on the map instead of guessed ids. Mirrors the cache decode in
 * [org.alter.tools.mapdump.MapDumpTool]. Level requirements are NOT in the cache — those are
 * OSRS design data and get encoded per-shortcut in the plugin.
 *
 * Run (workingDir = Alter root; needs the JDK-17 env):
 *   gradlew :game-server:shortcutScan
 */

private const val CACHE_PATH = "data/cache"
private const val XTEA_PATH = "data/xteas.json"
private const val OUT_DIR = "data/shortcutscan"
private const val REVISION = 228

private const val MAX_OBJ_ID = 70000

/**
 * Substrings (lowercase) of clickable actions that mark an agility-style obstacle/shortcut.
 * Deliberately broad — over-capture is fine, the TSV is for a human to curate. Excludes plain
 * "climb-up"/"climb-down" on their own (those are mostly ladders/staircases, not agility); the
 * rock-style shortcuts use bare "climb"/"climb-over"/"scale" which ARE included.
 */
private val SHORTCUT_VERBS = listOf(
    "climb-over", "climb-across", "climb-into", "climb-through", "climb-up rocks",
    "cross", "squeeze", "jump", "leap", "balance", "walk-across", "walk-on",
    "tightrope", "grapple", "swing", "vault", "crawl", "hurdle", "traverse",
    "stepping", "step-over", "scramble", "scale", "shimmy", "teeter", "wade",
    "hop-over", "hop", "edge-along", "run-across", "clamber",
)

private fun matchVerb(action: String): Boolean {
    val a = action.lowercase()
    return SHORTCUT_VERBS.any { a.contains(it) }
}

private data class DefRow(val id: Int, val name: String, val actions: List<String>)

private const val BLOCKED_TILE = 0x1

/**
 * The ids actually bound by AgilityShortcutPlugin, with their crossing kind. Kept in sync by
 * hand (game-plugins isn't on this tool's classpath). CROSS = land past the obstacle's far edge;
 * ONTO = land on the object tile (stepping stones — can't terrain-audit, reported for manual eyes).
 */
private val BOUND_CROSS = listOf(
    993, 3730, 7527, 12982, 22302, 19222, 544, 2618, 18411, 2962, 2963, 2964, 3309, 5847,
    15186, 15187, 15194, 15195, 15196, 15197, 16539, 16543, 16509, 16511, 20210,
    23136, 23137, 23138, 23139, 23140, 16532, 17002, 22552,
    3553, 3555, 3557, 3931, 3932, 3933, 16540, 16542, 16546, 16548, 20882, 20884,
    23144, 23145, 23274, 23542, 5002, 21306, 21307, 21308, 21309, 21315, 21316, 21317, 21318, 21319,
)
private val BOUND_ONTO = listOf(
    4411, 5948, 5949, 10663, 11643, 11768, 13504, 15412, 16466, 16513, 16533, 19040, 19042, 23556,
    21126, 21127, 21128, 21129, 21130, 21131, 21132, 21133,
)

fun main(args: Array<String>) {
    val outDir = File(OUT_DIR).apply { mkdirs() }
    println("shortcut-scan: cache=$CACHE_PATH rev=$REVISION out=$OUT_DIR")

    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)

    val xteaFile = File(XTEA_PATH)
    if (xteaFile.exists()) {
        XteaLoader.load(xteaFile)
        println("loaded xteas from $XTEA_PATH")
    } else {
        println("WARNING: $XTEA_PATH not found — placements will be empty (terrain-only cache).")
    }

    if (args.getOrNull(0)?.lowercase() == "audit") {
        audit(outDir)
        return
    }

    if (args.getOrNull(0)?.lowercase() == "anims") {
        // Verify candidate animation ids exist in the cache (frame count > 0).
        val ids = args.drop(1).mapNotNull { it.toIntOrNull() }
        ids.forEach { id ->
            val def = try {
                CacheManager.getAnim(id)
            } catch (e: Exception) {
                null
            }
            val cl = def?.let { runCatching { it.cycleLength }.getOrDefault(-1) } ?: -1
            println("anim $id -> ${if (def == null) "MISSING" else "EXISTS cycleLength=$cl"}")
        }
        return
    }

    // 1) Object defs whose actions contain a shortcut verb.
    val matching = HashMap<Int, DefRow>()
    for (id in 0..MAX_OBJ_ID) {
        val def = CacheManager.getObjectOrDefault(id)
        val actions = def.actions?.filterNotNull()?.filter { it.isNotBlank() } ?: continue
        if (actions.isEmpty()) continue
        if (actions.any { matchVerb(it) }) {
            val name = def.name?.ifBlank { "null" } ?: "null"
            matching[id] = DefRow(id, name, actions)
        }
    }
    println("matched ${matching.size} object def(s) with a shortcut verb")

    File(outDir, "shortcut-defs.tsv").printWriter().use { w ->
        w.println("id\tname\tactions")
        matching.values.sortedBy { it.id }.forEach { d ->
            w.println("${d.id}\t${d.name}\t${d.actions.joinToString(" | ")}")
        }
    }

    // 2) Every placement of those ids across all regions in the cache.
    var placements = 0
    File(outDir, "shortcut-locs.tsv").printWriter().use { w ->
        w.println("worldX\tworldZ\tlevel\tid\tname\tactions\ttype\torient\tsizeX\tsizeY")
        for (rx in 0..255) for (ry in 0..255) {
            if (CacheManager.cache.data(MAPS, "m${rx}_$ry") == null) continue
            val id = (rx shl 8) or ry
            val keys = XteaLoader.getKeys(id) ?: IntArray(4)
            val landData = try {
                CacheManager.cache.data(MAPS, "l${rx}_$ry", keys)
            } catch (e: Exception) {
                null
            } ?: continue
            val baseX = rx shl 6
            val baseZ = ry shl 6
            try {
                loadLocations(landData) { loc ->
                    val d = matching[loc.id] ?: return@loadLocations
                    val def = CacheManager.getObjectOrDefault(loc.id)
                    val sx = if (loc.orientation and 1 == 1) def.sizeY else def.sizeX
                    val sy = if (loc.orientation and 1 == 1) def.sizeX else def.sizeY
                    w.println(
                        "${baseX + loc.localX}\t${baseZ + loc.localY}\t${loc.height}\t${loc.id}\t${d.name}" +
                            "\t${d.actions.joinToString(" | ")}\t${loc.type}\t${loc.orientation}\t$sx\t$sy",
                    )
                    placements++
                }
            } catch (e: Exception) {
                // region with locs we can't decode — skip, terrain-only
            }
        }
    }
    println("found $placements placement(s) -> $OUT_DIR/shortcut-locs.tsv")
    println("done.")
}

// ============================================================================ audit

/** Lazily-decoded terrain-blocked grid per region (mirrors MapDumpTool's collision decode). */
private val terrainCache = HashMap<Int, Array<Array<BooleanArray>>?>()

private fun terrain(regionId: Int): Array<Array<BooleanArray>>? = terrainCache.getOrPut(regionId) {
    val rx = regionId shr 8
    val ry = regionId and 0xFF
    val mapData = CacheManager.cache.data(MAPS, "m${rx}_$ry") ?: return@getOrPut null
    val tiles = loadTerrain(mapData)
    Array(4) { z -> Array(64) { x -> BooleanArray(64) { y -> (tiles[z][x][y].settings.toInt() and BLOCKED_TILE) != 0 } } }
}

/** null = no map data for that tile; true = standable (not terrain-blocked). */
private fun standable(x: Int, z: Int, level: Int): Boolean? {
    val grid = terrain(((x shr 6) shl 8) or (z shr 6)) ?: return null
    return !grid[level][x and 63][z and 63]
}

private enum class Verdict { PASS, FLAG, NODATA, MANUAL }

/**
 * Audit every bound placement: does the obstacle connect two standable areas? For CROSS we need a
 * standable tile on opposite sides along at least one axis (so a player can stand on one side and
 * the engine's computed landing on the far side is on the ground, not in water/void). ONTO
 * (stepping stones) sit on water by design and can't be terrain-audited — reported as MANUAL.
 */
private fun audit(outDir: File) {
    val crossSet = BOUND_CROSS.toHashSet()
    val ontoSet = BOUND_ONTO.toHashSet()
    val wanted = crossSet + ontoSet

    data class Row(val x: Int, val z: Int, val level: Int, val id: Int, val name: String, val verdict: Verdict, val reason: String)
    val rows = ArrayList<Row>()

    for (rx in 0..255) for (ry in 0..255) {
        if (CacheManager.cache.data(MAPS, "m${rx}_$ry") == null) continue
        val regionId = (rx shl 8) or ry
        val keys = XteaLoader.getKeys(regionId) ?: IntArray(4)
        val landData = try {
            CacheManager.cache.data(MAPS, "l${rx}_$ry", keys)
        } catch (e: Exception) {
            null
        } ?: continue
        val baseX = rx shl 6
        val baseZ = ry shl 6
        try {
            loadLocations(landData) { loc ->
                if (loc.id !in wanted) return@loadLocations
                val def = CacheManager.getObjectOrDefault(loc.id)
                val name = def.name?.ifBlank { "null" } ?: "null"
                val sx = (if (loc.orientation and 1 == 1) def.sizeY else def.sizeX).coerceAtLeast(1)
                val sy = (if (loc.orientation and 1 == 1) def.sizeX else def.sizeY).coerceAtLeast(1)
                val ox = baseX + loc.localX
                val oz = baseZ + loc.localY
                val lvl = loc.height

                val (verdict, reason) = if (loc.id in ontoSet) {
                    Verdict.MANUAL to "stepping-stone (terrain-audit n/a)"
                } else {
                    val cz = oz + (sy - 1) / 2
                    val cx = ox + (sx - 1) / 2
                    val west = standable(ox - 1, cz, lvl); val east = standable(ox + sx, cz, lvl)
                    val south = standable(cx, oz - 1, lvl); val north = standable(cx, oz + sy, lvl)
                    val xAxis = west == true && east == true
                    val yAxis = south == true && north == true
                    val anyNoData = listOf(west, east, south, north).any { it == null }
                    when {
                        xAxis || yAxis -> Verdict.PASS to "connected"
                        anyNoData -> Verdict.NODATA to "edge of mapped area (w=$west e=$east s=$south n=$north)"
                        else -> Verdict.FLAG to "no standable tiles on opposite sides (w=$west e=$east s=$south n=$north)"
                    }
                }
                rows.add(Row(ox, oz, lvl, loc.id, name, verdict, reason))
            }
        } catch (e: Exception) {
            // undecodable locs — skip
        }
    }

    // per-placement report
    File(outDir, "audit-placements.tsv").printWriter().use { w ->
        w.println("verdict\tworldX\tworldZ\tlevel\tid\tname\treason")
        rows.sortedWith(compareBy({ it.verdict.name }, { it.id }, { it.z })).forEach {
            w.println("${it.verdict}\t${it.x}\t${it.z}\t${it.level}\t${it.id}\t${it.name}\t${it.reason}")
        }
    }

    // per-id summary
    val byId = rows.groupBy { it.id }
    File(outDir, "audit-summary.tsv").printWriter().use { w ->
        w.println("id\tname\ttotal\tpass\tflag\tnodata\tmanual\tworstExample")
        byId.toSortedMap().forEach { (id, list) ->
            val name = list.first().name
            val pass = list.count { it.verdict == Verdict.PASS }
            val flag = list.count { it.verdict == Verdict.FLAG }
            val nodata = list.count { it.verdict == Verdict.NODATA }
            val manual = list.count { it.verdict == Verdict.MANUAL }
            val worst = list.firstOrNull { it.verdict == Verdict.FLAG }
            val example = if (worst != null) "(${worst.x},${worst.z},${worst.level})" else ""
            w.println("$id\t$name\t${list.size}\t$pass\t$flag\t$nodata\t$manual\t$example")
        }
    }

    val flagged = rows.count { it.verdict == Verdict.FLAG }
    val pass = rows.count { it.verdict == Verdict.PASS }
    val nodata = rows.count { it.verdict == Verdict.NODATA }
    val manual = rows.count { it.verdict == Verdict.MANUAL }
    println("audit: ${rows.size} bound placements -> PASS=$pass FLAG=$flagged NODATA=$nodata MANUAL=$manual")
    println("  ids with any FLAG: " + byId.filterValues { l -> l.any { it.verdict == Verdict.FLAG } }.keys.sorted())
    println("reports -> $OUT_DIR/audit-summary.tsv , audit-placements.tsv")
}
