package org.alter.tools.siegehud

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Stage 1 of the custom siege-HUD interface (see `docs/long-term-vision.md` §4).
 *
 * The vendored OpenRune filestore is read-only, so we author interfaces with the
 * displee cache library. But first we must trust our hand-written **if3** codec:
 * this tool decodes every existing interface component in the live cache and, for
 * the component shapes we author (types 0/3/4 with minimal trailing), **re-encodes
 * them and byte-compares to the original**. If those round-trip byte-for-byte, our
 * encoder matches what the client expects — the green light to author for real.
 *
 * Run: `gradlew :game-server:siegeHud -PsiegeArgs="validate"` (workingDir = repo root).
 */

// ---- if3 component model (only the fields we need to reproduce types 0/3/4) ----
private class Comp(
    val type: Int, val contentType: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val widthMode: Int, val heightMode: Int, val xMode: Int, val yMode: Int, val parent: Int, val hidden: Int,
    // type 0 (layer)
    val scrollW: Int = 0, val scrollH: Int = 0, val noClickThrough: Int = 0,
    // type 3 (rectangle)
    val rectColour: Int = 0, val filled: Int = 0, val opacity: Int = 0,
    // type 4 (text)
    val font: Int = 0, val text: String = "", val lineHeight: Int = 0,
    val xAlign: Int = 0, val yAlign: Int = 0, val shadowed: Int = 0, val textColour: Int = 0,
    // trailing (minimal)
    val clickMask: Int = 0, val name: String = "", val dragDeadZone: Int = 0,
    val dragDeadTime: Int = 0, val dragRender: Int = 0, val targetVerb: String = "",
)

private sealed class Dec {
    class Validated(val comp: Comp) : Dec()
    class Consume(val leftover: Int) : Dec() // fully parsed a supported type but bytes left over -> BUG
    class Skip(val reason: String) : Dec()
}

private class R(val a: ByteArray) {
    var p = 0
    fun u1() = a[p++].toInt() and 0xFF
    fun u2() = (u1() shl 8) or u1()
    fun s2(): Int { val v = u2(); return if (v > 32767) v - 65536 else v }
    fun i4() = (u1() shl 24) or (u1() shl 16) or (u1() shl 8) or u1()
    fun u3() = (u1() shl 16) or (u1() shl 8) or u1()
    fun str(): String { val sb = StringBuilder(); while (true) { val c = u1(); if (c == 0) break; sb.append(c.toChar()) }; return sb.toString() }
    fun rem() = a.size - p
}

private class W {
    private val o = ByteArrayOutputStream()
    fun u1(v: Int) { o.write(v and 0xFF) }
    fun u2(v: Int) { u1(v shr 8); u1(v) }
    fun i4(v: Int) { u1(v shr 24); u1(v shr 16); u1(v shr 8); u1(v) }
    fun u3(v: Int) { u1(v shr 16); u1(v shr 8); u1(v) }
    fun str(s: String) { for (ch in s) o.write(ch.code and 0xFF); o.write(0) }
    fun bytes(): ByteArray = o.toByteArray()
}

private fun decode(data: ByteArray): Dec {
    val r = R(data)
    if (r.u1() != 0xFF) return Dec.Skip("if1-format") // legacy components start without the -1 marker
    val type = r.u1()
    val contentType = r.u2()
    val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
    val widthMode = r.u1(); val heightMode = r.u1(); val xMode = r.u1(); val yMode = r.u1()
    val parent = r.u2(); val hidden = r.u1()

    var scrollW = 0; var scrollH = 0; var noClick = 0
    var rectColour = 0; var filled = 0; var opacity = 0
    var font = 0; var text = ""; var lineH = 0; var xa = 0; var ya = 0; var shadow = 0; var textColour = 0
    when (type) {
        0 -> { scrollW = r.u2(); scrollH = r.u2(); noClick = r.u1() }
        3 -> { rectColour = r.i4(); filled = r.u1(); opacity = r.u1() }
        4 -> { font = r.u2(); text = r.str(); lineH = r.u1(); xa = r.u1(); ya = r.u1(); shadow = r.u1(); textColour = r.i4() }
        else -> return Dec.Skip("type-$type")
    }

    val clickMask = r.u3()
    val name = r.str()
    val actionCount = r.u1()
    if (actionCount > 0) return Dec.Skip("has-actions")
    val dragDeadZone = r.u1(); val dragDeadTime = r.u1(); val dragRender = r.u1()
    val targetVerb = r.str()
    repeat(18) { if (r.u1() != 0) return Dec.Skip("has-listener") }
    repeat(3) { if (r.u1() != 0) return Dec.Skip("has-trigger") }

    if (r.rem() != 0) return Dec.Consume(r.rem())
    return Dec.Validated(
        Comp(
            type, contentType, x, y, w, h, widthMode, heightMode, xMode, yMode, parent, hidden,
            scrollW, scrollH, noClick, rectColour, filled, opacity,
            font, text, lineH, xa, ya, shadow, textColour,
            clickMask, name, dragDeadZone, dragDeadTime, dragRender, targetVerb,
        ),
    )
}

private fun encode(c: Comp): ByteArray {
    val w = W()
    w.u1(0xFF); w.u1(c.type); w.u2(c.contentType)
    w.u2(c.x and 0xFFFF); w.u2(c.y and 0xFFFF); w.u2(c.w); w.u2(c.h)
    w.u1(c.widthMode); w.u1(c.heightMode); w.u1(c.xMode); w.u1(c.yMode)
    w.u2(c.parent); w.u1(c.hidden)
    when (c.type) {
        0 -> { w.u2(c.scrollW); w.u2(c.scrollH); w.u1(c.noClickThrough) }
        3 -> { w.i4(c.rectColour); w.u1(c.filled); w.u1(c.opacity) }
        4 -> { w.u2(c.font); w.str(c.text); w.u1(c.lineHeight); w.u1(c.xAlign); w.u1(c.yAlign); w.u1(c.shadowed); w.i4(c.textColour) }
    }
    w.u3(c.clickMask); w.str(c.name); w.u1(0) // 0 actions
    w.u1(c.dragDeadZone); w.u1(c.dragDeadTime); w.u1(c.dragRender); w.str(c.targetVerb)
    repeat(18) { w.u1(0) }
    repeat(3) { w.u1(0) }
    return w.bytes()
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "validate"
    val cachePath = args.getOrNull(1) ?: "data/cache"
    val gid = args.getOrNull(2)?.toIntOrNull() ?: SIEGE_HUD_INTERFACE
    println("siege-hud tool: mode=$mode cache=$cachePath gid=$gid")
    val lib = CacheLibrary(cachePath)
    try {
        when (mode) {
            "validate" -> validate(lib)
            "build" -> build(lib, gid)
            "inspect" -> inspect(lib, gid)
            else -> println("unknown mode '$mode' (expected: validate | build | inspect)")
        }
    } finally {
        lib.close()
    }
}

// ---- Stage 2: author the siege HUD interface ----
const val SIEGE_HUD_INTERFACE = 1000 // unused group id (existing interfaces top out ~909)
const val SIEGE_HUD_ROOT = 0
const val SIEGE_HUD_TEXT = 2
const val SIEGE_HUD_SEG0 = 3 // first of 10 fill segments (components 3..12)
const val SIEGE_HUD_SEGMENTS = 10

private fun build(lib: CacheLibrary, gid: Int) {
    val pad = 5
    val width = 176
    val height = 34
    val segGap = 1
    val segW = (width - pad * 2 - (SIEGE_HUD_SEGMENTS - 1) * segGap) / SIEGE_HUD_SEGMENTS
    val comps = ArrayList<Comp>()

    // 0: root layer, anchored bottom-LEFT (xMode=0 from left, yMode=2 from bottom),
    //    sitting just above the chatbox so the minimap/hover tooltips don't cover it.
    comps += Comp(
        type = 0, contentType = 0, x = 4, y = 170, w = width, h = height,
        widthMode = 0, heightMode = 0, xMode = 0, yMode = 2, parent = 0xFFFF, hidden = 0,
        scrollW = 0, scrollH = 0, noClickThrough = 1,
    )
    // 1: dark translucent background panel.
    comps += Comp(
        type = 3, contentType = 0, x = 0, y = 0, w = width, h = height,
        widthMode = 0, heightMode = 0, xMode = 0, yMode = 0, parent = 0, hidden = 0,
        rectColour = 0x111111, filled = 1, opacity = 70,
    )
    // 2: status text line (server overwrites via IfSetText).
    comps += Comp(
        type = 4, contentType = 0, x = pad, y = 3, w = width - pad * 2, h = 13,
        widthMode = 0, heightMode = 0, xMode = 0, yMode = 0, parent = 0, hidden = 0,
        font = 495, text = "LUMBRIDGE", lineHeight = 13, xAlign = 0, yAlign = 0, shadowed = 1, textColour = 0xFFFF00,
    )
    // 3..12: ten fill segments; server hides the empty ones to make the bar fill.
    for (i in 0 until SIEGE_HUD_SEGMENTS) {
        comps += Comp(
            type = 3, contentType = 0, x = pad + i * (segW + segGap), y = 20, w = segW, h = 9,
            widthMode = 0, heightMode = 0, xMode = 0, yMode = 0, parent = 0, hidden = 0,
            rectColour = 0xC83232, filled = 1, opacity = 0,
        )
    }

    comps.forEachIndexed { fid, c -> lib.put(3, gid, fid, encode(c)) }
    lib.update()
    println("wrote interface $gid with ${comps.size} components (segW=$segW)")

    // verify: read back + decode each component
    var ok = 0
    comps.indices.forEach { fid ->
        val data = lib.data(3, gid, fid)
        if (data == null) { println("  VERIFY FAIL: component $fid missing"); return@forEach }
        when (val d = decode(data)) {
            is Dec.Validated -> ok++
            is Dec.Consume -> println("  VERIFY FAIL: component $fid leftover=${d.leftover}")
            is Dec.Skip -> println("  VERIFY FAIL: component $fid skipped=${d.reason}")
        }
    }
    println(if (ok == comps.size) ">>> AUTHORED OK — $ok/${comps.size} components verified." else ">>> AUTHOR VERIFY FAILED ($ok/${comps.size}).")
}

/** Print the geometry of every component of [gid] so we can read real anchor modes. */
private fun inspect(lib: CacheLibrary, gid: Int) {
    val archive = lib.index(3)?.archive(gid) ?: run { println("no interface $gid"); return }
    println("interface $gid components (fid: type x y w h xMode yMode parent):")
    for (fid in archive.fileIds()) {
        val data = lib.data(3, gid, fid) ?: continue
        val r = R(data)
        if (r.u1() != 0xFF) { println("  $fid: if1-format"); continue }
        val type = r.u1(); r.u2() // contentType
        val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
        val wMode = r.u1(); val hMode = r.u1(); val xMode = r.u1(); val yMode = r.u1()
        val parent = r.u2()
        println("  $fid: type=$type x=$x y=$y w=$w h=$h wMode=$wMode hMode=$hMode xMode=$xMode yMode=$yMode parent=$parent")
    }
}

private fun validate(lib: CacheLibrary) {
    val index = lib.index(3) ?: run { println("no interface index (3)!"); return }
    var validated = 0; var encodeMismatch = 0; var consumeMismatch = 0; var skip = 0; var err = 0
    val samples = mutableListOf<String>()
    val skipReasons = HashMap<String, Int>()
    for (aid in index.archiveIds()) {
        val archive = index.archive(aid) ?: continue
        for (fid in archive.fileIds()) {
            val data = lib.data(3, aid, fid) ?: continue
            try {
                when (val d = decode(data)) {
                    is Dec.Validated -> {
                        val re = encode(d.comp)
                        if (re.contentEquals(data)) {
                            validated++
                        } else {
                            encodeMismatch++
                            if (samples.size < 8) samples.add("ENC  $aid:$fid type=${d.comp.type} origLen=${data.size} reLen=${re.size}")
                        }
                    }
                    is Dec.Consume -> {
                        consumeMismatch++
                        if (samples.size < 8) samples.add("LEFT $aid:$fid leftover=${d.leftover} len=${data.size}")
                    }
                    is Dec.Skip -> { skip++; skipReasons.merge(d.reason.substringBefore('-') + "-*", 1, Int::plus) }
                }
            } catch (e: Exception) {
                err++
                if (samples.size < 8) samples.add("ERR  $aid:$fid ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
    println("==== if3 codec round-trip ====")
    println("VALIDATED (byte-identical) = $validated")
    println("ENCODE_MISMATCH (BUG)      = $encodeMismatch")
    println("CONSUME_MISMATCH (BUG)     = $consumeMismatch")
    println("SKIP (other shapes)        = $skip  $skipReasons")
    println("ERR (decode threw)         = $err")
    if (samples.isNotEmpty()) { println("---- samples ----"); samples.forEach { println("  $it") } }
    val ok = encodeMismatch == 0 && consumeMismatch == 0 && validated > 0
    println(if (ok) ">>> CODEC OK — safe to author." else ">>> CODEC NOT verified — do NOT author yet.")
}
