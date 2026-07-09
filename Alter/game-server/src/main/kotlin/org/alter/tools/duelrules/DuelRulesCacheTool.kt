package org.alter.tools.duelrules

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Authors the **Duel Arena rules-grid** interface into the cache (index 3, group [DUEL_RULES_IFACE]).
 *
 * The faithful classic duel screen: a grid of clickable rule toggles (checkbox + label), an
 * accept-status line, and Accept / Decline buttons. Driven at runtime by
 * `org.alter.plugins.content.minigames.duel.DuelRulesScreen` (ids MUST stay in sync with it).
 *
 * Built strictly from the PROVEN recipes (PanelCacheTool / TabbedPanel, verified in-client):
 *  - only type-0 layers, type-3 rects, type-4 text — NO baked sprites (they NPE our custom draw),
 *  - clickable = transparent type-0 hit layer with clickMask=op1 + one action string,
 *  - component ids CONTIGUOUS 0..[LAST_ID] (a gap = client NPE while drawing),
 *  - group id in the free, in-range window (client interface array = 1102 → valid ids 0..1101).
 *
 * Run with the server STOPPED (cache file lock):
 *   gradlew :game-server:duelRulesIface -PduelRulesArgs="build"
 *   gradlew :game-server:duelRulesIface -PduelRulesArgs="inspect"
 * If the layout is ever changed, BUMP the group id (stale JS5 doesn't re-fetch changed bytes) and
 * update DuelRulesScreen.IFACE to match; the player must fully restart the client after authoring.
 */

// ── component ids (KEEP IN SYNC with DuelRulesScreen.kt) ─────────────────────────
const val DUEL_RULES_IFACE = 1020
private const val ROOT = 0
private const val FRAME = 1            // dark outer edge
private const val BODY = 2             // warm stone body
private const val TITLE_BAR = 3
private const val TITLE_TXT = 4
private const val CLOSE_BG = 5
private const val CLOSE_TXT = 6
private const val RULES_PANEL = 7      // translucent dark panel behind the grid
private const val SUBTITLE_TXT = 8
private const val BOX_BASE = 9         // 9..20   checkbox outer rects
private const val TICK_BASE = 21       // 21..32  checkbox green fills (authored HIDDEN)
private const val LBL_BASE = 33        // 33..44  rule labels
private const val TOGGLES = 12         // 2 cols x 6 rows of toggle slots (unused ones hidden at runtime)
private const val STATUS_TXT = 45      // accept-status line
private const val ACCEPT_LIGHT = 46    // accept button bevel light edge
private const val ACCEPT_DARK = 47     //   " dark edge
private const val ACCEPT_FILL = 48     //   " fill
private const val ACCEPT_TXT = 49      //   " label
private const val DECLINE_LIGHT = 50   // decline button (same anatomy)
private const val DECLINE_DARK = 51
private const val DECLINE_FILL = 52
private const val DECLINE_TXT = 53
private const val CLOSE_HIT = 54       // clickable layers (what the server binds onButton to)
private const val RULE_HIT_BASE = 55   // 55..66 one per toggle slot
private const val ACCEPT_HIT = 67
private const val DECLINE_HIT = 68
private const val LAST_ID = 68         // 69 components, contiguous 0..68

// ── geometry (fixed 512x334 main-screen pane) ────────────────────────────────────
private const val PANE_W = 512
private const val PANE_H = 334
private val GRID_COL_X = intArrayOf(28, 268)  // two toggle columns
private const val GRID_Y0 = 62
private const val GRID_DY = 32
private const val TOGGLE_W = 224
private const val TOGGLE_H = 30
private const val BOX_SZ = 16
private const val BTN_W = 120
private const val BTN_H = 28
private const val BTN_Y = 288
private const val ACCEPT_X = 118
private const val DECLINE_X = 274

// ── palette (matches the TabbedPanel warm-stone look) ────────────────────────────
private const val FONT = 495
private const val GOLD = 0xffcc33
private const val WHITE = 0xffffff
private const val C_FRAME = 0x2b2419
private const val C_BODY = 0x554d3e
private const val C_TITLE = 0x6e6147
private const val C_CLOSE = 0x7a2222
private const val C_PANEL = 0x000000
private const val PANEL_OP = 90
private const val C_BOX = 0x2c2619        // checkbox well (dark)
private const val C_TICK = 0x27a327       // checked fill (green)
private const val C_BEVEL_LIGHT = 0x8c8266
private const val C_BEVEL_DARK = 0x2c2619
private const val C_ACCEPT = 0x2f6b2f     // accept fill (green-ish)
private const val C_DECLINE = 0x7a2222    // decline fill (red)
private const val CLICK_OP1 = 1 shl 1
private val CLICK_ACTIONS = listOf("Select")

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "inspect"
    val cachePath = args.getOrNull(1) ?: "data/cache"
    val gid = args.getOrNull(2)?.toIntOrNull() ?: DUEL_RULES_IFACE
    println("duel-rules iface tool: mode=$mode cache=$cachePath gid=$gid")
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

    comps[ROOT] = layer(0, 0, PANE_W, PANE_H, parent = 0xFFFF, noClickThrough = 1)
    comps[FRAME] = rect(2, 4, 508, 326, C_FRAME)
    comps[BODY] = rect(4, 6, 504, 322, C_BODY)
    comps[TITLE_BAR] = rect(5, 7, 502, 22, C_TITLE)
    comps[TITLE_TXT] = text(5, 11, 502, 16, "", colour = GOLD, xAlign = 1)
    comps[CLOSE_BG] = rect(490, 9, 16, 16, C_CLOSE)
    comps[CLOSE_TXT] = text(490, 11, 16, 14, "X", colour = WHITE, xAlign = 1)
    comps[RULES_PANEL] = rect(10, 54, 492, 206, C_PANEL, opacity = PANEL_OP)
    comps[SUBTITLE_TXT] = text(5, 36, 502, 14, "Click a rule to toggle it. Both players must accept.", colour = WHITE, xAlign = 1)

    // 12 toggle slots (2 cols x 6 rows): checkbox well + hidden green tick + label.
    for (i in 0 until TOGGLES) {
        val x = GRID_COL_X[i / 6]
        val y = GRID_Y0 + (i % 6) * GRID_DY
        comps[BOX_BASE + i] = rect(x, y + (TOGGLE_H - BOX_SZ) / 2, BOX_SZ, BOX_SZ, C_BOX)
        comps[TICK_BASE + i] = rect(x + 3, y + (TOGGLE_H - BOX_SZ) / 2 + 3, BOX_SZ - 6, BOX_SZ - 6, C_TICK, hidden = 1)
        comps[LBL_BASE + i] = text(x + BOX_SZ + 8, y + (TOGGLE_H - 13) / 2, TOGGLE_W - BOX_SZ - 8, 13, "", colour = WHITE)
    }

    comps[STATUS_TXT] = text(5, 266, 502, 14, "", colour = GOLD, xAlign = 1)

    // Accept / Decline buttons (bevel: light edge under dark edge under fill, + centered label).
    comps[ACCEPT_LIGHT] = rect(ACCEPT_X, BTN_Y, BTN_W, BTN_H, C_BEVEL_LIGHT)
    comps[ACCEPT_DARK] = rect(ACCEPT_X + 1, BTN_Y + 1, BTN_W - 1, BTN_H - 1, C_BEVEL_DARK)
    comps[ACCEPT_FILL] = rect(ACCEPT_X + 1, BTN_Y + 1, BTN_W - 2, BTN_H - 2, C_ACCEPT)
    comps[ACCEPT_TXT] = text(ACCEPT_X, BTN_Y + (BTN_H - 13) / 2, BTN_W, 13, "Accept", colour = WHITE, xAlign = 1)
    comps[DECLINE_LIGHT] = rect(DECLINE_X, BTN_Y, BTN_W, BTN_H, C_BEVEL_LIGHT)
    comps[DECLINE_DARK] = rect(DECLINE_X + 1, BTN_Y + 1, BTN_W - 1, BTN_H - 1, C_BEVEL_DARK)
    comps[DECLINE_FILL] = rect(DECLINE_X + 1, BTN_Y + 1, BTN_W - 2, BTN_H - 2, C_DECLINE)
    comps[DECLINE_TXT] = text(DECLINE_X, BTN_Y + (BTN_H - 13) / 2, BTN_W, 13, "Decline", colour = WHITE, xAlign = 1)

    // Clickable hit layers — the ONLY clickable shapes (type-0 + op1 + action).
    comps[CLOSE_HIT] = hitLayer(490, 9, 16, 16)
    for (i in 0 until TOGGLES) {
        val x = GRID_COL_X[i / 6]
        val y = GRID_Y0 + (i % 6) * GRID_DY
        comps[RULE_HIT_BASE + i] = hitLayer(x, y, TOGGLE_W, TOGGLE_H)
    }
    comps[ACCEPT_HIT] = hitLayer(ACCEPT_X, BTN_Y, BTN_W, BTN_H)
    comps[DECLINE_HIT] = hitLayer(DECLINE_X, BTN_Y, BTN_W, BTN_H)

    check(comps.size == LAST_ID + 1 && (0..LAST_ID).all { comps.containsKey(it) }) {
        "component ids not contiguous 0..$LAST_ID (got ${comps.size})"
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
            else -> println("  VERIFY FAIL: component $fid did not round-trip")
        }
    }
    println(if (ok == comps.size) ">>> AUTHORED OK — $ok/${comps.size} components verified." else ">>> AUTHOR VERIFY FAILED ($ok/${comps.size}).")
}

private fun inspect(lib: CacheLibrary, gid: Int) {
    val archive = lib.index(3)?.archive(gid) ?: run { println("no interface $gid"); return }
    println("interface $gid components (id: type geom | clickMask actions):")
    for (fid in archive.fileIds()) {
        val data = lib.data(3, gid, fid) ?: continue
        val r = R(data)
        if (r.u1() != 0xFF) { println("  $fid: if1-format"); continue }
        val type = r.u1(); r.u2()
        val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
        r.u1(); r.u1(); r.u1(); r.u1(); r.u2(); val hidden = r.u1()
        val bodyOk = when (type) {
            0 -> { r.u2(); r.u2(); r.u1(); true }
            3 -> { r.i4(); r.u1(); r.u1(); true }
            4 -> { r.u2(); r.str(); r.u1(); r.u1(); r.u1(); r.u1(); r.i4(); true }
            else -> false
        }
        if (!bodyOk) { println("  $fid: type=$type (body not parsed)"); continue }
        val clickMask = r.u3(); r.str()
        val actions = (0 until r.u1()).map { r.str() }
        println("  $fid: type=$type x=$x y=$y w=$w h=$h hidden=$hidden | clickMask=0x%06x %s".format(clickMask, actions))
    }
}

// ── component builders (the proven recipes, verbatim from PanelCacheTool) ─────────
private fun layer(x: Int, y: Int, w: Int, h: Int, parent: Int, noClickThrough: Int) =
    Comp(type = 0, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = parent, hidden = 0, noClickThrough = noClickThrough)

private fun hitLayer(x: Int, y: Int, w: Int, h: Int) =
    Comp(type = 0, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, noClickThrough = 1,
        clickMask = CLICK_OP1, actions = CLICK_ACTIONS)

private fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int, opacity: Int = 0, hidden: Int = 0) =
    Comp(type = 3, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = hidden, rectColour = colour, filled = 1, opacity = opacity)

private fun text(x: Int, y: Int, w: Int, h: Int, s: String, colour: Int, xAlign: Int = 0) =
    Comp(type = 4, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, font = FONT, text = s, lineHeight = 14,
        xAlign = xAlign, yAlign = 0, shadowed = 1, textColour = colour)

// ── if3 codec (types 0/3/4 — the byte-identical-validated subset) ─────────────────
private class Comp(
    val type: Int, val contentType: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val widthMode: Int, val heightMode: Int, val xMode: Int, val yMode: Int, val parent: Int, val hidden: Int,
    val scrollW: Int = 0, val scrollH: Int = 0, val noClickThrough: Int = 0,
    val rectColour: Int = 0, val filled: Int = 0, val opacity: Int = 0,
    val font: Int = 0, val text: String = "", val lineHeight: Int = 0,
    val xAlign: Int = 0, val yAlign: Int = 0, val shadowed: Int = 0, val textColour: Int = 0,
    val clickMask: Int = 0, val name: String = "", val actions: List<String> = emptyList(),
)

private sealed class Dec {
    class Validated(val comp: Comp) : Dec()
    class Other : Dec()
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
    if (r.u1() != 0xFF) return Dec.Other()
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
        else -> return Dec.Other()
    }
    val clickMask = r.u3(); val name = r.str()
    val actions = (0 until r.u1()).map { r.str() }
    r.u1(); r.u1(); r.u1(); r.str()
    repeat(18) { if (r.u1() != 0) return Dec.Other() }
    repeat(3) { if (r.u1() != 0) return Dec.Other() }
    if (r.rem() != 0) return Dec.Other()
    return Dec.Validated(Comp(type, contentType, x, y, w, h, widthMode, heightMode, xMode, yMode, parent, hidden,
        scrollW, scrollH, noClick, rectColour, filled, opacity, font, txt, lineH, xa, ya, shadow, textColour,
        clickMask, name, actions))
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
    w.u3(c.clickMask); w.str(c.name)
    w.u1(c.actions.size); c.actions.forEach { w.str(it) }
    w.u1(0); w.u1(0); w.u1(0); w.str("")
    repeat(18) { w.u1(0) }
    repeat(3) { w.u1(0) }
    return w.bytes()
}
