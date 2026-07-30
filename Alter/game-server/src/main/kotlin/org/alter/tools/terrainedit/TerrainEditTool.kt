package org.alter.tools.terrainedit

import com.displee.cache.CacheLibrary
import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.util.XteaLoader
import java.io.File
import java.nio.file.Path

/**
 * **Terrain tile fixer** — the inverse-of-[LocEditorTool] gap for the *terrain* (`m<rx>_<ry>`)
 * archive: patch a single map tile by copying an adjacent tile's terrain over it, so a **void /
 * black tile** (an underlay-0 tile that used to be hidden under scenery, then exposed when we
 * opened the castle floor) renders as normal floor again.
 *
 * ### Why byte-splice instead of a full re-encoder
 * The map archive is a flat stream of per-tile attribute opcodes in a fixed order
 * (`plane 0..3 → x 0..63 → y 0..63`). Rather than decode → mutate → re-encode the whole region
 * (which would need a byte-exact terrain *encoder* we don't have), we **parse the stream into
 * per-tile byte ranges** and splice: replace the target tile's bytes with a verbatim copy of a
 * source tile's bytes. Every other tile is byte-for-byte untouched by construction, so there is
 * no encoder to get subtly wrong.
 *
 * ### The per-tile opcode format (standard OSRS map, matches dev.openrune loadTerrain)
 *   loop: op = u8()
 *     op == 0            → end of tile
 *     op == 1            → height = u8(); end of tile
 *     op in 2..49        → overlay: consumes one more id byte (shape=(op-2)/4, rot=(op-2)&3)
 *     op in 50..81       → tile settings/flags (no extra byte)
 *     op >= 82           → underlay id = op-81 (no extra byte)
 *
 * ### Modes  (workingDir = repo root; cache dir optional last arg, mirrors LocEditorTool)
 *   gradlew :game-server:terrainEdit -PterrainArgs="verify 12850"
 *       parse the region's terrain into 16384 tile ranges and assert full, exact coverage
 *       (proves the parser round-trips the real bytes). NO write.
 *   gradlew :game-server:terrainEdit -PterrainArgs="apply 12850 3209 3216 3210 3216"
 *       copy the terrain of source (3210,3216) onto target (3209,3216): backup -> splice ->
 *       read-back-verify. Optional 7th arg = plane (default 0).
 *   gradlew :game-server:terrainEdit -PterrainArgs="restore 12850"
 *       roll the region's terrain back from its backup.
 *
 * Restart the game after apply so it reloads the cache; clients pull the changed map on reconnect.
 */

private var cachePath = "data/cache"
private var xteaPath = "data/xteas.json"
private const val REVISION = 228
private const val PLANES = 4
private const val WIDTH = 64
private const val TILES = PLANES * WIDTH * WIDTH

private fun initCache() {
    CacheManager.init(Cache.load(Path.of(cachePath), false), REVISION)
    val x = File(xteaPath); if (x.exists()) XteaLoader.load(x)
}

/** Terrain (m-archive) is not xtea-encrypted — read/write with no keys (cf. MapDumpTool). */
private fun readTerrain(region: Int): ByteArray? {
    val rx = region shr 8; val ry = region and 0xFF
    return try {
        CacheManager.cache.data(MAPS, "m${rx}_$ry")
    } catch (e: Exception) {
        println("region $region: terrain read failed (${e.message})"); null
    }
}

private fun idx(plane: Int, lx: Int, ly: Int) = ((plane * WIDTH) + lx) * WIDTH + ly

/**
 * Walk the terrain stream and return the start offset of every tile, in decode order, plus a final
 * entry = total length (so tile i occupies `[starts[i], starts[i+1])`). Throws if the stream does
 * not parse to exactly [TILES] tiles ending at the last byte — that assertion is the parser proof.
 */
private fun tileStarts(data: ByteArray): IntArray {
    val starts = IntArray(TILES + 1)
    var p = 0
    fun u8(): Int {
        require(p < data.size) { "terrain stream underran at byte $p of ${data.size}" }
        return data[p++].toInt() and 0xFF
    }
    var i = 0
    for (plane in 0 until PLANES) for (lx in 0 until WIDTH) for (ly in 0 until WIDTH) {
        starts[i++] = p
        while (true) {
            val op = u8()
            when {
                op == 0 -> break
                op == 1 -> { u8(); break }          // height byte
                op <= 49 -> u8()                    // overlay id byte
                // 50..81 settings, >=82 underlay: opcode-only
            }
        }
    }
    starts[TILES] = p
    require(p == data.size) { "terrain parse mismatch: consumed $p of ${data.size} bytes over $i tiles" }
    return starts
}

// ---------------------------------------------------------------------------- cache write / backup

private fun backupDir() = File("data/mapedit/backup").apply { mkdirs() }

private fun backup(region: Int, data: ByteArray) {
    val rx = region shr 8; val ry = region and 0xFF
    val f = File(backupDir(), "m${rx}_$ry.terrain.bak")
    if (f.exists()) { println("terrain backup already exists (left as-is: ${f.path})"); return }
    f.writeBytes(data); println("backed up region $region terrain -> ${f.path}")
}

private fun writeTerrain(region: Int, bytes: ByteArray) {
    val rx = region shr 8; val ry = region and 0xFF
    val lib = CacheLibrary(cachePath)
    try { lib.put(MAPS, "m${rx}_$ry", bytes); lib.update() } finally { lib.close() }
}

// ---------------------------------------------------------------------------- modes

fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "help"
    // trailing optional [cacheDir] [xteas], consistent with LocEditorTool
    // args layout: verify/restore <region> [cacheDir] [xteas]  |
    //              apply <region> <tx> <tz> <sx> <sz> <plane> [cacheDir] [xteas]
    when (mode) {
        "verify", "restore" -> { args.getOrNull(2)?.let { cachePath = it }; args.getOrNull(3)?.let { xteaPath = it } }
        "apply" -> { args.getOrNull(7)?.let { cachePath = it }; args.getOrNull(8)?.let { xteaPath = it } }
    }
    println("terrain-editor tool: mode=$mode cache=$cachePath rev=$REVISION")
    initCache()
    when (mode) {
        "verify" -> verify(args.getOrNull(1)?.toIntOrNull())
        "apply" -> apply(args)
        "restore" -> restore(args.getOrNull(1)?.toIntOrNull())
        else -> println(
            "usage:\n" +
                "  verify <regionId>                         parse the region terrain (no write)\n" +
                "  apply  <regionId> <tx> <tz> <sx> <sz> [p] copy terrain from source (sx,sz) onto target (tx,tz)\n" +
                "  restore <regionId>                        roll the region terrain back from backup",
        )
    }
}

private fun verify(region: Int?) {
    if (region == null) { println("verify: give a region id, e.g. verify 12850"); return }
    val data = readTerrain(region) ?: run { println("region $region: no terrain in cache"); return }
    val starts = tileStarts(data)
    val nonEmpty = (0 until TILES).count { starts[it + 1] > starts[it] }
    println("region $region: terrain PARSED OK — ${data.size} bytes, $TILES tiles ($nonEmpty non-empty), consumed to end.")
    println("Parser round-trips the real stream; apply is safe to run.")
}

private fun apply(args: Array<String>) {
    val region = args.getOrNull(1)?.toIntOrNull()
    val tx = args.getOrNull(2)?.toIntOrNull(); val tz = args.getOrNull(3)?.toIntOrNull()
    val sx = args.getOrNull(4)?.toIntOrNull(); val sz = args.getOrNull(5)?.toIntOrNull()
    val plane = args.getOrNull(6)?.toIntOrNull() ?: 0
    if (region == null || tx == null || tz == null || sx == null || sz == null) {
        println("apply: usage  apply <regionId> <tx> <tz> <sx> <sz> [plane]"); return
    }
    val data = readTerrain(region) ?: run { println("region $region: no terrain in cache — aborting"); return }
    val baseX = (region shr 8) shl 6; val baseZ = (region and 0xFF) shl 6
    val tlx = tx - baseX; val tly = tz - baseZ; val slx = sx - baseX; val sly = sz - baseZ
    for ((label, lx, ly) in listOf(Triple("target", tlx, tly), Triple("source", slx, sly))) {
        require(lx in 0 until WIDTH && ly in 0 until WIDTH) {
            "$label ($tx,$tz)/($sx,$sz) is outside region $region [${baseX}..${baseX + 63}, ${baseZ}..${baseZ + 63}]"
        }
    }
    require(plane in 0 until PLANES) { "plane $plane out of range 0..3" }

    val starts = tileStarts(data)
    val t = idx(plane, tlx, tly); val s = idx(plane, slx, sly)
    val srcBytes = data.copyOfRange(starts[s], starts[s + 1])
    val out = data.copyOfRange(0, starts[t]) + srcBytes + data.copyOfRange(starts[t + 1], data.size)

    // Read-back verify BEFORE writing: the spliced stream must still parse to TILES tiles, and the
    // target tile's bytes must now equal the source tile's bytes; every other tile is byte-identical
    // by construction (only [starts[t], starts[t+1]) changed).
    val rs = tileStarts(out)
    val newTarget = out.copyOfRange(rs[t], rs[t + 1])
    require(newTarget.contentEquals(srcBytes)) { "post-splice target tile does not match source — refusing to write" }
    for (k in 0 until TILES) if (k != t) {
        val a = data.copyOfRange(starts[k], starts[k + 1]); val b = out.copyOfRange(rs[k], rs[k + 1])
        require(a.contentEquals(b)) { "post-splice tile $k changed unexpectedly — refusing to write" }
    }
    println("region $region: tile ($tx,$tz,p$plane) [${starts[t + 1] - starts[t]}B] <- source ($sx,$sz) [${srcBytes.size}B]; splice verified.")

    backup(region, data)
    writeTerrain(region, out)

    CacheManager.init(Cache.load(Path.of(cachePath), false), REVISION) // reopen and read back fresh
    val after = readTerrain(region)
    if (after != null && after.contentEquals(out)) {
        println("=== VERIFIED: region $region terrain written (${after.size} bytes). ===")
        println("Restart the game so it reloads the cache; clients pull the change on reconnect.")
    } else {
        println("VERIFY FAILED: read-back does not match. Run:  terrainEdit -PterrainArgs=\"restore $region\"")
    }
}

private fun restore(region: Int?) {
    if (region == null) { println("restore: give a region id"); return }
    val rx = region shr 8; val ry = region and 0xFF
    val f = File(backupDir(), "m${rx}_$ry.terrain.bak")
    if (!f.exists()) { println("no terrain backup for region $region at ${f.path}"); return }
    writeTerrain(region, f.readBytes())
    println("restored region $region terrain from backup. Restart the game to reload the cache.")
}
