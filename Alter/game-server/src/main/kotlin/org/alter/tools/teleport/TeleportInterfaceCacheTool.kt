package org.alter.tools.teleport

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Phase 2 of the teleport portal (see `docs/teleport-portal.md`): authors the custom
 * tabbed teleport interface into the cache (index 3, group [TELE_IFACE]), Roak-style.
 *
 * Reuses the **exact** hand-written if3 codec proven by the siege-HUD tool
 * (`org.alter.tools.siegehud`) — only layer/rect/text components with 0 actions and no
 * listeners (clicks are enabled at runtime by the server via `IfSetEvents`, so no baked
 * clickMask is needed). Component ids here MUST stay in sync with
 * `org.alter.plugins.content.teleport.TeleportInterface` (the server-side driver).
 *
 * Run with the server STOPPED (cache file lock):
 *   gradlew :game-server:teleportIface -PteleArgs="build"      # author
 *   gradlew :game-server:teleportIface -PteleArgs="inspect"    # dump geometry
 */

// ── component ids (KEEP IN SYNC with TeleportInterface.kt) ───────────────────────
const val TELE_IFACE = 1101 // bumped from 1100 (fresh group → avoid stale client fetch)
private const val ROOT = 0
private const val WIN_BG = 1
private const val TITLE_BAR = 2
private const val TITLE_TXT = 3
private const val CLOSE_BTN = 4
private const val CLOSE_TXT = 5
private const val TAB_COL_BG = 6
private const val LIST_BG = 7
private const val HDR_LOC = 8
private const val HDR_PRICE = 9
private const val HDR_DANGER = 10
private const val TAB_BASE = 20
private const val TAB_COUNT = 9
private const val ROW_BASE = 100
private const val ROWS = 22 // covers the largest category (Bosses ~21)

// ── geometry (authored against the fixed 512x334 main-screen pane) ───────────────
private const val PANE_W = 512
private const val PANE_H = 334
private const val ROW_H = 12
private const val ROW_Y0 = 50

// fonts/colours
private const val FONT = 495
private const val GOLD = 0xff981f
private const val WHITE = 0xffffff
private const val GREY = 0xc8c8c8

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "inspect"
    val cachePath = args.getOrNull(1) ?: "data/cache"
    val gid = args.getOrNull(2)?.toIntOrNull() ?: TELE_IFACE
    println("teleport-iface tool: mode=$mode cache=$cachePath gid=$gid")
    val lib = CacheLibrary(cachePath)
    try {
        when (mode) {
            "build" -> build(lib, gid)
            "inspect" -> inspect(lib, gid)
            else -> println("unknown mode '$mode' (expected: build | inspect)")
        }
    } finally {
        lib.close()
    }
}

private fun build(lib: CacheLibrary, gid: Int) {
    val comps = LinkedHashMap<Int, Comp>()

    // 0: root layer — fills the main-screen pane, blocks click-through behind the modal.
    comps[ROOT] = layer(x = 0, y = 0, w = PANE_W, h = PANE_H, parent = 0xFFFF, noClickThrough = 1)
    // 1: window background panel.
    comps[WIN_BG] = rect(4, 6, 504, 322, 0x39352c)
    // 2/3: title bar + centred title.
    comps[TITLE_BAR] = rect(4, 6, 504, 22, 0x5a5340)
    comps[TITLE_TXT] = text(4, 10, 504, 16, "Kearse Teleports", colour = GOLD, xAlign = 1)
    // 4/5: close button (red box + X). Clicks enabled at runtime via IfSetEvents
    // (baked clickMask is left 0 — the only shape the if3 codec is proven safe on).
    comps[CLOSE_BTN] = rect(488, 8, 16, 16, 0x6a2020)
    comps[CLOSE_TXT] = text(488, 10, 16, 14, "X", colour = WHITE, xAlign = 1)
    // 6/7: left tab column + right list backgrounds.
    comps[TAB_COL_BG] = rect(6, 30, 120, 300, 0x2b2820)
    comps[LIST_BG] = rect(130, 30, 378, 300, 0x2b2820)
    // 8/9/10: column headers.
    comps[HDR_LOC] = text(138, 33, 180, 14, "Location", colour = GOLD)
    comps[HDR_PRICE] = text(320, 33, 60, 14, "Price", colour = GOLD)
    comps[HDR_DANGER] = text(400, 33, 104, 14, "Danger", colour = GOLD)

    // 20..28: nine tab labels (text + clicks enabled at runtime).
    for (t in 0 until TAB_COUNT) {
        comps[TAB_BASE + t] = text(10, 36 + t * 30, 112, 24, "", colour = GREY)
    }

    // 100..: ROWS rows of {name, price, danger}; text + clicks enabled at runtime.
    for (i in 0 until ROWS) {
        val y = ROW_Y0 + i * ROW_H
        comps[ROW_BASE + i * 3 + 0] = text(138, y, 180, ROW_H, "", colour = WHITE)
        comps[ROW_BASE + i * 3 + 1] = text(320, y, 70, ROW_H, "", colour = WHITE)
        comps[ROW_BASE + i * 3 + 2] = text(400, y, 104, ROW_H, "", colour = WHITE)
    }

    comps.forEach { (fid, c) -> lib.put(3, gid, fid, encode(c)) }
    lib.update()
    println("wrote interface $gid with ${comps.size} components")

    var ok = 0
    comps.keys.forEach { fid ->
        val data = lib.data(3, gid, fid)
        if (data == null) { println("  VERIFY FAIL: component $fid missing"); return@forEach }
        when (decode(data)) {
            is Dec.Validated -> ok++
            is Dec.Consume -> println("  VERIFY FAIL: component $fid has leftover bytes")
            is Dec.Skip -> println("  VERIFY FAIL: component $fid skipped")
        }
    }
    println(if (ok == comps.size) ">>> AUTHORED OK — $ok/${comps.size} components verified." else ">>> AUTHOR VERIFY FAILED ($ok/${comps.size}).")
}

private fun inspect(lib: CacheLibrary, gid: Int) {
    val archive = lib.index(3)?.archive(gid) ?: run { println("no interface $gid"); return }
    println("interface $gid components:")
    for (fid in archive.fileIds()) {
        val data = lib.data(3, gid, fid) ?: continue
        val r = R(data)
        if (r.u1() != 0xFF) { println("  $fid: if1-format"); continue }
        val type = r.u1(); r.u2()
        val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
        println("  $fid: type=$type x=$x y=$y w=$w h=$h")
    }
}

// ── component builders ───────────────────────────────────────────────────────────
private fun layer(x: Int, y: Int, w: Int, h: Int, parent: Int, noClickThrough: Int) =
    Comp(type = 0, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = parent, hidden = 0, noClickThrough = noClickThrough)

private fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int, opacity: Int = 0, clickMask: Int = 0) =
    Comp(type = 3, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, rectColour = colour, filled = 1, opacity = opacity,
        clickMask = clickMask)

private fun text(x: Int, y: Int, w: Int, h: Int, s: String, colour: Int, xAlign: Int = 0, clickMask: Int = 0) =
    Comp(type = 4, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, font = FONT, text = s, lineHeight = ROW_H,
        xAlign = xAlign, yAlign = 0, shadowed = 1, textColour = colour, clickMask = clickMask)

// ── if3 codec (identical to SiegeHudCacheTool — types 0/3/4, 0 actions) ───────────
private class Comp(
    val type: Int, val contentType: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val widthMode: Int, val heightMode: Int, val xMode: Int, val yMode: Int, val parent: Int, val hidden: Int,
    val scrollW: Int = 0, val scrollH: Int = 0, val noClickThrough: Int = 0,
    val rectColour: Int = 0, val filled: Int = 0, val opacity: Int = 0,
    val font: Int = 0, val text: String = "", val lineHeight: Int = 0,
    val xAlign: Int = 0, val yAlign: Int = 0, val shadowed: Int = 0, val textColour: Int = 0,
    val clickMask: Int = 0, val name: String = "", val dragDeadZone: Int = 0,
    val dragDeadTime: Int = 0, val dragRender: Int = 0, val targetVerb: String = "",
)

private sealed class Dec {
    class Validated(val comp: Comp) : Dec()
    class Consume(val leftover: Int) : Dec()
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
    if (r.u1() != 0xFF) return Dec.Skip("if1-format")
    val type = r.u1(); val contentType = r.u2()
    val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
    val widthMode = r.u1(); val heightMode = r.u1(); val xMode = r.u1(); val yMode = r.u1()
    val parent = r.u2(); val hidden = r.u1()
    var scrollW = 0; var scrollH = 0; var noClick = 0
    var rectColour = 0; var filled = 0; var opacity = 0
    var font = 0; var txt = ""; var lineH = 0; var xa = 0; var ya = 0; var shadow = 0; var textColour = 0
    when (type) {
        0 -> { scrollW = r.u2(); scrollH = r.u2(); noClick = r.u1() }
        3 -> { rectColour = r.i4(); filled = r.u1(); opacity = r.u1() }
        4 -> { font = r.u2(); txt = r.str(); lineH = r.u1(); xa = r.u1(); ya = r.u1(); shadow = r.u1(); textColour = r.i4() }
        else -> return Dec.Skip("type-$type")
    }
    val clickMask = r.u3(); val name = r.str()
    val actionCount = r.u1()
    if (actionCount > 0) return Dec.Skip("has-actions")
    val dragDeadZone = r.u1(); val dragDeadTime = r.u1(); val dragRender = r.u1()
    val targetVerb = r.str()
    repeat(18) { if (r.u1() != 0) return Dec.Skip("has-listener") }
    repeat(3) { if (r.u1() != 0) return Dec.Skip("has-trigger") }
    if (r.rem() != 0) return Dec.Consume(r.rem())
    return Dec.Validated(Comp(type, contentType, x, y, w, h, widthMode, heightMode, xMode, yMode, parent, hidden,
        scrollW, scrollH, noClick, rectColour, filled, opacity, font, txt, lineH, xa, ya, shadow, textColour,
        clickMask, name, dragDeadZone, dragDeadTime, dragRender, targetVerb))
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
    w.u3(c.clickMask); w.str(c.name); w.u1(0)
    w.u1(c.dragDeadZone); w.u1(c.dragDeadTime); w.u1(c.dragRender); w.str(c.targetVerb)
    repeat(18) { w.u1(0) }
    repeat(3) { w.u1(0) }
    return w.bytes()
}
