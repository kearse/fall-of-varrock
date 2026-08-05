package org.alter.tools.headicons

import com.displee.cache.CacheLibrary
import dev.openrune.cache.SPRITES
import dev.openrune.cache.filestore.Cache
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Loot keys WITHOUT the skull: repaint the overhead **head icons** so a key-carrier shows just
 * the keys above their head, not a skull.
 *
 * The overhead PK icons are frames of the `headicons_pk` sprite group (index [SPRITES]); a
 * player's `skullIcon` id indexes straight into it. Frames 8..12 are the loot-key icons
 * (RuneLite `SkullIcon.LOOT_KEYS_ONE..FIVE`) and the stock art is a white skull with 1..5 keys
 * hanging under it — OSRS has no keys-without-skull sprite at all (unskulled carriers simply
 * show nothing there). This server never uses the icons for anything but loot keys
 * ([org.alter.plugins.content.economy.pk.LootKeys.syncOverhead]), so we repaint them in place:
 * same ids, same rendering path, zero protocol risk.
 *
 * **How the skull is erased.** All frames of a sprite group share one palette and are placed on
 * a common canvas by their per-frame offsetX/offsetY. Frame 0 is the plain white skull — the
 * exact pixels the keyed frames layer the keys onto. So for frames 8..12 we erase every pixel
 * whose canvas position AND palette index match an opaque frame-0 pixel (palette index 0 is the
 * transparent colour). Key pixels that overlap the skull survive because their palette index
 * differs. The edit is **surgical**: raster/alpha bytes are zeroed in place (same length, no
 * re-encode of the group structure), following the MinimapIconsTool byte-edit MO.
 *
 * Every apply is gated: the frame table must parse exactly (pixel blocks must land exactly on
 * the trailer), each keyed frame must both erase a plausible number of pixels (the skull) and
 * keep a plausible number (the keys), and a post-edit re-parse must confirm the mask is gone.
 * `inspect` writes before/after PNGs (12x) to `headicon-sprites/` for eyeballing first.
 *
 * Reads use OpenRune's [Cache] (read-only, proven addressing); writes go through displee
 * [CacheLibrary] like the repo's other cache tools. The cache is shared and streamed to clients
 * over JS5 — restart the server afterwards and clients pull the change on reconnect.
 *
 * Run (workingDir = repo root = the Alter project dir):
 *   gradlew :game-server:headIcons -PheadArgs="inspect"
 *   gradlew :game-server:headIcons -PheadArgs="apply"
 *   gradlew :game-server:headIcons -PheadArgs="restore"
 */

private const val CACHE_PATH = "data/cache"
private const val GROUP_NAME = "headicons_pk"
private const val FLAG_VERTICAL = 0x01
private const val FLAG_ALPHA = 0x02

/** The loot-key frames (1..5 keys) and the plain-skull frame used as the erase mask. */
private const val SKULL_FRAME = 0
private val KEY_FRAMES = 8..12

private class Frame(
    val index: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val setting: Int,
    /** Absolute offset of the first raster byte in the group blob. */
    val rasterPos: Int,
    /** Absolute offset of the first alpha byte, or -1 when the frame has no alpha block. */
    val alphaPos: Int,
) {
    /** Absolute blob offset of pixel (x,y)'s raster byte, honouring the frame's pixel order. */
    fun rasterAt(x: Int, y: Int): Int =
        rasterPos + if (setting and FLAG_VERTICAL != 0) x * height + y else x + y * width

    fun alphaAt(x: Int, y: Int): Int =
        alphaPos + if (setting and FLAG_VERTICAL != 0) x * height + y else x + y * width
}

private class Group(
    val data: ByteArray,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val palette: IntArray,
    val frames: List<Frame>,
    /** Pixel blocks consumed exactly up to the palette/trailer — proof the framing is right. */
    val exactFit: Boolean,
)

private fun u8(d: ByteArray, p: Int) = d[p].toInt() and 0xFF
private fun u16(d: ByteArray, p: Int) = (u8(d, p) shl 8) or u8(d, p + 1)
private fun u24(d: ByteArray, p: Int) = (u8(d, p) shl 16) or (u8(d, p + 1) shl 8) or u8(d, p + 2)

/** Mirror of SpriteDecoder's read layout, kept as absolute offsets so we can edit in place. */
private fun parseGroup(data: ByteArray): Group {
    val size = u16(data, data.size - 2)
    val base = data.size - 7 - size * 8
    val canvasWidth = u16(data, base)
    val canvasHeight = u16(data, base + 2)
    val paletteSize = u8(data, base + 4) + 1

    val offsetsX = IntArray(size) { u16(data, base + 5 + it * 2) }
    val offsetsY = IntArray(size) { u16(data, base + 5 + size * 2 + it * 2) }
    val widths = IntArray(size) { u16(data, base + 5 + size * 4 + it * 2) }
    val heights = IntArray(size) { u16(data, base + 5 + size * 6 + it * 2) }

    val palettePos = base - (paletteSize - 1) * 3
    val palette = IntArray(paletteSize)
    for (i in 1 until paletteSize) {
        palette[i] = u24(data, palettePos + (i - 1) * 3)
        if (palette[i] == 0) palette[i] = 1
    }

    var pos = 0
    val frames = ArrayList<Frame>(size)
    for (i in 0 until size) {
        val setting = u8(data, pos)
        pos++
        val area = widths[i] * heights[i]
        val rasterPos = pos
        pos += area
        val alphaPos = if (setting and FLAG_ALPHA != 0) pos.also { pos += area } else -1
        frames += Frame(i, offsetsX[i], offsetsY[i], widths[i], heights[i], setting, rasterPos, alphaPos)
    }
    return Group(data, canvasWidth, canvasHeight, palette, frames, exactFit = pos == palettePos)
}

/** Canvas-position → palette-index map of the mask frame's opaque pixels. */
private fun skullMask(g: Group): Map<Int, Int> {
    val f = g.frames[SKULL_FRAME]
    val mask = HashMap<Int, Int>()
    for (y in 0 until f.height) {
        for (x in 0 until f.width) {
            val idx = u8(g.data, f.rasterAt(x, y))
            if (idx == 0) continue
            mask[(f.offsetY + y) * g.canvasWidth + (f.offsetX + x)] = idx
        }
    }
    return mask
}

private class EraseStats(val frame: Int, val erased: Int, val kept: Int)

/**
 * Zero out every mask-matching pixel of the keyed frames, in place on a copy of the blob.
 * Returns the edited blob + per-frame counts (the caller decides whether they're sane).
 */
private fun eraseSkull(g: Group): Pair<ByteArray, List<EraseStats>> {
    val out = g.data.copyOf()
    val mask = skullMask(g)
    val stats = ArrayList<EraseStats>()
    for (fi in KEY_FRAMES) {
        val f = g.frames[fi]
        var erased = 0
        var kept = 0
        for (y in 0 until f.height) {
            for (x in 0 until f.width) {
                val rp = f.rasterAt(x, y)
                val idx = u8(out, rp)
                if (idx == 0) continue
                val canvas = (f.offsetY + y) * g.canvasWidth + (f.offsetX + x)
                if (mask[canvas] == idx) {
                    out[rp] = 0
                    if (f.alphaPos != -1) out[f.alphaAt(x, y)] = 0
                    erased++
                } else {
                    kept++
                }
            }
        }
        stats += EraseStats(fi, erased, kept)
    }
    return out to stats
}

private fun render(g: Group, f: Frame): BufferedImage {
    val img = BufferedImage(g.canvasWidth.coerceAtLeast(1), g.canvasHeight.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until f.height) {
        for (x in 0 until f.width) {
            val idx = u8(g.data, f.rasterAt(x, y))
            if (idx == 0) continue
            val alpha = if (f.alphaPos != -1) u8(g.data, f.alphaAt(x, y)) else 0xFF
            val cx = f.offsetX + x
            val cy = f.offsetY + y
            if (cx < img.width && cy < img.height) {
                img.setRGB(cx, cy, (alpha shl 24) or g.palette[idx])
            }
        }
    }
    return img
}

private fun dump(g: Group, f: Frame, dir: File, name: String) {
    val img = render(g, f)
    val scale = 12
    val big = BufferedImage(img.width * scale, img.height * scale, BufferedImage.TYPE_INT_ARGB)
    val gr = big.createGraphics()
    gr.drawImage(img.getScaledInstance(big.width, big.height, java.awt.Image.SCALE_FAST), 0, 0, null)
    gr.dispose()
    val file = File(dir, "$name.png")
    ImageIO.write(big, "png", file)
    println("  wrote ${file.path} (frame ${f.index}: ${f.width}x${f.height} @ ${f.offsetX},${f.offsetY})")
}

private fun loadGroup(): Pair<Int, Group>? {
    val cache = Cache.load(Path.of(CACHE_PATH), false)
    try {
        val groupId = cache.archiveId(SPRITES, GROUP_NAME)
        if (groupId < 0) {
            println("ABORT: sprite group '$GROUP_NAME' not found in index $SPRITES")
            return null
        }
        val data = cache.data(SPRITES, groupId, 0) ?: run {
            println("ABORT: no data for '$GROUP_NAME' (group $groupId)")
            return null
        }
        val g = parseGroup(data)
        if (!g.exactFit) {
            println("ABORT: pixel blocks don't exact-fit the trailer — unsafe to edit (frames=${g.frames.size})")
            return null
        }
        if (g.frames.size <= KEY_FRAMES.last) {
            println("ABORT: '$GROUP_NAME' has only ${g.frames.size} frames — no loot-key icons (need > ${KEY_FRAMES.last})")
            return null
        }
        return groupId to g
    } finally {
        cache.close()
    }
}

/** erased ≈ the skull, kept ≈ the keys: both must be non-trivial or the mask didn't line up. */
private fun sane(stats: List<EraseStats>): Boolean = stats.all { it.erased >= 20 && it.kept >= 10 }

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "inspect"
    println("headicons tool: mode=$mode cache=$CACHE_PATH group='$GROUP_NAME' frames=${KEY_FRAMES.first}..${KEY_FRAMES.last}")
    when (mode) {
        "inspect" -> inspect()
        "apply" -> apply()
        "restore" -> restore()
        else -> println("unknown mode '$mode' (expected: inspect | apply | restore)")
    }
}

private fun inspect() {
    val (_, g) = loadGroup() ?: return
    println("frames=${g.frames.size} canvas=${g.canvasWidth}x${g.canvasHeight} palette=${g.palette.size} exactFit=${g.exactFit}")
    g.frames.forEach { f ->
        println("  frame ${f.index}: ${f.width}x${f.height} @ ${f.offsetX},${f.offsetY} vertical=${f.setting and FLAG_VERTICAL != 0} alpha=${f.alphaPos != -1}")
    }
    val dir = File("headicon-sprites").apply { mkdirs() }
    dump(g, g.frames[SKULL_FRAME], dir, "before_skull_$SKULL_FRAME")
    KEY_FRAMES.forEach { dump(g, g.frames[it], dir, "before_keys_$it") }

    val (edited, stats) = eraseSkull(g)
    stats.forEach { println("  frame ${it.frame}: would erase ${it.erased} skull px, keep ${it.kept} key px") }
    println(if (sane(stats)) "mask lines up — 'apply' would proceed." else "MASK DOES NOT LINE UP — 'apply' would refuse.")
    val preview = parseGroup(edited)
    KEY_FRAMES.forEach { dump(preview, preview.frames[it], dir, "after_keys_$it") }
}

private fun apply() {
    val (groupId, g) = loadGroup() ?: return
    val (edited, stats) = eraseSkull(g)
    stats.forEach { println("frame ${it.frame}: erasing ${it.erased} skull px, keeping ${it.kept} key px") }
    if (!sane(stats)) {
        println("ABORT: erase counts implausible — the skull mask doesn't line up with the keyed frames. Run 'inspect' and eyeball the PNGs.")
        return
    }
    // Re-parse the edited blob and require every masked pixel is really gone.
    val re = parseGroup(edited)
    if (!re.exactFit || eraseSkull(re).second.any { it.erased != 0 }) {
        println("ABORT: post-edit verification failed — not writing.")
        return
    }

    backup()
    val lib = CacheLibrary(CACHE_PATH)
    try {
        val archive = lib.index(SPRITES).archive(groupId) ?: run { println("ABORT: displee can't open group $groupId"); return }
        archive.add(0, edited)
        lib.update()
    } finally {
        lib.close()
    }

    // Verify from disk through the proven read path.
    val after = loadGroup()
    val ok = after != null && eraseSkull(after.second).second.all { it.erased == 0 }
    println(if (ok) "==== done: loot-key head icons are now keys-only. Restart the server; clients pull the change on reconnect. ====" else "VERIFY FAILED after write — run 'restore' to roll back.")
}

private fun backup() {
    val src = File(CACHE_PATH)
    val dst = File(src.parentFile, src.name + "_backup_headicons")
    if (dst.exists()) {
        println("backup already exists at ${dst.path} (left as-is)")
        return
    }
    dst.mkdirs()
    src.listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true) }
    println("backed up cache -> ${dst.path}")
}

private fun restore() {
    val dst = File(CACHE_PATH)
    val src = File(dst.parentFile, dst.name + "_backup_headicons")
    if (!src.exists()) {
        println("no backup at ${src.path} — nothing to restore")
        return
    }
    src.listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true) }
    println("restored cache from ${src.path}")
}
