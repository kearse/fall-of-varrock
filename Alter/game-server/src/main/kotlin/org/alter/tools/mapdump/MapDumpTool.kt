package org.alter.tools.mapdump

import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.loadLocations
import dev.openrune.cache.filestore.loadTerrain
import dev.openrune.cache.util.XteaLoader
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * **Map dump** — decode the world straight out of the cache into formats Claude (and you)
 * can read, so nobody has to hand-feed coordinates. Mirrors the authoritative server decode
 * in [org.alter.game.fs.DefinitionSet]: terrain → blocked tiles, locs → objects.
 *
 * For each region it emits, under [OUT_DIR]:
 *   - `r<id>_<rx>_<ry>.txt`   — per-level 64×64 ASCII collision grid + the object list (cheap
 *                               for an LLM to read; north is UP). Legend in the header.
 *   - `r<id>_<rx>_<ry>.png`   — schematic render at [TILE_PX]px/tile (walkable / blocked terrain /
 *                               bridge / object footprint / wall).
 *   - plus a combined `stitched.png` (1px/tile, all dumped regions placed by world position)
 *     and `INDEX.md` mapping every region file to its world bounds.
 *
 * ### What's accurate vs schematic
 *   - **Terrain collision is byte-exact** — same `loadTerrain` + `BLOCKED_TILE`/`BRIDGE_TILE`
 *     bits the engine clips on. This is unencrypted, so it always works.
 *   - **Objects** need XTEA (`data/xteas.json`, loaded by [XteaLoader]). Missing keys → that
 *     region's locs are skipped (terrain still dumps). Object *footprints* are marked from the
 *     loc type + the object def's size/clipType — faithful enough for "where are the walls /
 *     buildings / gaps", not a substitute for the engine's exact per-edge wall flags.
 *
 * ### Region selection (arg 0)
 *   - `home`                       → Lumbridge + the war corridor (regionX 48..51, regionY 49..52)
 *   - `region <id> [<id> ...]`     → explicit region ids ((rx shl 8) or ry)
 *   - `box <rxMin> <ryMin> <rxMax> <ryMax>`  → inclusive rectangle of region coords
 *   - `tile <x> <z>`               → the single region containing world tile (x,z)
 *   - `all`                        → every region present in the cache (big — dumps to disk only)
 *
 * Run (workingDir = repo root = the Alter project dir):
 *   gradlew :game-server:mapDump -PmapArgs="home"
 *   gradlew :game-server:mapDump -PmapArgs="region 12850"
 *   gradlew :game-server:mapDump -PmapArgs="box 48 49 51 52"
 *   gradlew :game-server:mapDump -PmapArgs="tile 3222 3218"
 *   gradlew :game-server:mapDump -PmapArgs="all"
 */

private const val CACHE_PATH = "data/cache"
private const val XTEA_PATH = "data/xteas.json"
private const val OUT_DIR = "data/mapdump"
private const val REVISION = 228

private const val BLOCKED_TILE = 0x1
private const val BRIDGE_TILE = 0x2

private const val TILE_PX = 4 // per-region render scale (64*4 = 256px)

// ARGB render palette
private const val C_WALK = 0xFFC8D2C0.toInt()
private const val C_BLOCK = 0xFF35506B.toInt() // water / cliff / void
private const val C_BRIDGE = 0xFFB58A4A.toInt()
private const val C_OBJECT = 0xFFC8732B.toInt()
private const val C_WALL = 0xFF202020.toInt()

/** Per-tile classification (one region, one level). */
private class Cell {
    var blocked = false // terrain BLOCKED_TILE
    var bridge = false // terrain BRIDGE_TILE
    var wall = false // a wall-type loc touches this tile
    var obj = false // a solid scenery loc occupies this tile
}

private class RegionDump(val id: Int, val rx: Int, val ry: Int) {
    val baseX = rx shl 6
    val baseZ = ry shl 6
    // [level][x][y]
    val cells = Array(4) { Array(64) { Array(64) { Cell() } } }
    val objects = ArrayList<ObjRow>()
    var hadKeys = false
}

private data class ObjRow(
    val worldX: Int, val worldZ: Int, val level: Int,
    val id: Int, val name: String, val type: Int, val orient: Int,
    val sizeX: Int, val sizeY: Int,
)

fun main(args: Array<String>) {
    val outDir = File(OUT_DIR).apply { mkdirs() }
    println("map-dump tool: cache=$CACHE_PATH rev=$REVISION out=$OUT_DIR")

    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)

    val xteaFile = File(XTEA_PATH)
    if (xteaFile.exists()) {
        XteaLoader.load(xteaFile)
        println("loaded xteas from $XTEA_PATH")
    } else {
        println("WARNING: $XTEA_PATH not found — objects/locs will be skipped, terrain only.")
    }

    if (args.getOrNull(0)?.lowercase() == "ring") {
        analyzeRings(outDir)
        return
    }

    if (args.getOrNull(0)?.lowercase() == "objinfo") {
        objInfo(args.drop(1).mapNotNull { it.toIntOrNull() })
        return
    }

    if (args.getOrNull(0)?.lowercase() == "npcinfo") {
        for (id in args.drop(1).mapNotNull { it.toIntOrNull() }) {
            val def = CacheManager.getNpcOrDefault(id)
            println(
                "npc id=$id  name='${def.name}'  combatLevel=${def.combatLevel}  size=${def.size}  " +
                    "actions=${def.actions.toList()}  varbit=${def.varbit}  varp=${def.varp}  transforms=${def.transforms?.toList()}",
            )
        }
        return
    }

    if (args.getOrNull(0)?.lowercase() == "flags") {
        val a = args.drop(1).mapNotNull { it.toIntOrNull() }
        if (a.size == 4) dumpFlags(a[0], a[1], a[2], a[3])
        else println("usage: flags <x0> <z0> <x1> <z1>   (world coords, one region)")
        return
    }

    val regions = selectRegions(args)
    if (regions.isEmpty()) {
        println("no regions selected. usage: home | region <id...> | box <rxMin ryMin rxMax ryMax> | tile <x z> | all")
        return
    }
    println("dumping ${regions.size} region(s)...")

    val dumps = ArrayList<RegionDump>()
    for (id in regions) {
        val dump = dumpRegion(id) ?: continue
        writeText(outDir, dump)
        writePng(outDir, dump)
        dumps += dump
    }

    if (dumps.isEmpty()) {
        println("no regions had map data in the cache.")
        return
    }
    writeStitched(outDir, dumps)
    writeIndex(outDir, dumps)
    println("done. ${dumps.size} region(s) -> $OUT_DIR")
}

// ---------------------------------------------------------------------------- object info

/**
 * `objinfo <id> [<id> ...]` — print each object def's render-relevant fields so we can tell a
 * STATIC object (real [ObjectType.objectModels], no varbit/varp) apart from a TRANSFORM object
 * (varbit/varp + transforms → invisible in its default state, e.g. farming patches).
 */
private fun objInfo(ids: List<Int>) {
    if (ids.isEmpty()) { println("objinfo: no ids given"); return }
    for (id in ids) {
        val def = CacheManager.getObjectOrDefault(id)
        val models = def.objectModels?.joinToString(",")?.ifBlank { "—" } ?: "—(none)"
        val acts = def.actions.filterNotNull().filter { it.isNotBlank() }
        val hasModels = def.objectModels?.isNotEmpty() == true
        val isTransform = def.varbit != -1 || def.varp != -1
        val verdict = when {
            isTransform -> "TRANSFORM (varbit/varp — likely INVISIBLE in default state)"
            hasModels -> "STATIC — always renders"
            else -> "NO MODELS — invisible"
        }
        println(
            "id=$id  name='${def.name}'  size=${def.sizeX}x${def.sizeY}  clipType=${def.clipType}  " +
                "models=[$models]  varbit=${def.varbit}  varp=${def.varp}  transforms=${def.transforms}  " +
                "actions=$acts\n   -> $verdict",
        )
    }
}

/**
 * `flags <x0> <z0> <x1> <z1>` — print the RAW terrain `settings` byte for every tile in the world
 * box, per plane. This is the byte the CLIENT'S plane-hiding runs on: bit 0x1 = blocked,
 * 0x2 = bridge (render this plane's content one plane down), 0x4 = UNDER-ROOF (stand here and the
 * client hides the roof-groups above you — the "floors don't superimpose" mechanism),
 * 0x8 = vis-below. Built to diagnose why an instanced map copy renders all floors superimposed:
 * if the source tiles lack 0x4, the client has nothing to hide with — in the instance OR anywhere.
 */
private fun dumpFlags(x0: Int, z0: Int, x1: Int, z1: Int) {
    val rx = x0 shr 6
    val ry = z0 shr 6
    val mapData = CacheManager.cache.data(MAPS, "m${rx}_$ry") ?: run { println("no map data for region ${(rx shl 8) or ry}"); return }
    val tiles = loadTerrain(mapData)
    for (plane in 0 until 4) {
        var any = false
        val sb = StringBuilder()
        sb.appendLine("## Plane $plane  (settings hex per tile; '.' = 0; north up)")
        for (z in z1 downTo z0) {
            sb.append(z.toString().padStart(6)).append(": ")
            for (x in x0..x1) {
                val s = tiles[plane][x and 63][z and 63].settings.toInt() and 0xFF
                if (s != 0) any = true
                sb.append(if (s == 0) '.' else s.toString(16).last())
            }
            sb.appendLine()
        }
        val counts = (0 until 4).associateWith { bit ->
            var n = 0
            for (z in z0..z1) for (x in x0..x1) {
                if ((tiles[plane][x and 63][z and 63].settings.toInt() and (1 shl bit)) != 0) n++
            }
            n
        }
        sb.appendLine("bit counts: blocked(1)=${counts[0]}  bridge(2)=${counts[1]}  under-roof(4)=${counts[2]}  vis-below(8)=${counts[3]}")
        println(sb.toString())
        if (!any) println("(plane $plane: all zero)\n")
    }
}

// ---------------------------------------------------------------------------- selection

private fun selectRegions(args: Array<String>): List<Int> {
    val mode = args.getOrNull(0)?.lowercase() ?: "home"
    fun rid(rx: Int, ry: Int) = (rx shl 8) or ry
    return when (mode) {
        "home" -> buildList {
            for (rx in 48..51) for (ry in 49..52) add(rid(rx, ry))
        }
        "region" -> args.drop(1).mapNotNull { it.toIntOrNull() }
        "box" -> {
            val (a, b, c, d) = listOf(1, 2, 3, 4).map { args.getOrNull(it)?.toIntOrNull() ?: return emptyList() }
            buildList { for (rx in a..c) for (ry in b..d) add(rid(rx, ry)) }
        }
        "tile" -> {
            val x = args.getOrNull(1)?.toIntOrNull() ?: return emptyList()
            val z = args.getOrNull(2)?.toIntOrNull() ?: return emptyList()
            listOf(rid(x shr 6, z shr 6))
        }
        "all" -> buildList {
            for (rx in 0..255) for (ry in 0..255) {
                if (CacheManager.cache.data(MAPS, "m${rx}_$ry") != null) add(rid(rx, ry))
            }
        }
        else -> emptyList()
    }
}

private operator fun <T> List<T>.component4(): T = this[3]

// ---------------------------------------------------------------------------- decode

private fun dumpRegion(id: Int): RegionDump? {
    val rx = id shr 8
    val ry = id and 0xFF
    val mapData = CacheManager.cache.data(MAPS, "m${rx}_$ry") ?: return null
    val dump = RegionDump(id, rx, ry)

    val tiles = loadTerrain(mapData)
    for (z in 0 until 4) for (x in 0 until 64) for (y in 0 until 64) {
        val s = tiles[z][x][y].settings.toInt()
        val cell = dump.cells[z][x][y]
        cell.blocked = (s and BLOCKED_TILE) != 0
        cell.bridge = (tiles[1][x][y].settings.toInt() and BRIDGE_TILE) != 0
    }

    // Map-decrypted caches store locs with zero keys, so fall back to EMPTY (like DefinitionSet).
    val keys = XteaLoader.getKeys(id) ?: IntArray(4)
    run {
        try {
            val landData = CacheManager.cache.data(MAPS, "l${rx}_$ry", keys)
            if (landData != null) {
                dump.hadKeys = true
                loadLocations(landData) { loc ->
                    val def = CacheManager.getObjectOrDefault(loc.id)
                    val name = def.name?.ifBlank { "null" } ?: "null"
                    // footprint rotates with orientation 1/3
                    val sx = if (loc.orientation and 1 == 1) def.sizeY else def.sizeX
                    val sy = if (loc.orientation and 1 == 1) def.sizeX else def.sizeY
                    dump.objects += ObjRow(
                        dump.baseX + loc.localX, dump.baseZ + loc.localY, loc.height,
                        loc.id, name, loc.type, loc.orientation, sx, sy,
                    )
                    val clips = def.clipType != 0 // clipType 0 == never clips
                    if (loc.height in 0..3 && clips) {
                        when (loc.type) {
                            in 0..3, 9 -> dump.cells[loc.height][loc.localX][loc.localY].wall = true
                            in 10..11 -> markFootprint(dump, loc.height, loc.localX, loc.localY, sx, sy)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("region $id: could not decrypt locs (${e.message})")
        }
    }
    return dump
}

private fun markFootprint(dump: RegionDump, level: Int, lx: Int, ly: Int, sx: Int, sy: Int) {
    for (dx in 0 until sx.coerceAtLeast(1)) for (dy in 0 until sy.coerceAtLeast(1)) {
        val x = lx + dx; val y = ly + dy
        if (x in 0..63 && y in 0..63) dump.cells[level][x][y].obj = true
    }
}

// ---------------------------------------------------------------------------- writers

/** One tile -> one char. Priority: terrain block > wall > object > bridge > walkable. */
private fun glyph(c: Cell): Char = when {
    c.blocked -> '#'
    c.wall -> 'W'
    c.obj -> 'O'
    c.bridge -> '='
    else -> '.'
}

private fun writeText(outDir: File, d: RegionDump) {
    val sb = StringBuilder()
    sb.appendLine("# Region ${d.id}  (regionX=${d.rx}, regionY=${d.ry})")
    sb.appendLine("# World bounds: SW (${d.baseX}, ${d.baseZ})  ->  NE (${d.baseX + 63}, ${d.baseZ + 63})")
    sb.appendLine("# XTEA keys: ${if (d.hadKeys) "present (objects decoded)" else "MISSING (terrain only)"}")
    sb.appendLine("# Legend:  . walkable   # blocked-terrain(water/cliff)   W wall   O solid-object   = bridge")
    sb.appendLine("# Grid is NORTH-UP. Left column = world X of that row's west edge; top row = highest Z (north).")
    sb.appendLine("# Column header = world X (tens digit shown every 10 tiles from baseX).")

    for (level in 0 until 4) {
        val anything = (0 until 64).any { x -> (0 until 64).any { y -> glyph(d.cells[level][x][y]) != '.' } }
        if (level > 0 && !anything) continue // skip empty upper levels
        sb.appendLine()
        sb.appendLine("## Level $level")
        // x ruler: mark a '|' every 10 world-X tiles, labelled below
        val ticks = StringBuilder("        ")
        val labels = StringBuilder("        ")
        var x = 0
        while (x < 64) {
            val wx = d.baseX + x
            if (wx % 10 == 0) {
                ticks.append('|')
                val lbl = wx.toString()
                labels.append(lbl)
                repeat((10 - lbl.length).coerceAtLeast(1)) { ticks.append(' ') }
                x += 1 + (10 - lbl.length).coerceAtLeast(1)
            } else {
                ticks.append(' '); x++
            }
        }
        sb.appendLine(ticks.toString())
        sb.appendLine(labels.toString())
        for (y in 63 downTo 0) {
            val row = StringBuilder()
            row.append((d.baseZ + y).toString().padStart(6)).append(": ")
            for (x in 0 until 64) row.append(glyph(d.cells[level][x][y]))
            sb.appendLine(row.toString())
        }
    }

    // object list
    sb.appendLine()
    sb.appendLine("## Objects (${d.objects.size})  [worldX  worldZ  level  id  name  type  orient  sizeX  sizeY]")
    d.objects.sortedWith(compareBy({ it.level }, { it.worldZ }, { it.worldX })).forEach { o ->
        sb.appendLine("${o.worldX}\t${o.worldZ}\t${o.level}\t${o.id}\t${o.name}\t${o.type}\t${o.orient}\t${o.sizeX}\t${o.sizeY}")
    }

    File(outDir, "r${d.id}_${d.rx}_${d.ry}.txt").writeText(sb.toString())
}

private fun colorOf(c: Cell): Int = when {
    c.blocked -> C_BLOCK
    c.wall -> C_WALL
    c.obj -> C_OBJECT
    c.bridge -> C_BRIDGE
    else -> C_WALK
}

private fun writePng(outDir: File, d: RegionDump) {
    val img = BufferedImage(64 * TILE_PX, 64 * TILE_PX, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until 64) for (y in 0 until 64) {
        val color = colorOf(d.cells[0][x][y])
        val px = x * TILE_PX
        val py = (63 - y) * TILE_PX // north up
        for (ix in 0 until TILE_PX) for (iy in 0 until TILE_PX) img.setRGB(px + ix, py + iy, color)
    }
    ImageIO.write(img, "png", File(outDir, "r${d.id}_${d.rx}_${d.ry}.png"))
}

private fun writeStitched(outDir: File, dumps: List<RegionDump>) {
    val minRx = dumps.minOf { it.rx }; val maxRx = dumps.maxOf { it.rx }
    val minRy = dumps.minOf { it.ry }; val maxRy = dumps.maxOf { it.ry }
    val w = (maxRx - minRx + 1) * 64
    val h = (maxRy - minRy + 1) * 64
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    // fill transparent-ish background
    for (px in 0 until w) for (py in 0 until h) img.setRGB(px, py, 0xFF101010.toInt())
    for (d in dumps) {
        val ox = (d.rx - minRx) * 64
        val oyRegion = (maxRy - d.ry) * 64 // north up: higher ry = higher on image
        for (x in 0 until 64) for (y in 0 until 64) {
            val color = colorOf(d.cells[0][x][y])
            img.setRGB(ox + x, oyRegion + (63 - y), color)
        }
    }
    ImageIO.write(img, "png", File(outDir, "stitched.png"))
    println("stitched overview: ${w}x$h px  (regionX $minRx..$maxRx, regionY $minRy..$maxRy)")
}

// ---------------------------------------------------------------------------- ring overlay

/**
 * Ring geometry mirrored from `org.alter.plugins.content.war.CityFrontiers` (game-plugins is
 * runtime-only to game-server, so we can't import it). Keep these numbers in sync with the
 * `FrontierConfig`s there. `ringStaging`/`capEvenly` below replicate that object's logic.
 */
private class RingCfg(
    val key: String, val name: String,
    val limX0: Int, val limZ0: Int, val limX1: Int, val limZ1: Int,
    val gap: Int, val depth: Int, val spacing: Int, val maxEnemies: Int,
)

private val RINGS = listOf(
    // CityFrontiers.LUMBRIDGE: cityLimits Area(3200,3190,3270,3245), gap 10, depth 50, spacing 10, max 200
    RingCfg("lumbridge", "Lumbridge", 3200, 3190, 3270, 3245, gap = 10, depth = 50, spacing = 10, maxEnemies = 200),
)

/** Mirrors CityFrontiers.ringStaging: 14-grid in the OUTER box but outside the INNER box. */
private fun ringStaging(c: RingCfg): List<Pair<Int, Int>> {
    val inX0 = c.limX0 - c.gap; val inZ0 = c.limZ0 - c.gap
    val inX1 = c.limX1 + c.gap; val inZ1 = c.limZ1 + c.gap
    val outX0 = c.limX0 - c.gap - c.depth; val outZ0 = c.limZ0 - c.gap - c.depth
    val outX1 = c.limX1 + c.gap + c.depth; val outZ1 = c.limZ1 + c.gap + c.depth
    val step = c.spacing.coerceAtLeast(1)
    val cells = ArrayList<Pair<Int, Int>>()
    var x = outX0
    while (x <= outX1) {
        var z = outZ0
        while (z <= outZ1) {
            val insideInner = x in inX0..inX1 && z in inZ0..inZ1 // Area.contains is edge-inclusive
            if (!insideInner) cells += x to z
            z += step
        }
        x += step
    }
    return cells
}

/** Mirrors CityFrontiers.capEvenly. */
private fun capEvenly(tiles: List<Pair<Int, Int>>, max: Int): List<Pair<Int, Int>> {
    if (tiles.size <= max) return tiles
    val stride = tiles.size.toDouble() / max
    return (0 until max).map { tiles[(it * stride).toInt()] }
}

/** Lazily-decoded region cache so we can test arbitrary world tiles for walkability. */
private val regionCache = HashMap<Int, RegionDump?>()

private fun cellAt(worldX: Int, worldZ: Int, level: Int): Cell? {
    val id = ((worldX shr 6) shl 8) or (worldZ shr 6)
    val dump = regionCache.getOrPut(id) { dumpRegion(id) } ?: return null
    return dump.cells[level][worldX and 63][worldZ and 63]
}

/** Spawnable == on the ground, not in water/cliff and not under a wall/solid object. */
private enum class Walk { WALKABLE, TERRAIN, OBJECT, NODATA }

private fun classify(x: Int, z: Int): Walk {
    val c = cellAt(x, z, 0) ?: return Walk.NODATA
    return when {
        c.blocked -> Walk.TERRAIN
        c.wall || c.obj -> Walk.OBJECT
        else -> Walk.WALKABLE
    }
}

/** Mirror of World.findRandomTileAround's filter: any fully-clear (walkable) tile in the radius box. */
private fun hasClearTileWithin(x: Int, z: Int, radius: Int): Boolean {
    for (dx in -radius..radius) for (dz in -radius..radius) {
        if (classify(x + dx, z + dz) == Walk.WALKABLE) return true
    }
    return false
}

private fun analyzeRings(outDir: File) {
    for (cfg in RINGS) {
        // Mirror CityFrontierPlugin EXACTLY: ringStaging -> StaticTerrain walkable filter -> capEvenly.
        val full = ringStaging(cfg)
        val walkable = full.filter { classify(it.first, it.second) != Walk.TERRAIN } // StaticTerrain is terrain-only
        val capped = capEvenly(walkable, cfg.maxEnemies)
        printStats(cfg, "FULL geometry (pre-filter)", full)
        printStats(cfg, "WALKABLE (terrain-filtered, pre-cap)", walkable)
        printStats(cfg, "FINAL shipped (after cap to ${cfg.maxEnemies})", capped)

        // Spawn reality: HostileZone does findRandomTileAround(point, radius=4) and only spawns ON
        // the muster tile if the WHOLE 9x9 is clipped. Count the FINAL points that would do that.
        val objPts = capped.filter { classify(it.first, it.second) == Walk.OBJECT }
        val trapped = capped.count { !hasClearTileWithin(it.first, it.second, 4) }
        val objTrapped = objPts.count { !hasClearTileWithin(it.first, it.second, 4) }
        println(
            "[${cfg.name}] SPAWN CHECK: of ${capped.size} final points, $trapped have NO clear tile within radius 4 " +
                "(would spawn on the bad tile). Of the ${objPts.size} under-wall/object points, $objTrapped are trapped.",
        )
        renderRingOverlay(outDir, cfg, full, "ring_${cfg.key}_before.png")
        renderRingOverlay(outDir, cfg, capped, "ring_${cfg.key}_after.png")
    }
}

private fun printStats(cfg: RingCfg, label: String, pts: List<Pair<Int, Int>>) {
    val g = pts.groupingBy { classify(it.first, it.second) }.eachCount()
    val w = g[Walk.WALKABLE] ?: 0
    val pct = if (pts.isEmpty()) 0.0 else 100.0 * w / pts.size
    println(
        "[${cfg.name}] $label: ${pts.size} points -> " +
            "walkable=$w (${"%.1f".format(pct)}%), " +
            "in-water/cliff=${g[Walk.TERRAIN] ?: 0}, " +
            "under-wall/object=${g[Walk.OBJECT] ?: 0}, " +
            "no-map-data=${g[Walk.NODATA] ?: 0}",
    )
}

private fun renderRingOverlay(outDir: File, cfg: RingCfg, pts: List<Pair<Int, Int>>, fileName: String) {
    if (pts.isEmpty()) return
    val pad = 6
    val minX = pts.minOf { it.first } - pad; val maxX = pts.maxOf { it.first } + pad
    val minZ = pts.minOf { it.second } - pad; val maxZ = pts.maxOf { it.second } + pad
    val wTiles = maxX - minX + 1; val hTiles = maxZ - minZ + 1
    val px = 3
    val img = BufferedImage(wTiles * px, hTiles * px, BufferedImage.TYPE_INT_ARGB)

    // background: the collision map, dimmed
    for (tx in 0 until wTiles) for (tz in 0 until hTiles) {
        val c = cellAt(minX + tx, minZ + tz, 0)
        val base = if (c == null) 0xFF101010.toInt() else colorOf(c)
        val ix = tx * px; val iy = (hTiles - 1 - tz) * px
        for (a in 0 until px) for (b in 0 until px) img.setRGB(ix + a, iy + b, base)
    }
    // staging points: green = walkable land, red = water/cliff, yellow = under wall/object
    for ((x, z) in pts) {
        val color = when (classify(x, z)) {
            Walk.WALKABLE -> 0xFF2ECC40.toInt()
            Walk.TERRAIN -> 0xFFFF4136.toInt()
            Walk.OBJECT -> 0xFFFFDC00.toInt()
            Walk.NODATA -> 0xFFAAAAAA.toInt()
        }
        val ix = (x - minX) * px; val iy = (hTiles - 1 - (z - minZ)) * px
        for (a in 0 until px) for (b in 0 until px) img.setRGB(ix + a, iy + b, color)
    }
    ImageIO.write(img, "png", File(outDir, fileName))
    println("[${cfg.name}] overlay -> $OUT_DIR/$fileName  (${wTiles}x$hTiles tiles, world X $minX..$maxX, Z $minZ..$maxZ)")
}

private fun writeIndex(outDir: File, dumps: List<RegionDump>) {
    val sb = StringBuilder()
    sb.appendLine("# Map dump index")
    sb.appendLine()
    sb.appendLine("Generated from `$CACHE_PATH` (rev $REVISION). `stitched.png` is the whole-area overview (north up).")
    sb.appendLine()
    sb.appendLine("| region id | regionX | regionY | world SW | world NE | objects | keys |")
    sb.appendLine("|-----------|---------|---------|----------|----------|---------|------|")
    dumps.sortedWith(compareBy({ it.ry }, { it.rx })).forEach { d ->
        sb.appendLine(
            "| ${d.id} | ${d.rx} | ${d.ry} | (${d.baseX}, ${d.baseZ}) | (${d.baseX + 63}, ${d.baseZ + 63}) " +
                "| ${d.objects.size} | ${if (d.hadKeys) "yes" else "—"} |",
        )
    }
    File(outDir, "INDEX.md").writeText(sb.toString())
}
