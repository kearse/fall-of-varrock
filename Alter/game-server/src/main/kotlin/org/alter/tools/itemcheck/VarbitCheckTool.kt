package org.alter.tools.itemcheck

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import java.nio.file.Path

/**
 * Throwaway audit helper: print varbit definitions (parent varp + bit range), and list every
 * varbit that lives inside a given varp. Needed because writing a RAW value to a varp silently
 * clobbers every varbit stored in it (found via the loot chest's status varp 1299 — see
 * content/economy/pk/LootChestInterface).
 *
 * Run (workingDir = repo root):
 *   gradlew :game-server:varbitCheck -PvarbitArgs="varbit 4842"     — print one varbit's def
 *   gradlew :game-server:varbitCheck -PvarbitArgs="varp 1299"      — list all varbits in a varp
 */
private const val CACHE_PATH = "data/cache"
private const val REVISION = 228

fun main(args: Array<String>) {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)
    val mode = args.getOrNull(0) ?: "varp"
    val id = args.getOrNull(1)?.toIntOrNull() ?: 1299
    when (mode) {
        "varbit" -> {
            val d = runCatching { CacheManager.getVarbit(id) }.getOrNull()
            if (d == null) println("varbit $id: (no def)")
            else println("varbit $id: varp=${d.varp} bits ${d.startBit}..${d.endBit}")
        }
        "varp" -> {
            println("varbits stored in varp $id:")
            var found = 0
            for (vb in 0 until 30000) {
                val d = runCatching { CacheManager.getVarbit(vb) }.getOrNull() ?: continue
                if (d.varp == id) {
                    println("  varbit $vb: bits ${d.startBit}..${d.endBit}")
                    found++
                }
            }
            if (found == 0) println("  (none — the varp is a plain value)")
        }
        // Find every enum whose values (or keys) contain the given int — e.g. which enum maps
        // the loot chest's tabs to key item ids.
        "enums-containing" -> {
            for (e in 0 until 30000) {
                val d = runCatching { CacheManager.getEnum(e) }.getOrNull() ?: continue
                val hit = d.values.any { (k, v) -> k == id || (v as? Int) == id }
                if (hit) println("enum $e: ${d.values.toList().take(12)}")
            }
        }
        "enum" -> {
            val d = runCatching { CacheManager.getEnum(id) }.getOrNull()
            if (d == null) println("enum $id: (no def)") else println("enum $id: ${d.values.toList()}")
        }
        // Disassemble a clientscript (index 12) and every proc it gosubs into (opcode 40),
        // printing each script's int-constant pool — reveals which enums/invs/items/varbits the
        // UI logic references without a full decompiler. args: script-dis <scriptId>
        "script-dis" -> {
            val cache = Cache.load(Path.of(CACHE_PATH), false)
            val verbose = args.contains("-v")
            val seen = LinkedHashSet<Int>()
            fun dis(sid: Int) {
                if (!seen.add(sid)) return
                val data = cache.data(12, sid) ?: run { println("script $sid: no data"); return }
                // OSRS cs2 container: trailer = [12-byte header][switch tables][u2 switchLength]
                var off = data.size - 2
                fun u1(i: Int) = data[i].toInt() and 0xff
                fun u2(i: Int) = (u1(i) shl 8) or u1(i + 1)
                fun i4(i: Int) = (u1(i) shl 24) or (u1(i + 1) shl 16) or (u1(i + 2) shl 8) or u1(i + 3)
                val switchLength = u2(off)
                val endIdx = data.size - 2 - switchLength - 12
                // instruction stream starts after the leading null-terminated script name
                var pos = 0
                while (u1(pos) != 0) pos++
                pos++
                val ops = mutableListOf<Pair<Int, Any?>>()
                while (pos < endIdx) {
                    val op = u2(pos); pos += 2
                    when {
                        op == 3 -> { // SCONST
                            val sb = StringBuilder()
                            while (u1(pos) != 0) { sb.append(u1(pos).toChar()); pos++ }
                            pos++
                            ops += op to sb.toString()
                        }
                        op < 100 && op != 21 && op != 38 && op != 39 -> { ops += op to i4(pos); pos += 4 }
                        else -> { ops += op to u1(pos); pos += 1 }
                    }
                }
                val consts = ops.filter { it.first == 0 }.map { it.second }
                val gosubs = ops.filter { it.first == 40 }.map { it.second as Int }
                val strings = ops.filter { it.first == 3 }.map { it.second }
                println("script $sid (${data.size}b, ${ops.size} ops): intConsts=$consts gosubs=$gosubs strings=$strings")
                if (verbose) println("  ops: " + ops.joinToString(" ") { "(${it.first}:${it.second})" })
                gosubs.forEach { dis(it) }
            }
            dis(id)
        }
        // Sweep EVERY clientscript for int constants matching the given values — finds which
        // script drives a UI (e.g. the loot chest tab builder) when no component hook names it.
        // args: script-sweep <int> <int> ...
        "script-sweep" -> {
            val cache = Cache.load(Path.of(CACHE_PATH), false)
            val needles = args.drop(1).mapNotNull { it.toIntOrNull() }.toSet()
            var scanned = 0
            for (sid in 0 until 12000) {
                val data = runCatching { cache.data(12, sid) }.getOrNull() ?: continue
                if (data.size < 16) continue
                scanned++
                runCatching {
                    fun u1(i: Int) = data[i].toInt() and 0xff
                    fun u2(i: Int) = (u1(i) shl 8) or u1(i + 1)
                    fun i4(i: Int) = (u1(i) shl 24) or (u1(i + 1) shl 16) or (u1(i + 2) shl 8) or u1(i + 3)
                    val switchLength = u2(data.size - 2)
                    val endIdx = data.size - 2 - switchLength - 12
                    var pos = 0
                    while (u1(pos) != 0) pos++
                    pos++
                    val hits = mutableSetOf<Int>()
                    while (pos < endIdx) {
                        val op = u2(pos); pos += 2
                        when {
                            op == 3 -> { while (u1(pos) != 0) pos++; pos++ }
                            op < 100 && op != 21 && op != 38 && op != 39 -> {
                                val v = i4(pos); pos += 4
                                if (op == 0 && v in needles) hits += v
                            }
                            else -> pos += 1
                        }
                    }
                    if (hits.isNotEmpty()) println("script $sid: consts $hits")
                }
            }
            println("(scanned $scanned scripts)")
        }
    }
}
