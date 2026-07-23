package org.alter.tools.quests

import com.displee.cache.CacheLibrary
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.DBROW
import dev.openrune.cache.DBTABLEINDEX
import dev.openrune.cache.filestore.buffer.BufferReader
import dev.openrune.cache.filestore.buffer.BufferWriter
import dev.openrune.cache.filestore.buffer.Reader
import dev.openrune.cache.filestore.buffer.Writer
import dev.openrune.cache.filestore.definition.data.DBRowType
import dev.openrune.cache.filestore.definition.decoder.decodeColumnFields
import dev.openrune.cache.filestore.definition.decoder.readVarInt2
import dev.openrune.cache.filestore.definition.encoder.DBRowEncoder
import dev.openrune.cache.util.ScriptVarType
import java.io.File

/**
 * **Quest tab — Phase 1 relabel (safe proof).** Renames a handful of OSRS quest rows in the cache's
 * quest DBTable (table 0) to Fall of Varrock's custom quests, so the stock quest tab (interface 399)
 * shows OUR quest names, coloured by OUR progress.
 *
 * Why relabel instead of a full rebuild: the rev-228 quest-list clientscript colours each row by
 * `QUEST_STATUS_GET(<col0 quest id>)`, which resolves that quest's progress varp — it is NOT stored
 * in the table (there is no varbit column; verified in the dump). So we can't point a row at one of
 * our own varps without editing compiled cs2. Instead we REUSE a simple OSRS quest whose varp the
 * server can freely drive ([QuestJournal.RECRUIT_QUEST_VARP] etc.): relabel its row's name columns
 * and mirror our quest state into its varp → the stock tab colours it red/yellow/green for free.
 *
 * `relabel` only rewrites the STRING name columns (1 = sort name, 2 = displayed name) — no row set
 * or indexed column changes, so the other OSRS quests still list. **Phase 2 (`hide`)** then prunes
 * the quest table's master row index down to just our rows, so the tab lists ONLY FoV quests (the
 * other rows are hidden, not deleted — fully reversible). See [hide].
 *
 *   gradlew :game-server:questTable -PquestArgs="inspect"        # read-only
 *   gradlew :game-server:questTable -PquestArgs="relabel"        # back up + rename the rows
 *   gradlew :game-server:questTable -PquestArgs="hide"           # list ONLY our quests
 *   gradlew :game-server:questTable -PquestArgs="restore"        # undo the relabels
 *   gradlew :game-server:questTable -PquestArgs="relabel D:/path/to/cache"
 *
 * After each write, restart the server so it serves the edited cache. On the live VPS this all runs
 * through the "Quest cache relabel" GitHub Actions workflow (docs/quest-tab-handoff.md).
 */

private const val CACHE_PATH = "data/cache"
private const val BACKUP_DIR = "data/cache-backups"

private const val QUEST_TABLE_ID = 0 // DBTable id the quest tab renders (also its DBTABLEINDEX archive)
private const val MASTER_INDEX_FILE = 0 // DBTABLEINDEX file 0 = the "all rows" master the list iterates

private const val COL_SORT_NAME = 1 // hidden sort key (list orders by this within a category)
private const val COL_DISPLAY_NAME = 2 // the name shown in the quest tab (DBTableID.Quest.NAME)

/**
 * The relabel plan. `dbrowId` is the DBROW file id (from the dump); `questId` is its col0 (the id
 * `QUEST_STATUS_GET` keys on, kept only for reference). `sortName` is prefixed with an order digit
 * so our quests list in quest-line order; `displayName` is what players see. The `varp` is the
 * reused quest's progress varp the server drives (kept in lock-step with [QuestJournal]).
 *
 * The LIVE, server-driven quests are relabelled — the still-unbuilt teasers (War-Prep II/III)
 * stay as their OSRS rows (a "coming soon" quest showing red "not started" would just mislead). Their
 * mappings are documented in docs/quest-tab-handoff.md for Phase 2. "The Rogue Problem" (Act II)
 * reuses the still-unbuilt War-Prep II — Ranged slot (The Restless Ghost, varp 107) — it drives the
 * custom-client Journal today and now the native tab too, coloured by [QuestJournal.syncNativeTab].
 */
private data class Relabel(
    val dbrowId: Int,
    val questId: Int,
    val sortName: String,
    val displayName: String,
    val varp: Int,
)

private val PLAN = listOf(
    Relabel(dbrowId = 17, questId = 1, sortName = "1 Recruit Trials", displayName = "Recruit Trials", varp = 29),
    Relabel(dbrowId = 30, questId = 11, sortName = "2 War-Prep I - Magic", displayName = "War-Prep I - Magic", varp = 31),
    // The Rogue Problem (Act II) reuses The Restless Ghost (dbrow 120, quest id 3, varp 107, driven by
    // QuestJournal from RogueProblem.step) — the War-Prep II — Ranged reuse slot, spare while Ranged is
    // unbuilt. Sort digit 3 places it after War-Prep I and before King of Lumbridge.
    Relabel(dbrowId = 120, questId = 3, sortName = "3 The Rogue Problem", displayName = "The Rogue Problem", varp = 107),
    // King of Lumbridge (endgame conquest) reuses Witch's Potion (varp 67, driven by QuestJournal from
    // Conquest.step). Sort digit 5 keeps it last in the quest-line order.
    Relabel(dbrowId = 161, questId = 13, sortName = "5 King of Lumbridge", displayName = "King of Lumbridge", varp = 67),
)

/** The only quest rows the tab should list after `hide` — exactly the ones we relabelled. */
private val KEPT: Set<Int> = PLAN.map { it.dbrowId }.toSet()

fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "inspect"
    // Everything after the mode is the cache path, rejoined with spaces — the Gradle task splits
    // -PquestArgs on spaces, so a Windows path like "C:\Program Files (x86)\..." arrives in pieces.
    val cachePath = if (args.size > 1) args.drop(1).joinToString(" ") else CACHE_PATH
    println("quest-table tool: mode=$mode cache=${File(cachePath).absolutePath}")
    if (!File(cachePath, "main_file_cache.dat2").exists()) {
        println("!! No cache at that path (looked for main_file_cache.dat2). A fresh git clone has an")
        println("   empty data/cache — point at your install cache, e.g.:")
        println("   gradlew :game-server:questTable -PquestArgs=\"relabel C:/Program Files (x86)/Kearse RSPS/Alter/data/cache\"")
        return
    }
    when (mode) {
        "inspect" -> inspect(cachePath)
        "relabel" -> relabel(cachePath)
        "restore" -> restore(cachePath)
        "hide" -> hide(cachePath)
        else -> println("usage: inspect | relabel | restore | hide  [cachePath]")
    }
}

private fun inspect(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        for (r in PLAN) {
            val data = readRow(lib, r.dbrowId)
            if (data == null) { println("  row ${r.dbrowId}: MISSING"); continue }
            val row = decodeRow(data)
            println("  dbrow ${r.dbrowId} (questId ${r.questId}, varp ${r.varp}): " +
                "sort='${str(row, COL_SORT_NAME)}' display='${str(row, COL_DISPLAY_NAME)}'  ->  will become '${r.displayName}'")
        }
    } finally {
        lib.close()
    }
}

private fun relabel(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        val archive = lib.index(CONFIGS).archive(DBROW) ?: run { println("ABORT: no DBROW archive"); return }
        for (r in PLAN) {
            val original = archive.file(r.dbrowId)?.data
            if (original == null) { println("ABORT: no existing DBROW ${r.dbrowId}"); continue }

            // one-time backup of the pristine record
            val backup = File(BACKUP_DIR, "dbrow_${r.dbrowId}.bin")
            if (!backup.exists()) {
                backup.parentFile.mkdirs()
                backup.writeBytes(original)
                println("backed up dbrow ${r.dbrowId} (${original.size} bytes) -> $backup")
            }

            val row = decodeRow(original)
            if (!hasStringColumn(row, COL_SORT_NAME) || !hasStringColumn(row, COL_DISPLAY_NAME)) {
                println("ABORT: dbrow ${r.dbrowId} lacks name columns — unexpected schema, skipping")
                continue
            }
            setString(row, COL_SORT_NAME, r.sortName)
            setString(row, COL_DISPLAY_NAME, r.displayName)

            val writer = BufferWriter(4096)
            with(DBRowEncoder()) { writer.encode(row) }
            archive.add(r.dbrowId, writer.toArray())
            println("relabelled dbrow ${r.dbrowId} -> '${r.displayName}'")
        }
        lib.update()
    } finally {
        lib.close()
    }
    verify(cachePath)
}

private fun verify(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        var ok = true
        for (r in PLAN) {
            val data = readRow(lib, r.dbrowId)
            if (data == null) {
                ok = false
                println("VERIFY FAIL: dbrow ${r.dbrowId} missing")
                continue
            }
            val display = str(decodeRow(data), COL_DISPLAY_NAME)
            if (display == r.displayName) {
                println("VERIFY ok: dbrow ${r.dbrowId} display='$display'")
            } else {
                ok = false
                println("VERIFY FAIL: dbrow ${r.dbrowId} display='$display' (expected '${r.displayName}')")
            }
        }
        println(if (ok) "OK — quest rows relabelled. Restart the server to serve the edited cache." else "VERIFY FAILED — run 'restore' to roll back.")
    } finally {
        lib.close()
    }
}

private fun restore(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        val archive = lib.index(CONFIGS).archive(DBROW) ?: run { println("ABORT: no DBROW archive"); return }
        for (r in PLAN) {
            val backup = File(BACKUP_DIR, "dbrow_${r.dbrowId}.bin")
            if (!backup.exists()) { println("  no backup for dbrow ${r.dbrowId} at $backup"); continue }
            archive.add(r.dbrowId, backup.readBytes())
            println("restored dbrow ${r.dbrowId} from backup")
        }
        lib.update()
    } finally {
        lib.close()
    }
    println("restore complete. Restart the server to serve the restored cache.")
}

// --- DBROW read / decode / edit -------------------------------------------------------------

private fun readRow(lib: CacheLibrary, dbrowId: Int): ByteArray? =
    lib.index(CONFIGS).archive(DBROW)?.file(dbrowId)?.data

/** Standalone DBRow decoder, mirroring the library's DBRowDecoder opcodes (kept tool-local so we
 *  don't need the global CacheManager, whose bulk decode path we can't reuse here). */
private fun decodeRow(data: ByteArray): DBRowType {
    val def = DBRowType(0)
    val buffer: Reader = BufferReader(data)
    while (true) {
        when (val opcode = buffer.readUnsignedByte()) {
            0 -> return def
            3 -> {
                val numColumns = buffer.readUnsignedByte()
                val types = arrayOfNulls<Array<ScriptVarType>?>(numColumns)
                val values = arrayOfNulls<Array<Any?>?>(numColumns)
                while (true) {
                    val columnId = buffer.readUnsignedByte()
                    if (columnId == 0xFF) break
                    val columnTypes = Array(buffer.readUnsignedByte()) { ScriptVarType.forId(buffer.readSmart())!! }
                    types[columnId] = columnTypes
                    values[columnId] = decodeColumnFields(buffer, columnTypes)
                }
                def.columnTypes = types
                def.columnValues = values
            }
            4 -> def.tableId = buffer.readVarInt2()
            else -> error("Unknown DBRow opcode $opcode")
        }
    }
}

private fun hasStringColumn(row: DBRowType, col: Int): Boolean {
    val types = row.columnTypes ?: return false
    val t = types.getOrNull(col) ?: return false
    return t.size == 1 && t[0] == ScriptVarType.STRING
}

private fun str(row: DBRowType, col: Int): String? =
    row.columnValues?.getOrNull(col)?.getOrNull(0) as? String

private fun setString(row: DBRowType, col: Int, value: String) {
    val values = row.columnValues ?: return
    values[col] = arrayOf<Any?>(value)
}

// --- Phase 2: list ONLY our quests, by pruning the quest table's master row index ----------

/**
 * **hide** — make the quest tab list only [KEPT] (our relabelled rows), hiding the ~196 OSRS quests.
 *
 * The rev-228 quest list enumerates rows via the quest table's **master index** (js5 index
 * [DBTABLEINDEX], archive [QUEST_TABLE_ID], file [MASTER_INDEX_FILE]) — one key mapping to every row
 * id. We simply rewrite that row list down to [KEPT]. The other rows' data is left intact (not
 * deleted), so this is fully reversible and the per-column indexes stay valid for lookups; they're
 * just no longer reachable from the list. Backs up the original master index and verifies by
 * re-decode.
 */
private fun hide(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        val data = lib.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(MASTER_INDEX_FILE)?.data
            ?: run { println("ABORT: no quest master index (idx $DBTABLEINDEX / archive $QUEST_TABLE_ID / file $MASTER_INDEX_FILE)"); return }

        val backup = File(BACKUP_DIR, "questindex_${QUEST_TABLE_ID}_$MASTER_INDEX_FILE.bin")
        if (!backup.exists()) {
            backup.parentFile.mkdirs()
            backup.writeBytes(data)
            println("backed up quest master index (${data.size} bytes) -> $backup")
        }

        val tuples = decodeIndex(data)
        var before = 0
        var after = 0
        for (t in tuples) {
            val iter = t.values.iterator()
            while (iter.hasNext()) {
                val rows = iter.next().second
                before += rows.size
                rows.retainAll { it in KEPT }
                after += rows.size
                if (rows.isEmpty()) iter.remove()
            }
        }
        println("quest master index: $before row refs -> $after (keeping ${KEPT.sorted()})")
        lib.put(DBTABLEINDEX, QUEST_TABLE_ID, MASTER_INDEX_FILE, encodeIndex(tuples))
        lib.update()
    } finally {
        lib.close()
    }

    // verify: the master must now enumerate exactly KEPT
    val lib2 = CacheLibrary(cachePath)
    try {
        val data = lib2.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(MASTER_INDEX_FILE)?.data
            ?: run { println("VERIFY FAIL: master index missing after write"); return }
        val rows = decodeIndex(data).flatMap { t -> t.values.flatMap { it.second } }.toSet()
        if (rows == KEPT) {
            println("OK — quest tab now lists only ${KEPT.sorted()}. Restart the server to serve it.")
        } else {
            println("VERIFY: master now lists $rows (expected $KEPT) — run 'restore' if this is wrong.")
        }
    } finally {
        lib2.close()
    }
}

/** One tuple of a DBTableIndex file: a key type and its (key -> row ids) entries. */
private class IndexTuple(val type: Int, val values: MutableList<Pair<Any, MutableList<Int>>>)

/** Decode a DBTableIndex file (mirrors RuneLite's DBTableIndexLoader / the dump tool). */
private fun decodeIndex(data: ByteArray): List<IndexTuple> {
    val r: Reader = BufferReader(data)
    val tupleCount = r.readVarInt2()
    val tuples = ArrayList<IndexTuple>(tupleCount)
    repeat(tupleCount) {
        val type = r.readUnsignedByte()
        val valueCount = r.readVarInt2()
        val values = ArrayList<Pair<Any, MutableList<Int>>>(valueCount)
        repeat(valueCount) {
            val key: Any = when (type) {
                0 -> r.readInt()
                1 -> r.readLong()
                2 -> r.readString()
                else -> error("unknown DBTableIndex key type $type")
            }
            val rowCount = r.readVarInt2()
            val rows = ArrayList<Int>(rowCount)
            repeat(rowCount) { rows.add(r.readVarInt2()) }
            values.add(key to rows)
        }
        tuples.add(IndexTuple(type, values))
    }
    return tuples
}

/** Re-encode a DBTableIndex file (inverse of [decodeIndex]). */
private fun encodeIndex(tuples: List<IndexTuple>): ByteArray {
    val w = BufferWriter(4096)
    w.writeVarInt2(tuples.size)
    for (t in tuples) {
        w.writeByte(t.type)
        w.writeVarInt2(t.values.size)
        for ((key, rows) in t.values) {
            when (t.type) {
                0 -> w.writeInt(key as Int)
                1 -> w.writeLong(key as Long)
                2 -> w.writeString(key as String)
            }
            w.writeVarInt2(rows.size)
            for (row in rows) w.writeVarInt2(row)
        }
    }
    return w.toArray()
}

/** LEB128 unsigned varint — the write side of [readVarInt2]. */
private fun Writer.writeVarInt2(value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        writeByte((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    writeByte(v and 0x7F)
}
