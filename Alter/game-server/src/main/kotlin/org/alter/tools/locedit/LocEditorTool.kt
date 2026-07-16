package org.alter.tools.locedit

import com.displee.cache.CacheLibrary
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.LocationData
import dev.openrune.cache.filestore.buffer.BufferWriter
import dev.openrune.cache.filestore.buffer.Writer
import dev.openrune.cache.filestore.loadLocations
import dev.openrune.cache.util.XteaLoader
import java.io.File
import java.nio.file.Path
import kotlin.random.Random

/**
 * **Loc editor** — surgically rewrite the *scenery objects* (locs) of a single map region in the
 * cache, so we can turn a place into a war-fallen version of itself (remove intact buildings /
 * walls, drop in rubble + fire) *in-game*, not just in a Blender render.
 *
 * This is the one tool the "fallen city" (path B) needed that didn't exist yet: the **inverse of
 * [loadLocations]** — a loc-list → bytes encoder — plus a small edit-list applier and the
 * backup / write / read-back-verify discipline the other cache tools use ([MinimapIconsTool]).
 * Everything else (terrain/loc decode, object defs, cache write via displee, xteas) was already here.
 *
 * ### The loc archive format (mirrors [loadLocations] exactly)
 * A region's `l<rx>_<ry>` archive is: id-delta (largeSmart) → { position-delta+1 (smart) →
 * attribute byte `type<<2 | orientation` }* → smart 0 (end this id) ... → largeSmart 0 (end).
 * Position packs `height<<12 | localX<<6 | localY` (14 bits). We decode the whole region, mutate
 * the loc list, and re-encode — sorted by (id, packedPosition) so every delta is ≥ 0, which the
 * reader requires. Terrain (`m<rx>_<ry>`) is left byte-for-byte untouched (scorched ground would
 * be a *terrain-overlay* edit — a separate, later tool).
 *
 * ### Modes (workingDir = repo root = the Alter project dir)
 *   gradlew :game-server:locEdit -PlocArgs="verify 12598"          # encoder round-trip on a real region; NO write
 *   gradlew :game-server:locEdit -PlocArgs="preview data/mapedit/ge_plaza.json"   # show the diff; NO write
 *   gradlew :game-server:locEdit -PlocArgs="apply   data/mapedit/ge_plaza.json"   # backup -> write -> read-back-verify
 *   gradlew :game-server:locEdit -PlocArgs="restore 12598"         # roll a region back from its backup
 *
 * `verify` and `preview` never touch the cache — run them first. `apply` backs the region's two
 * archives up, writes, then re-reads through the *server's* decode path and asserts the resulting
 * loc set equals what we intended; on any mismatch it tells you to `restore`. Restart the server
 * afterwards so it reloads the cache; clients pull the changed map on reconnect.
 */

// Overridable so the same tool runs locally (defaults) and inside the prod game image against the
// mounted live cache (e.g. `apply /app/data/mapedit/ge_plaza.json /cache`). See .github/workflows/loc-cache.yml.
private var cachePath = "data/cache"
private var xteaPath = "data/xteas.json"
private const val REVISION = 228

// ---------------------------------------------------------------------------- edit-list schema

/** One region's destruction pass, authored in world coordinates (see data/mapedit/ge_plaza.json). */
private data class EditSpec(
    val region: Int,
    val note: String? = null,
    val removes: List<RemoveRule> = emptyList(),
    val adds: List<AddRule> = emptyList(),
    val scatters: List<ScatterRule> = emptyList(),
)

/** Delete every loc whose world tile is inside [box] and (if given) matches level/types/ids. */
private data class RemoveRule(
    val box: List<Int>,            // [x0, z0, x1, z1] world tiles, inclusive (any corner order)
    val level: Int? = null,        // null = all levels
    val types: List<Int>? = null,  // null = any loc type
    val ids: List<Int>? = null,    // null = any object id
)

/** Place one loc at a world tile. Defaults suit ground scenery (type 10). */
private data class AddRule(
    val id: Int,
    val x: Int,
    val z: Int,
    val type: Int = 10,
    val orient: Int = 0,
    val level: Int = 0,
)

/**
 * Procedurally scatter [count] locs (id picked uniformly from [ids], random orientation) across
 * [box], one per tile, deterministic by [seed] so a re-run reproduces the exact same field (and
 * `verify`/`apply` round-trips). Turns "flatten the GE and rubble-ify it" into three lines instead
 * of 150 hand-placed adds.
 */
private data class ScatterRule(
    val box: List<Int>,     // [x0, z0, x1, z1] world tiles, inclusive
    val ids: List<Int>,     // object ids to draw from
    val count: Int,
    val type: Int = 10,
    val level: Int = 0,
    val seed: Int = 1,
)

// ---------------------------------------------------------------------------- loc encoder (inverse of loadLocations)

/** largeSmart write: inverse of [dev.openrune.cache.filestore.buffer.Reader.readLargeSmart]. */
private fun Writer.writeLargeSmart(value: Int) {
    var v = value
    while (v >= 32767) { writeSmart(32767); v -= 32767 }
    writeSmart(v)
}

private fun pack(loc: LocationData): Int = (loc.height shl 12) or (loc.localX shl 6) or loc.localY

/**
 * Encode a loc list back into the `l<rx>_<ry>` byte format. Sorts by (id, packedPosition) so the
 * delta-encoding the reader relies on is monotonic. Round-trip-proven by [verify].
 */
private fun encodeLocations(locs: List<LocationData>): ByteArray {
    val sorted = locs.sortedWith(compareBy({ it.id }, { pack(it) }, { (it.type shl 2) or it.orientation }))
    val w = BufferWriter(64 + sorted.size * 8)
    var lastId = -1
    var i = 0
    while (i < sorted.size) {
        val id = sorted[i].id
        w.writeLargeSmart(id - lastId)   // id delta (first = id+1, since reader starts at -1)
        lastId = id
        var lastPos = 0
        while (i < sorted.size && sorted[i].id == id) {
            val loc = sorted[i]
            val pos = pack(loc)
            w.writeSmart(pos - lastPos + 1) // position delta (+1; 0 terminates the block)
            lastPos = pos
            w.writeByte((loc.type shl 2) or (loc.orientation and 3))
            i++
        }
        w.writeSmart(0) // end of this id's positions
    }
    w.writeLargeSmart(0) // end of all ids
    return w.toArray()
}

// ---------------------------------------------------------------------------- cache helpers

private fun initCache() {
    CacheManager.init(Cache.load(Path.of(cachePath), false), REVISION)
    val xteaFile = File(xteaPath)
    if (xteaFile.exists()) XteaLoader.load(xteaFile)
}

/** Map-decrypted caches store locs under zero keys — mirror MapDumpTool/DefinitionSet exactly. */
private fun keysFor(region: Int): IntArray = XteaLoader.getKeys(region) ?: IntArray(4)

private fun readLocs(region: Int): MutableList<LocationData>? {
    val rx = region shr 8; val ry = region and 0xFF
    val land = try {
        CacheManager.cache.data(MAPS, "l${rx}_$ry", keysFor(region))
    } catch (e: Exception) {
        println("region $region: loc decode failed (${e.message})"); return null
    } ?: return null
    val out = ArrayList<LocationData>()
    loadLocations(land) { out += it }
    return out
}

private fun name(id: Int): String = (CacheManager.getObjectOrDefault(id).name ?: "null").ifBlank { "null" }

private fun canon(l: List<LocationData>): List<LocationData> =
    l.sortedWith(compareBy({ it.id }, { pack(it) }, { (it.type shl 2) or it.orientation }))

// ---------------------------------------------------------------------------- edit application (in memory)

private class ApplyResult(val edited: List<LocationData>, val removed: Int, val added: Int)

private fun applyEdits(spec: EditSpec, original: List<LocationData>): ApplyResult {
    val rx = spec.region shr 8; val ry = spec.region and 0xFF
    val baseX = rx shl 6; val baseZ = ry shl 6

    fun worldX(l: LocationData) = baseX + l.localX
    fun worldZ(l: LocationData) = baseZ + l.localY

    fun matches(rule: RemoveRule, l: LocationData): Boolean {
        val x0 = rule.box[0]; val z0 = rule.box[1]; val x1 = rule.box[2]; val z1 = rule.box[3]
        val wx = worldX(l); val wz = worldZ(l)
        if (wx < minOf(x0, x1) || wx > maxOf(x0, x1)) return false
        if (wz < minOf(z0, z1) || wz > maxOf(z0, z1)) return false
        if (rule.level != null && l.height != rule.level) return false
        if (rule.types != null && l.type !in rule.types) return false
        if (rule.ids != null && l.id !in rule.ids) return false
        return true
    }

    val kept = original.filterNot { l -> spec.removes.any { matches(it, l) } }
    val removed = original.size - kept.size

    val added = ArrayList<LocationData>()
    val used = HashSet<Int>() // packed local tile (lx*64+ly) — keep scatter/adds one-per-tile
    for (a in spec.adds) {
        val lx = a.x - baseX; val ly = a.z - baseZ
        require(lx in 0..63 && ly in 0..63) {
            "add id=${a.id} at world (${a.x},${a.z}) is outside region ${spec.region} [${baseX}..${baseX + 63}, ${baseZ}..${baseZ + 63}]"
        }
        added += LocationData(a.id, a.type, a.orient, lx, ly, a.level)
        used += lx * 64 + ly
    }

    for (s in spec.scatters) {
        require(s.ids.isNotEmpty()) { "scatter rule with no ids" }
        val lx0 = (minOf(s.box[0], s.box[2]) - baseX).coerceIn(0, 63)
        val lx1 = (maxOf(s.box[0], s.box[2]) - baseX).coerceIn(0, 63)
        val ly0 = (minOf(s.box[1], s.box[3]) - baseZ).coerceIn(0, 63)
        val ly1 = (maxOf(s.box[1], s.box[3]) - baseZ).coerceIn(0, 63)
        require(lx0 <= lx1 && ly0 <= ly1) { "scatter box does not intersect region ${spec.region}" }
        val rng = Random(s.seed)
        var placed = 0; var tries = 0
        val cap = s.count * 40
        while (placed < s.count && tries < cap) {
            tries++
            val lx = rng.nextInt(lx0, lx1 + 1)
            val ly = rng.nextInt(ly0, ly1 + 1)
            if (!used.add(lx * 64 + ly)) continue
            added += LocationData(s.ids[rng.nextInt(s.ids.size)], s.type, rng.nextInt(4), lx, ly, s.level)
            placed++
        }
        if (placed < s.count) println("  scatter note: only placed $placed/${s.count} (box has ${(lx1 - lx0 + 1) * (ly1 - ly0 + 1)} tiles)")
    }
    return ApplyResult(kept + added, removed, added.size)
}

// ---------------------------------------------------------------------------- modes

fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "help"
    // args[2] = cache dir, args[3] = xteas path (both optional; positions are consistent across modes:
    //   verify/restore <region> [cacheDir] [xteas]   |   preview/apply <editfile> [cacheDir] [xteas])
    args.getOrNull(2)?.let { cachePath = it }
    args.getOrNull(3)?.let { xteaPath = it }
    println("loc-editor tool: mode=$mode cache=$cachePath rev=$REVISION")
    initCache()
    when (mode) {
        "verify" -> verify(args.getOrNull(1)?.toIntOrNull())
        "preview" -> preview(args.getOrNull(1))
        "apply" -> apply(args.getOrNull(1))
        "restore" -> restore(args.getOrNull(1)?.toIntOrNull())
        else -> println(
            "usage:\n" +
                "  verify <regionId>            encoder round-trip on a real region (no write)\n" +
                "  preview <editfile.json>      show the diff a spec would make (no write)\n" +
                "  apply   <editfile.json>      backup -> write -> read-back-verify\n" +
                "  restore <regionId>           roll a region back from its backup",
        )
    }
}

/** Decode a region's locs, re-encode, re-decode, and assert the set is identical. Proves the encoder. */
private fun verify(region: Int?) {
    if (region == null) { println("verify: give a region id, e.g. verify 12598"); return }
    val locs = readLocs(region) ?: run { println("region $region: no loc data in cache"); return }
    val bytes = encodeLocations(locs)
    val roundTrip = ArrayList<LocationData>().also { rt -> loadLocations(bytes) { rt += it } }
    val a = canon(locs); val b = canon(roundTrip)
    val ok = a == b
    println("region $region: decoded ${locs.size} locs -> re-encoded ${bytes.size} bytes -> re-decoded ${roundTrip.size} locs")
    if (ok) {
        println("ROUND-TRIP OK — the encoder reproduces every loc (id/type/orient/x/y/height) exactly.")
    } else {
        val diff = (a.toSet() - b.toSet()).take(10)
        println("ROUND-TRIP MISMATCH — first differences: $diff")
    }
}

private fun loadSpec(path: String?): EditSpec? {
    if (path == null) { println("give an edit-list json path, e.g. data/mapedit/ge_plaza.json"); return null }
    val f = File(path)
    if (!f.exists()) { println("edit-list not found: ${f.absolutePath}"); return null }
    val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    return mapper.readValue(f.readText())
}

private fun preview(path: String?) {
    val spec = loadSpec(path) ?: return
    val original = readLocs(spec.region) ?: run { println("region ${spec.region}: no loc data in cache"); return }
    val res = applyEdits(spec, original)
    println("=== preview: region ${spec.region}${spec.note?.let { "  ($it)" } ?: ""} ===")
    println("original locs: ${original.size}  ->  edited: ${res.edited.size}   (removed ${res.removed}, added ${res.added})")

    val removedSample = original.filterNot { o -> res.edited.contains(o) }
        .groupingBy { name(it.id) }.eachCount().entries.sortedByDescending { it.value }.take(12)
    println("--- removed (by object name) ---")
    removedSample.forEach { println("   ${it.value}x  ${it.key}") }
    println("--- added ---")
    spec.adds.forEach { println("   ${it.id}\t${name(it.id)}\t@ (${it.x},${it.z}) type=${it.type} orient=${it.orient} level=${it.level}") }
    spec.scatters.forEach { s ->
        val names = s.ids.joinToString(", ") { "${name(it)}($it)" }
        println("   scatter ${s.count}x [${names}] across (${s.box[0]},${s.box[1]})-(${s.box[2]},${s.box[3]}) seed=${s.seed}")
    }

    val roundTrip = ArrayList<LocationData>().also { rt -> loadLocations(encodeLocations(res.edited)) { rt += it } }
    println(if (canon(roundTrip) == canon(res.edited)) "encode round-trip: OK (safe to apply)" else "encode round-trip: FAILED — do NOT apply")
}

private fun apply(path: String?) {
    val spec = loadSpec(path) ?: return
    val region = spec.region
    val rx = region shr 8; val ry = region and 0xFF
    val original = readLocs(region) ?: run { println("region $region: no loc data in cache — aborting"); return }
    val res = applyEdits(spec, original)

    // Local round-trip gate BEFORE any write.
    val expected = canon(res.edited)
    val preBytes = encodeLocations(res.edited)
    val preDecode = ArrayList<LocationData>().also { rt -> loadLocations(preBytes) { rt += it } }
    if (canon(preDecode) != expected) { println("ABORT: local encode round-trip failed; refusing to write."); return }
    println("region $region: ${original.size} -> ${res.edited.size} locs (removed ${res.removed}, added ${res.added}); local round-trip OK.")

    backup(region)
    val keys = keysFor(region)
    val lib = CacheLibrary(cachePath)
    try {
        lib.put(MAPS, "l${rx}_$ry", preBytes, keys)
        lib.update()
    } finally {
        lib.close()
    }

    // Read back through the SAME path the server uses and assert the loc set matches.
    CacheManager.init(Cache.load(Path.of(cachePath), false), REVISION) // reopen so we read fresh bytes
    val after = readLocs(region)
    if (after != null && canon(after) == expected) {
        println("=== VERIFIED: region $region written and re-decoded to the intended ${after.size} locs. ===")
        println("Restart the server so it reloads the cache; clients pull the change on reconnect.")
    } else {
        println("VERIFY FAILED: read-back does not match intended set. Run:  locEdit -PlocArgs=\"restore $region\"")
    }
}

// region-scoped backup of just the two map archives, stored as raw bytes on disk.
private fun backupDir() = File("data/mapedit/backup").apply { mkdirs() }

private fun backup(region: Int) {
    val rx = region shr 8; val ry = region and 0xFF
    val dir = backupDir()
    val lFile = File(dir, "l${rx}_$ry.bak"); val mFile = File(dir, "m${rx}_$ry.bak")
    if (lFile.exists()) { println("backup for region $region already exists (left as-is: ${lFile.path})"); return }
    val land = CacheManager.cache.data(MAPS, "l${rx}_$ry", keysFor(region))
    val terrain = CacheManager.cache.data(MAPS, "m${rx}_$ry")
    if (land != null) lFile.writeBytes(land)
    if (terrain != null) mFile.writeBytes(terrain)
    println("backed up region $region locs+terrain -> ${dir.path}")
}

private fun restore(region: Int?) {
    if (region == null) { println("restore: give a region id"); return }
    val rx = region shr 8; val ry = region and 0xFF
    val dir = backupDir()
    val lFile = File(dir, "l${rx}_$ry.bak")
    if (!lFile.exists()) { println("no backup for region $region at ${lFile.path}"); return }
    val keys = keysFor(region)
    val lib = CacheLibrary(cachePath)
    try {
        lib.put(MAPS, "l${rx}_$ry", lFile.readBytes(), keys)
        lib.update()
    } finally {
        lib.close()
    }
    println("restored region $region locs from backup. Restart the server to reload the cache.")
}
