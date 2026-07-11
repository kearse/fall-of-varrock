package org.alter.tools.quests

import com.displee.cache.CacheLibrary
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.DBROW
import dev.openrune.cache.filestore.buffer.BufferReader
import dev.openrune.cache.filestore.buffer.BufferWriter
import dev.openrune.cache.filestore.buffer.Reader
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
 * This phase only rewrites the STRING name columns (1 = sort name, 2 = displayed name). It does NOT
 * change the row set or any indexed column, so **no DBTableIndex rebuild is needed** and the risk is
 * minimal — the other OSRS quests still list unchanged. Deleting them and listing only FoV quests is
 * Phase 2 (a full row-set replace + index-21 rebuild — see docs/quest-tab-handoff.md).
 *
 *   gradlew :game-server:questTable                              # inspect (read-only)
 *   gradlew :game-server:questTable -PquestArgs="inspect"
 *   gradlew :game-server:questTable -PquestArgs="relabel"        # back up + rename the rows
 *   gradlew :game-server:questTable -PquestArgs="restore"        # undo from the backups
 *   gradlew :game-server:questTable -PquestArgs="relabel D:/path/to/cache"
 *
 * After `relabel`, restart the server so it serves the edited cache, log in, and watch the
 * "Recruit Trials" row track a fresh account (red → yellow as you start → green on completion).
 */

private const val CACHE_PATH = "data/cache"
private const val BACKUP_DIR = "data/cache-backups"

private const val COL_SORT_NAME = 1 // hidden sort key (list orders by this within a category)
private const val COL_DISPLAY_NAME = 2 // the name shown in the quest tab (DBTableID.Quest.NAME)

/**
 * The relabel plan. `dbrowId` is the DBROW file id (from the dump); `questId` is its col0 (the id
 * `QUEST_STATUS_GET` keys on, kept only for reference). `sortName` is prefixed with an order digit
 * so our quests list in quest-line order; `displayName` is what players see. The `varp` is the
 * reused quest's progress varp the server drives (kept in lock-step with [QuestJournal]).
 *
 * Only the two LIVE quests are relabelled for the proof — the future teasers stay as their OSRS
 * rows (a "coming soon" quest showing red "not started" would just mislead). Their mappings are
 * documented in docs/quest-tab-handoff.md for Phase 2.
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
)

fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "inspect"
    val cachePath = args.getOrNull(1) ?: CACHE_PATH
    println("quest-table tool: mode=$mode cache=${File(cachePath).absolutePath}")
    when (mode) {
        "inspect" -> inspect(cachePath)
        "relabel" -> relabel(cachePath)
        "restore" -> restore(cachePath)
        else -> println("usage: inspect | relabel | restore  [cachePath]")
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
