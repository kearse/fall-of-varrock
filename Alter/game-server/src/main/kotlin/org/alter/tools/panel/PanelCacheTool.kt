package org.alter.tools.panel

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Authors the **reusable tabbed-panel** interface into the cache (index 3, group [PANEL_IFACE]).
 *
 * Generic, content-agnostic version of the proven teleport/siege if3 codec (types 0/3/4). The
 * server-side driver (`org.alter.plugins.content.interfaces.panel.TabbedPanel`) fills the
 * title/tabs/rows at runtime; any feature (collection log, loadouts, menus) reuses one interface.
 *
 * **Clickability** (learned by inspecting stock interface 387's working buttons): a clickable
 * element is a **type-0 layer** with `clickMask=op1 (2)` AND **≥1 action string** — NOT a text
 * component, and runtime IfSetEvents alone is not enough. So each tab/row/close has a transparent
 * **hit layer** on top of its visual text; the server binds onButton to the hit layer id.
 *
 * Component ids MUST stay CONTIGUOUS (a gap = a null component the client NPEs on while drawing)
 * and in sync with `TabbedPanel`.
 *
 * Run with the server STOPPED (cache file lock):
 *   gradlew :game-server:panelIface -PpanelArgs="build"     # author
 *   gradlew :game-server:panelIface -PpanelArgs="inspect data/cache <gid>"   # dump geometry
 */

// ── component ids (KEEP IN SYNC with TabbedPanel.kt) ─────────────────────────────
// Group MUST be < client interface count (1102 → ids 0..1101). `lib.put` never deletes old files
// and changed bytes don't reliably re-fetch on the client, so BUMP the group on every layout
// change + the player full-restarts the client. History: 1002 gappy→NPE, 1003 contiguous (worked,
// not clickable), 1004 baked op1 on TEXT (still not clickable — wrong recipe), 1005 = layer hitboxes.
const val PANEL_IFACE = 1011
private const val ROOT = 0
private const val FRAME_OUTER = 1      // near-black outer edge
private const val BODY_TEX = 2         // type-5 sprite 297 (classic OSRS stone texture), tiled
private const val TAB_COL_PANEL = 3    // translucent dark panel over the texture (tab column)
private const val LIST_PANEL = 4       // translucent dark panel over the texture (list area)
private const val TITLE_BAR = 5
private const val TITLE_TXT = 6
private const val CLOSE_BG = 7
private const val CLOSE_TXT = 8
private const val HDR_BASE = 9         // 9,10,11 = three column headers
private const val HDR_COLS = 3
private const val TAB_LIGHT_BASE = 12  // 12..20 bevel: light top-left edge
private const val TAB_DARK_BASE = 21   // 21..29 bevel: dark bottom-right edge
private const val TAB_FILL_BASE = 30   // 30..38 button fill (unselected)
private const val TAB_SEL_BASE = 39    // 39..47 selected fill (hidden, shown when active)
private const val TAB_TXT_BASE = 48    // 48..56 tab labels
private const val TAB_COUNT = 9
private const val ROW_ICON_BASE = 57   // 57..68 item-icon cells (type-5 graphic)
private const val ROW_TXT_BASE = 69    // 69..104 (12 rows x 3 cols, visual text)
private const val ROWS = 12
private const val COLS = 3
private const val CLOSE_HIT = 105      // close-button hit layer (clickable)
private const val TAB_HIT_BASE = 106   // 106..114 tab hit layers (clickable)
private const val ROW_HIT_BASE = 115   // 115..126 row hit layers (clickable)
// last id = 126 → 127 components, contiguous 0..126.
private const val BG_SPRITE = 297      // classic OSRS interface stone background (bank/equipment use it)

// ── geometry (authored against the fixed 512x334 main-screen pane) ───────────────
private const val PANE_W = 512
private const val PANE_H = 334
private const val ROW_H = 22
private const val ROW_Y0 = 58
private const val ICON_W = 18         // item-icon cell size
private val COL_X = intArrayOf(152, 350, 432)   // col0 = icon anchor; text sits at +ICON gap
private val COL_W = intArrayOf(190, 80, 70)
private const val NAME_DX = 22        // name text offset past the icon
private const val TAB_X = 12
private const val TAB_W = 124
private const val TAB_Y0 = 46
private const val TAB_DY = 30
private const val TAB_H = 26

// ── brighter warm palette (Roat-feel) over a classic OSRS stone texture ───────────
private const val FONT = 495
private const val GOLD = 0xffcc33      // title + headers
private const val WHITE = 0xffffff
private const val GREY = 0xd8d0c0      // inactive tab label (warm light grey)
private const val C_FRAME = 0x2b2419   // outer edge (dark brown)
private const val C_BODY = 0x554d3e    // window body (brighter warm stone)
private const val C_TITLE = 0x6e6147   // title bar (warm tan)
private const val C_CLOSE = 0x7a2222   // close button (red)
private const val C_PANEL = 0x000000   // tab/list panels: black drawn translucent (darkens texture)
private const val PANEL_OP = 90        // panel opacity (0=opaque, 255=invisible)
private const val C_BEVEL_LIGHT = 0x8c8266 // button bevel: light top-left edge
private const val C_BEVEL_DARK = 0x2c2619  // button bevel: dark bottom-right edge
private const val C_TAB_FILL = 0x5b5340    // button fill (unselected)
private const val C_TAB_SEL = 0x7e7252     // selected button fill (lighter)
private const val TEX_OP = 0           // background texture opacity (0 = fully opaque stone)
private const val CLICK_OP1 = 1 shl 1 // op1 (== InterfaceEvent.ClickOp1, 2)
private val CLICK_ACTIONS = listOf("Select")

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "inspect"
    val cachePath = args.getOrNull(1) ?: "data/cache"
    val gid = args.getOrNull(2)?.toIntOrNull() ?: PANEL_IFACE
    println("panel-iface tool: mode=$mode cache=$cachePath gid=$gid")
    val lib = CacheLibrary(cachePath)
    try {
        when (mode) {
            "build" -> build(lib, gid)
            "inspect" -> inspect(lib, gid)
            "validate" -> validate(lib)
            "scan" -> scan(lib, args.getOrNull(2) ?: "loot")
            "listeners" -> listeners(lib, gid)
            else -> println("unknown mode '$mode' (expected: build | inspect | validate | scan | listeners)")
        }
    } finally {
        lib.close()
    }
}

private fun build(lib: CacheLibrary, gid: Int) {
    val comps = LinkedHashMap<Int, Comp>()

    comps[ROOT] = layer(0, 0, PANE_W, PANE_H, parent = 0xFFFF, noClickThrough = 1)
    comps[FRAME_OUTER] = rect(2, 4, 508, 326, C_FRAME)        // dark outer edge (1px border feel)
    // NOTE: baked sprites (type-5 with spriteId>=0) NPE the client's draw for our custom interface
    // (only rects/text/item-icons render) — so the "texture" is a flat warm stone-coloured rect.
    comps[BODY_TEX] = rect(4, 6, 504, 322, C_BODY)            // warm stone body (flat)
    comps[TAB_COL_PANEL] = rect(6, 32, 134, 294, C_PANEL, opacity = PANEL_OP) // darker panel under tabs
    comps[LIST_PANEL] = rect(144, 32, 364, 294, C_PANEL, opacity = PANEL_OP)  // darker panel under list
    comps[TITLE_BAR] = rect(5, 7, 502, 22, C_TITLE)
    comps[TITLE_TXT] = text(5, 11, 502, 16, "", colour = GOLD, xAlign = 1)
    comps[CLOSE_BG] = rect(490, 9, 16, 16, C_CLOSE)           // close button (rect — sprites crash)
    comps[CLOSE_TXT] = text(490, 11, 16, 14, "X", colour = WHITE, xAlign = 1)

    // Headers: name col header sits past the icon gap; other cols at their x.
    comps[HDR_BASE + 0] = text(COL_X[0] + NAME_DX, 42, COL_W[0] - NAME_DX, 14, "", colour = GOLD)
    comps[HDR_BASE + 1] = text(COL_X[1], 42, COL_W[1], 14, "", colour = GOLD)
    comps[HDR_BASE + 2] = text(COL_X[2], 42, COL_W[2], 14, "", colour = GOLD)

    // 3D-beveled tab buttons: a light rect (top-left edge) under a dark rect (offset +1 → bottom-right
    // edge) under the fill, plus a hidden lighter "selected" fill, plus the label.
    for (t in 0 until TAB_COUNT) {
        val y = TAB_Y0 + t * TAB_DY
        comps[TAB_LIGHT_BASE + t] = rect(TAB_X, y, TAB_W, TAB_H, C_BEVEL_LIGHT)
        comps[TAB_DARK_BASE + t] = rect(TAB_X + 1, y + 1, TAB_W - 1, TAB_H - 1, C_BEVEL_DARK)
        comps[TAB_FILL_BASE + t] = rect(TAB_X + 1, y + 1, TAB_W - 2, TAB_H - 2, C_TAB_FILL)
        comps[TAB_SEL_BASE + t] = rect(TAB_X + 1, y + 1, TAB_W - 2, TAB_H - 2, C_TAB_SEL, hidden = 1)
        comps[TAB_TXT_BASE + t] = text(TAB_X + 8, y + (TAB_H - 13) / 2, TAB_W - 12, 13, "", colour = GREY)
    }

    // Per row: an item-icon cell (type-5 graphic, filled via setComponentItem) + 3 text cells.
    for (i in 0 until ROWS) {
        val y = ROW_Y0 + i * ROW_H
        comps[ROW_ICON_BASE + i] = iconCell(COL_X[0], y + (ROW_H - ICON_W) / 2, ICON_W, ICON_W)
        val ty = y + (ROW_H - 13) / 2
        comps[ROW_TXT_BASE + i * COLS + 0] = text(COL_X[0] + NAME_DX, ty, COL_W[0] - NAME_DX, 13, "", colour = WHITE)
        comps[ROW_TXT_BASE + i * COLS + 1] = text(COL_X[1], ty, COL_W[1], 13, "", colour = WHITE)
        comps[ROW_TXT_BASE + i * COLS + 2] = text(COL_X[2], ty, COL_W[2], 13, "", colour = WHITE)
    }
    // Hit layers (transparent, clickable) — these are what the server binds onButton to.
    comps[CLOSE_HIT] = hitLayer(490, 9, 16, 16)
    for (t in 0 until TAB_COUNT) {
        comps[TAB_HIT_BASE + t] = hitLayer(TAB_X, TAB_Y0 + t * TAB_DY, TAB_W, TAB_H)
    }
    val rowSpanW = COL_X[COLS - 1] + COL_W[COLS - 1] - COL_X[0]
    for (i in 0 until ROWS) {
        comps[ROW_HIT_BASE + i] = hitLayer(COL_X[0], ROW_Y0 + i * ROW_H, rowSpanW, ROW_H)
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

/**
 * Round-trip every real component in index 3 (decode → encode) and confirm byte-identical. Proves
 * our if3 codec — especially the new type-5 (graphic) body — matches the client's exactly BEFORE
 * we author anything. Components with non-empty listeners/triggers are decoded as Skip (we don't
 * author those) and not counted. Reports pass/fail per component type.
 */
private fun validate(lib: CacheLibrary) {
    val index = lib.index(3) ?: run { println("no interface index (3)!"); return }
    val pass = HashMap<Int, Int>(); val fail = HashMap<Int, Int>(); val skip = HashMap<String, Int>()
    val failExamples = ArrayList<String>()
    for (gid in index.archiveIds()) {
        val archive = index.archive(gid) ?: continue
        for (fid in archive.fileIds()) {
            val data = lib.data(3, gid, fid) ?: continue
            when (val d = decode(data)) {
                is Dec.Validated -> {
                    val re = encode(d.comp)
                    val t = d.comp.type
                    if (re.contentEquals(data)) pass[t] = (pass[t] ?: 0) + 1
                    else {
                        fail[t] = (fail[t] ?: 0) + 1
                        if (failExamples.size < 8) failExamples += "  MISMATCH $gid:$fid type=$t (${data.size}b vs ${re.size}b)"
                    }
                }
                is Dec.Consume -> skip["leftover"] = (skip["leftover"] ?: 0) + 1
                is Dec.Skip -> skip[d.reason] = (skip[d.reason] ?: 0) + 1
            }
        }
    }
    println("=== round-trip validation (decode→encode byte-identical) ===")
    (pass.keys + fail.keys).distinct().sorted().forEach { t ->
        println("  type $t: pass=${pass[t] ?: 0} fail=${fail[t] ?: 0}")
    }
    failExamples.forEach(::println)
    println("  (skipped, not authored by us: ${skip.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}=${it.value}" }})")
    val totalFail = fail.values.sum()
    println(if (totalFail == 0) ">>> CODEC OK — all decodable components round-trip byte-identical (type 5 included)." else ">>> CODEC FAIL — $totalFail mismatches.")
}

/** Find stock interfaces by baked strings: search every group's raw component bytes for [needle]
 *  (case-insensitive, ISO-8859-1) — locates e.g. the OSRS loot chest UI without knowing its id. */
private fun scan(lib: CacheLibrary, needle: String) {
    val index = lib.index(3) ?: run { println("no interface index (3)!"); return }
    val n = needle.lowercase()
    var hits = 0
    for (gid in index.archiveIds()) {
        val archive = index.archive(gid) ?: continue
        for (fid in archive.fileIds()) {
            val data = lib.data(3, gid, fid) ?: continue
            val s = String(data, Charsets.ISO_8859_1).lowercase()
            var at = s.indexOf(n)
            while (at >= 0) {
                // Pull the full readable string around the hit for context.
                var start = at; while (start > 0 && s[start - 1].code in 32..126) start--
                var end = at; while (end < s.length && s[end].code in 32..126) end++
                println("  $gid:$fid @$at \"${String(data, start, end - start, Charsets.ISO_8859_1)}\"")
                hits++
                at = s.indexOf(n, end)
            }
        }
    }
    println(if (hits == 0) "no matches for \"$needle\"" else ">>> $hits match(es) for \"$needle\"")
}

/**
 * Dump every component's cs2 LISTENERS + transmit triggers (RuneLite decodeIf3 layout: after the
 * common trailer come 18 listeners — each u1 argCount, then per arg u1 kind (0=int i4 / 1=string)
 * with the FIRST arg being the script id — then 3 trigger lists (u1 count × i4): varTransmit,
 * invTransmit, statTransmit). The invTransmit list names the CONTAINER id a stock UI watches.
 */
private fun listeners(lib: CacheLibrary, gid: Int) {
    val archive = lib.index(3)?.archive(gid) ?: run { println("no interface $gid"); return }
    val names = listOf(
        "onLoad", "onMouseOver", "onMouseLeave", "onTargetLeave", "onTargetEnter", "onVarTransmit",
        "onInvTransmit", "onStatTransmit", "onTimer", "onOp", "onMouseRepeat", "onClick",
        "onClickRepeat", "onRelease", "onHold", "onDrag", "onDragComplete", "onScroll",
    )
    for (fid in archive.fileIds()) {
        val data = lib.data(3, gid, fid) ?: continue
        val r = R(data)
        if (r.u1() != 0xFF) { println("  $fid: if1-format"); continue }
        val type = r.u1(); r.u2(); r.s2(); r.s2(); r.u2(); r.u2()
        r.u1(); r.u1(); r.u1(); r.u1(); r.u2(); r.u1()
        val bodyOk = when (type) {
            0 -> { r.u2(); r.u2(); r.u1(); true }
            3 -> { r.i4(); r.u1(); r.u1(); true }
            4 -> { r.u2(); r.str(); r.u1(); r.u1(); r.u1(); r.u1(); r.i4(); true }
            5 -> { r.i4(); r.u2(); r.u1(); r.u1(); r.u1(); r.i4(); r.u1(); r.u1(); true }
            9 -> { r.u1(); r.i4(); r.u1(); true }
            else -> false
        }
        if (!bodyOk) { println("  $fid: type=$type (body not parsed — listeners unknown)"); continue }
        r.u3(); r.str()
        repeat(r.u1()) { r.str() }
        r.u1(); r.u1(); r.u1(); r.str()
        val parts = ArrayList<String>()
        try {
            for (li in 0 until 18) {
                val count = r.u1()
                if (count == 0) continue
                val args = (0 until count).map { if (r.u1() == 0) r.i4().toString() else "\"${r.str()}\"" }
                parts += "${names[li]}=[${args.joinToString(",")}]"
            }
            val trigNames = listOf("varTransmit", "invTransmit", "statTransmit")
            for (ti in 0 until 3) {
                val count = r.u1()
                if (count == 0) continue
                parts += "${trigNames[ti]}=[${(0 until count).joinToString(",") { r.i4().toString() }}]"
            }
        } catch (e: Exception) {
            parts += "(parse error: ${e.message})"
        }
        if (parts.isNotEmpty()) println("  $fid (type=$type): ${parts.joinToString(" ")}")
    }
}

private fun inspect(lib: CacheLibrary, gid: Int) {
    val archive = lib.index(3)?.archive(gid) ?: run { println("no interface $gid"); return }
    println("interface $gid components (id: type geom | clickMask actionCount actions):")
    for (fid in archive.fileIds()) {
        val data = lib.data(3, gid, fid) ?: continue
        val r = R(data)
        if (r.u1() != 0xFF) { println("  $fid: if1-format"); continue }
        val type = r.u1(); r.u2()
        val x = r.s2(); val y = r.s2(); val w = r.u2(); val h = r.u2()
        r.u1(); r.u1(); r.u1(); r.u1(); r.u2(); r.u1()
        var spriteInfo = ""
        val bodyOk = when (type) {
            0 -> { r.u2(); r.u2(); r.u1(); true }
            3 -> { r.i4(); r.u1(); r.u1(); true }
            4 -> { r.u2(); r.str(); r.u1(); r.u1(); r.u1(); r.u1(); r.i4(); true }
            5 -> {
                val sid = r.i4(); val tex = r.u2(); val tile = r.u1(); val op = r.u1()
                val bt = r.u1(); val sc = r.i4(); val fv = r.u1(); val fh = r.u1()
                spriteInfo = " spriteId=$sid tex=$tex tile=$tile op=$op border=$bt shadow=$sc flip=$fv/$fh"; true
            }
            else -> false
        }
        if (!bodyOk) { println("  $fid: type=$type x=$x y=$y w=$w h=$h (body not parsed)"); continue }
        val clickMask = r.u3(); r.str()
        val actionCount = r.u1()
        val actions = (0 until actionCount).map { r.str() }
        println("  $fid: type=$type x=$x y=$y w=$w h=$h | clickMask=0x%06x acts=%d %s%s".format(clickMask, actionCount, actions, spriteInfo))
    }
}

// ── component builders ───────────────────────────────────────────────────────────
private fun layer(x: Int, y: Int, w: Int, h: Int, parent: Int, noClickThrough: Int) =
    Comp(type = 0, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = parent, hidden = 0, noClickThrough = noClickThrough)

/** Transparent clickable hit layer — the proven recipe: type-0 layer + op1 + one action. */
private fun hitLayer(x: Int, y: Int, w: Int, h: Int) =
    Comp(type = 0, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, noClickThrough = 1,
        clickMask = CLICK_OP1, actions = CLICK_ACTIONS)

/** Item-icon cell — type-5 graphic with no baked sprite (spriteId=-1); filled at runtime via
 *  setComponentItem (IfSetObject). **Authored HIDDEN**: a type-5 graphic with spriteId=-1 and no
 *  item set NPEs the client's draw, so it must stay hidden until the server sets an item AND then
 *  reveals it (TabbedPanel.selectTab). Border/shadow copied from the stock price-guide icon (464:8). */
private fun iconCell(x: Int, y: Int, w: Int, h: Int) =
    Comp(type = 5, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 1,
        spriteId = -1, borderType = 1, shadowColor = 0x333333)

/** A clickable sprite button — type-5 graphic with a baked sprite + op1 + an action (the proven
 *  clickable recipe). Used for the close-X (sprite 541). A baked sprite draws fine (unlike the
 *  empty spriteId=-1 icon cells), so it stays visible. */
private fun clickableSprite(x: Int, y: Int, w: Int, h: Int, sprite: Int) =
    Comp(type = 5, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, spriteId = sprite,
        clickMask = CLICK_OP1, actions = listOf("Close"))

/** Tiled background texture — type-5 graphic with a baked sprite drawn repeated over the area. */
private fun texture(x: Int, y: Int, w: Int, h: Int, sprite: Int, op: Int) =
    Comp(type = 5, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0,
        spriteId = sprite, spriteTiling = 1, spriteOpacity = op)

private fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int, opacity: Int = 0, hidden: Int = 0) =
    Comp(type = 3, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = hidden, rectColour = colour, filled = 1, opacity = opacity)

private fun text(x: Int, y: Int, w: Int, h: Int, s: String, colour: Int, xAlign: Int = 0) =
    Comp(type = 4, contentType = 0, x = x, y = y, w = w, h = h, widthMode = 0, heightMode = 0,
        xMode = 0, yMode = 0, parent = ROOT, hidden = 0, font = FONT, text = s, lineHeight = ROW_H,
        xAlign = xAlign, yAlign = 0, shadowed = 1, textColour = colour)

// ── if3 codec (types 0/3/4, now WITH action strings) ──────────────────────────────
private class Comp(
    val type: Int, val contentType: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val widthMode: Int, val heightMode: Int, val xMode: Int, val yMode: Int, val parent: Int, val hidden: Int,
    val scrollW: Int = 0, val scrollH: Int = 0, val noClickThrough: Int = 0,
    val rectColour: Int = 0, val filled: Int = 0, val opacity: Int = 0,
    val font: Int = 0, val text: String = "", val lineHeight: Int = 0,
    val xAlign: Int = 0, val yAlign: Int = 0, val shadowed: Int = 0, val textColour: Int = 0,
    val clickMask: Int = 0, val name: String = "", val actions: List<String> = emptyList(),
    val dragDeadZone: Int = 0, val dragDeadTime: Int = 0, val dragRender: Int = 0, val targetVerb: String = "",
    // type 5 (graphic)
    val spriteId: Int = -1, val textureId: Int = 0, val spriteTiling: Int = 0, val spriteOpacity: Int = 0,
    val borderType: Int = 0, val shadowColor: Int = 0, val flipV: Int = 0, val flipH: Int = 0,
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
    var spriteId = -1; var textureId = 0; var spriteTiling = 0; var spriteOpacity = 0
    var borderType = 0; var shadowColor = 0; var flipV = 0; var flipH = 0
    when (type) {
        0 -> { scrollW = r.u2(); scrollH = r.u2(); noClick = r.u1() }
        3 -> { rectColour = r.i4(); filled = r.u1(); opacity = r.u1() }
        4 -> { font = r.u2(); txt = r.str(); lineH = r.u1(); xa = r.u1(); ya = r.u1(); shadow = r.u1(); textColour = r.i4() }
        5 -> { spriteId = r.i4(); textureId = r.u2(); spriteTiling = r.u1(); spriteOpacity = r.u1(); borderType = r.u1(); shadowColor = r.i4(); flipV = r.u1(); flipH = r.u1() }
        else -> return Dec.Skip("type-$type")
    }
    val clickMask = r.u3(); val name = r.str()
    val actionCount = r.u1()
    val actions = (0 until actionCount).map { r.str() }
    val dragDeadZone = r.u1(); val dragDeadTime = r.u1(); val dragRender = r.u1()
    val targetVerb = r.str()
    repeat(18) { if (r.u1() != 0) return Dec.Skip("has-listener") }
    repeat(3) { if (r.u1() != 0) return Dec.Skip("has-trigger") }
    if (r.rem() != 0) return Dec.Consume(r.rem())
    return Dec.Validated(Comp(type, contentType, x, y, w, h, widthMode, heightMode, xMode, yMode, parent, hidden,
        scrollW, scrollH, noClick, rectColour, filled, opacity, font, txt, lineH, xa, ya, shadow, textColour,
        clickMask, name, actions, dragDeadZone, dragDeadTime, dragRender, targetVerb,
        spriteId, textureId, spriteTiling, spriteOpacity, borderType, shadowColor, flipV, flipH))
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
        5 -> { w.i4(c.spriteId); w.u2(c.textureId); w.u1(c.spriteTiling); w.u1(c.spriteOpacity); w.u1(c.borderType); w.i4(c.shadowColor); w.u1(c.flipV); w.u1(c.flipH) }
    }
    w.u3(c.clickMask); w.str(c.name)
    w.u1(c.actions.size); c.actions.forEach { w.str(it) }
    w.u1(c.dragDeadZone); w.u1(c.dragDeadTime); w.u1(c.dragRender); w.str(c.targetVerb)
    repeat(18) { w.u1(0) }
    repeat(3) { w.u1(0) }
    return w.bytes()
}
