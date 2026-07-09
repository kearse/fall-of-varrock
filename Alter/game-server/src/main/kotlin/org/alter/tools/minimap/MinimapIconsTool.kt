package org.alter.tools.minimap

import com.displee.cache.CacheLibrary
import dev.openrune.cache.AREA
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.loadLocations
import dev.openrune.cache.filestore.definition.decoder.SpriteDecoder
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Declutter the in-game **minimap** by hiding map-element ("AreaType") icons.
 *
 * The little symbol icons on the minimap (shops, anvil, furnace, altar, range, bank, …)
 * are NOT npc dots and NOT object `mapSceneID` backdrops — they are **AreaType** map
 * elements (CONFIGS/AREA, see [dev.openrune.cache.filestore.definition.decoder.AreaDecoder]).
 * Each area packs its render flags into **opcode 7** as a bitfield:
 *   bit 1 (0x1) set  -> renders on the WORLD MAP
 *   bit 2 (0x2) set  -> renders on the MINIMAP
 * An icon only appears on the minimap when opcode 7 is present with bit 2 set.
 *
 * We **surgically clear bit 2 of the single opcode-7 byte in place** — no length change, every
 * other byte untouched. We walk the opcode stream (mirroring AreaDecoder's operand sizes, and
 * treating opcodes the codec doesn't model as zero-operand no-ops, exactly as AreaDecoder's
 * read-loop does) only to locate that one byte.
 *
 * Validation: the vendored AreaDecoder actually throws BufferUnderflow on this cache's area
 * archive (so the server never loads areas through it — it's not a usable oracle). Instead we
 * gate every edit on the walker reaching the terminating opcode-0 AND consuming the record
 * **exactly to its end** (exact-fit). A wrong operand width would shift the terminator, so an
 * exact fit is strong per-record proof the framing — and thus the opcode-7 offset — is correct.
 * After writing we re-parse and require the minimap bit is now clear and the record still fits.
 *
 * Reads use OpenRune's [Cache] (read-only, proven addressing). Writes use the displee
 * [CacheLibrary] `archive.add(id, bytes)` (same path as the repo's PackConfig), storing our
 * edited *raw* bytes — no lossy re-encode. The cache is shared and streamed to clients over
 * JS5, so editing it here is enough; restart the server afterwards so it reloads the cache.
 *
 * Run (workingDir = repo root = the Alter project dir):
 *   gradlew :game-server:minimapIcons -PminimapArgs="scan"
 *   gradlew :game-server:minimapIcons -PminimapArgs="apply all"
 *   gradlew :game-server:minimapIcons -PminimapArgs="apply 12 34 56"
 *   gradlew :game-server:minimapIcons -PminimapArgs="restore"
 */

private const val MINIMAP_BIT = 0x2
private const val CACHE_PATH = "data/cache"

private class Area(
    val id: Int,
    val name: String,
    val sprite1: Int,
    val sprite2: Int,
    val worldMap: Boolean,
    val miniMap: Boolean,
    /** Absolute index of the opcode-7 flags byte, or -1 if opcode 7 absent. */
    val op7Offset: Int,
    /** Hit the terminating opcode 0 with no overrun. */
    val clean: Boolean,
    /** clean AND consumed exactly to the end of the record (no trailing bytes). */
    val exactFit: Boolean,
    val raw: ByteArray,
) {
    val safe get() = clean && exactFit
}

/** Byte-walker over an area record; mirrors AreaDecoder operand sizes exactly. */
private class Walker(val a: ByteArray) {
    var p = 0
    fun u1(): Int = a[p++].toInt() and 0xFF
    fun skip(n: Int) { p += n }
    fun smart(): Int { val peek = u1(); return if (peek < 128) peek else ((peek shl 8) or u1()) - 32768 }
    fun largeSmart(): Int { var base = 0; var v = smart(); while (v == 32767) { v = smart(); base += 32767 }; return base + v }
    fun str() { while (p < a.size) { if (u1() == 0) break } }
}

private fun parseArea(id: Int, data: ByteArray): Area {
    val w = Walker(data)
    var name = "null"; var sprite1 = -1; var sprite2 = -1
    var worldMap = true; var miniMap = false
    var op7 = -1
    var clean = false
    try {
        while (w.p < data.size) {
            val op = w.u1()
            if (op == 0) { clean = true; break }
            when (op) {
                1 -> sprite1 = w.largeSmart()
                2 -> sprite2 = w.largeSmart()
                3 -> { val s = w.p; w.str(); name = String(data, s, w.p - 1 - s, Charsets.ISO_8859_1) }
                4 -> w.skip(3)
                5 -> w.skip(3)
                6 -> w.u1()
                7 -> { op7 = w.p; val f = w.u1(); worldMap = (f and 0x1) != 0; miniMap = (f and MINIMAP_BIT) == MINIMAP_BIT }
                8 -> w.u1()
                in 10..14 -> w.str()
                15 -> { val len = w.u1(); w.skip(len * 2 * 2); w.skip(4); val sub = w.u1(); w.skip(sub * 4); w.skip(len) }
                16 -> w.skip(1)
                17 -> w.str()
                18 -> w.largeSmart()
                19 -> w.skip(2)
                21 -> w.skip(4)
                22 -> w.skip(4)
                23 -> w.skip(3)
                24 -> w.skip(4)
                25 -> w.largeSmart()
                28 -> w.skip(1)
                29 -> w.u1()
                30 -> w.u1()
                else -> { } // unknown opcode: zero operands, like AreaDecoder's read-loop
            }
        }
    } catch (e: IndexOutOfBoundsException) {
        clean = false
    }
    val exact = clean && w.p == data.size
    return Area(id, name, sprite1, sprite2, worldMap, miniMap, op7, clean, exact, data)
}

private fun readAreas(): List<Area> {
    val cache = Cache.load(Path.of(CACHE_PATH), false)
    try {
        val ids = cache.files(CONFIGS, AREA)
        val out = ArrayList<Area>(ids.size)
        for (id in ids) {
            val data = cache.data(CONFIGS, AREA, id) ?: continue
            out += parseArea(id, data)
        }
        return out
    } finally {
        cache.close()
    }
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "scan"
    println("minimap-icons tool: mode=$mode cache=$CACHE_PATH (CONFIGS=$CONFIGS AREA=$AREA)")
    when (mode) {
        "scan" -> scan()
        "diag" -> diag()
        "map" -> mapRefs()
        "locate" -> locate()
        "sprites" -> dumpSprites(args.drop(1))
        "apply" -> apply(args.drop(1))
        "keep" -> keep(args.drop(1))
        "restore" -> restore()
        else -> println("unknown mode '$mode' (expected: scan | diag | map | apply [all|<ids...>] | keep <ids...> | restore)")
    }
}

/** For each minimap area, show its sprites + the names of objects that place it (via mapAreaId). */
private fun mapRefs() {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), 228)
    val byArea = HashMap<Int, MutableMap<String, Int>>()
    for (o in CacheManager.getObjects().values) {
        if (o.mapAreaId != -1) {
            val nm = (o.name ?: "null").ifBlank { "null" }
            byArea.getOrPut(o.mapAreaId) { LinkedHashMap() }.merge(nm, 1, Int::plus)
        }
    }
    val onMini = readAreas().filter { it.miniMap && it.safe }.sortedBy { it.id }
    println("==== minimap areas -> placing objects (${onMini.size}) ====")
    println("areaId\tspr1\tspr2\t#objs\tobject names")
    onMini.forEach { a ->
        val refs = byArea[a.id]
        val objs = refs?.entries?.sortedByDescending { it.value }?.take(6)?.joinToString(", ") { "${it.key}x${it.value}" } ?: "(none)"
        println("${a.id}\t${a.sprite1}\t${a.sprite2}\t${refs?.values?.sum() ?: 0}\t$objs")
    }
}

private fun scan() {
    val areas = readAreas()
    val unsafe = areas.filter { !it.safe }
    val onMini = areas.filter { it.miniMap && it.safe }.sortedBy { it.id }
    println("==== AreaType scan ====")
    println("total areas read = ${areas.size}, clean+exact-fit = ${areas.count { it.safe }}, not-exact = ${unsafe.size}")
    if (unsafe.isNotEmpty()) println("   (records that don't exact-fit, left untouched: ${unsafe.take(20).joinToString(" ") { it.id.toString() }}${if (unsafe.size > 20) " …" else ""})")
    println("---- areas rendered on the MINIMAP (${onMini.size}) ----")
    println("id\tsprite1\tname")
    onMini.forEach { println("${it.id}\t${it.sprite1}\t${it.name}") }
    println("------------------------------------------------")
    println("To hide ALL of them:  apply all")
    if (onMini.isNotEmpty()) println("To hide specific ids: apply ${onMini.take(3).joinToString(" ") { it.id.toString() }} ...")
}

/** Hex dump + walker summary for eyeballing the records. */
private fun diag() {
    val cache = Cache.load(Path.of(CACHE_PATH), false)
    try {
        val ids = cache.files(CONFIGS, AREA)
        println("AREA files: count=${ids.size}")
        for (id in listOf(ids.first(), ids[ids.size / 2], ids.last())) {
            val d = cache.data(CONFIGS, AREA, id) ?: continue
            val hex = d.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            val a = parseArea(id, d)
            println("area $id len=${d.size} clean=${a.clean} exact=${a.exactFit} mini=${a.miniMap} op7@${a.op7Offset}: $hex")
        }
    } finally {
        cache.close()
    }
}

private fun apply(idArgs: List<String>) {
    if (idArgs.isEmpty()) { println("refusing to run: specify 'all' or a list of area ids"); return }
    val areas = readAreas()
    val byId = areas.associateBy { it.id }
    val targets: List<Area> = if (idArgs.first().equals("all", true)) {
        areas.filter { it.miniMap && it.safe }
    } else {
        idArgs.mapNotNull { it.toIntOrNull() }.mapNotNull { byId[it] }
    }
    hide(targets)
}

/**
 * Decode the Lumbridge region(s) loc objects and report every map-marker (loc whose
 * ObjectType.mapAreaId != -1) with its world tile, so markers can be matched to landmarks.
 */
private fun locate() {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), 228)
    val empty = IntArray(4) // maps are decrypted in this cache; empty xtea
    data class Mark(val area: Int, val x: Int, val y: Int, val h: Int)
    data class Named(val name: String, val x: Int, val y: Int, val h: Int)
    val marks = ArrayList<Mark>()
    val named = ArrayList<Named>()
    // Lumbridge surface (ry 48..52) + the cellar where the mine lives (ry ~150).
    val regions = (48..52).flatMap { rx -> ((48..52) + (148..152)).map { ry -> rx to ry } }
    for ((rx, ry) in regions) {
        val baseX = rx shl 6; val baseY = ry shl 6
        val land = try { CacheManager.cache.data(MAPS, "l${rx}_$ry", empty) } catch (e: Exception) { null } ?: continue
        try {
            loadLocations(land) { loc ->
                val o = CacheManager.getObjectOrDefault(loc.id)
                val wx = baseX + loc.localX; val wy = baseY + loc.localY
                if (o.mapAreaId != -1) marks += Mark(o.mapAreaId, wx, wy, loc.height)
                val nm = (o.name ?: "null")
                if (nm != "null" && nm.isNotBlank()) named += Named(nm, wx, wy, loc.height)
            }
        } catch (e: Exception) { println("region $rx,$ry loc decode failed: ${e.message}") }
    }
    // One row per distinct (area, tile) marker, annotated with the named objects sitting on/near it.
    val distinct = marks.distinctBy { listOf(it.area, it.x, it.y, it.h) }.sortedBy { it.area }
    println("==== ${distinct.size} distinct map-markers (area @ tile -> nearby named objects) ====")
    distinct.forEach { m ->
        val near = named.filter { it.h == m.h && Math.abs(it.x - m.x) <= 2 && Math.abs(it.y - m.y) <= 2 }
            .distinctBy { it.name }
            .sortedBy { Math.abs(it.x - m.x) + Math.abs(it.y - m.y) }
            .take(4).joinToString(", ") { it.name }
        println("area=${m.area}\ttile=(${m.x},${m.y},${m.h})\t-> ${if (near.isBlank()) "(no named obj nearby)" else near}")
    }
}

/** Decode the given sprite group ids and write them (scaled 12x) as PNGs for visual ID. */
private fun dumpSprites(idArgs: List<String>) {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), 228)
    val all = SpriteDecoder().load(CacheManager.cache)
    val outDir = File("minimap-sprites"); outDir.mkdirs()
    idArgs.mapNotNull { it.toIntOrNull() }.forEach { id ->
        val st = all[id]
        if (st == null) { println("sprite $id: not found"); return@forEach }
        val img = st.toSprite(0)
        val scale = 12
        val big = BufferedImage(img.width * scale, img.height * scale, BufferedImage.TYPE_INT_ARGB)
        val g = big.createGraphics()
        g.drawImage(img.getScaledInstance(img.width * scale, img.height * scale, java.awt.Image.SCALE_FAST), 0, 0, null)
        g.dispose()
        val f = File(outDir, "sprite_$id.png")
        ImageIO.write(big, "png", f)
        println("wrote ${f.absolutePath} (${img.width}x${img.height} -> ${big.width}x${big.height})")
    }
}

/** Hide every minimap area EXCEPT the given ids (the ones to keep visible). */
private fun keep(idArgs: List<String>) {
    val keepIds = idArgs.mapNotNull { it.toIntOrNull() }.toSet()
    if (keepIds.isEmpty()) { println("refusing to run: specify the area ids to KEEP on the minimap"); return }
    val areas = readAreas()
    val targets = areas.filter { it.miniMap && it.safe && it.id !in keepIds }
    println("keeping ${keepIds.size} area(s) on the minimap: ${keepIds.sorted().joinToString(" ")}")
    hide(targets)
}

private fun hide(targets: List<Area>) {
    val edits = LinkedHashMap<Int, ByteArray>()
    for (t in targets) {
        if (!t.safe) { println("SKIP area ${t.id}: record not clean+exact-fit (unsafe to edit)"); continue }
        if (!t.miniMap) { println("SKIP area ${t.id} (${t.name}): not on minimap"); continue }
        if (t.op7Offset < 0) { println("SKIP area ${t.id}: no opcode-7 offset"); continue }
        val edited = t.raw.copyOf()
        edited[t.op7Offset] = (edited[t.op7Offset].toInt() and MINIMAP_BIT.inv()).toByte()
        val re = parseArea(t.id, edited)
        if (!re.safe || re.miniMap) { println("ABORT area ${t.id}: post-edit re-parse failed (safe=${re.safe} mini=${re.miniMap})"); continue }
        edits[t.id] = edited
        println("queued: area ${t.id} (${t.name}) -> minimap off, worldMap stays=${re.worldMap}")
    }
    if (edits.isEmpty()) { println("nothing to write."); return }

    backup()
    val lib = CacheLibrary(CACHE_PATH)
    try {
        val archive = lib.index(CONFIGS).archive(AREA) ?: run { println("ABORT: no AREA archive"); return }
        edits.forEach { (id, bytes) -> archive.add(id, bytes) }
        lib.update()
    } finally {
        lib.close()
    }

    // re-read from disk via the proven path and assert each flag flipped
    val after = readAreas().associateBy { it.id }
    var ok = 0; var fail = 0
    edits.keys.forEach { id ->
        val a = after[id]
        if (a != null && a.safe && !a.miniMap) ok++
        else { fail++; println("VERIFY FAIL: area $id safe=${a?.safe} mini=${a?.miniMap}") }
    }
    println("==== done: wrote ${edits.size}, verified off = $ok, verify failures = $fail ====")
    println(if (fail > 0) "Some writes did NOT verify — run 'restore' to roll back." else "Restart the server so it reloads the cache; clients pull the update on reconnect.")
}

private fun backup() {
    val src = File(CACHE_PATH)
    val dst = File(src.parentFile, src.name + "_backup_minimap")
    if (dst.exists()) { println("backup already exists at ${dst.path} (left as-is)"); return }
    dst.mkdirs()
    src.listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true) }
    println("backed up cache -> ${dst.path}")
}

private fun restore() {
    val dst = File(CACHE_PATH)
    val src = File(dst.parentFile, dst.name + "_backup_minimap")
    if (!src.exists()) { println("no backup at ${src.path} — nothing to restore"); return }
    src.listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true) }
    println("restored cache from ${src.path}")
}
