package dev.openrune.cache.tools

import com.google.gson.GsonBuilder
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.DBROW
import dev.openrune.cache.DBTABLE
import dev.openrune.cache.DBTABLEINDEX
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.buffer.BufferReader
import dev.openrune.cache.filestore.buffer.Reader
import dev.openrune.cache.filestore.definition.data.DBRowType
import dev.openrune.cache.filestore.definition.data.DBTableType
import dev.openrune.cache.filestore.definition.decoder.decodeColumnFields
import dev.openrune.cache.filestore.definition.decoder.readVarInt2
import dev.openrune.cache.util.ScriptVarType
import java.io.File
import java.nio.file.Path

/**
 * **Quest DBTable dump** (READ-ONLY) — the recon step for replacing the OSRS quest tab contents
 * with Fall of Varrock's custom quests (docs/custom-quests.md §3, docs/quest-tab-handoff.md).
 *
 * Rev 228 renders the quest tab (interface 399) client-side from DBTable [QUEST_TABLE_ID]: the
 * table schema lives in configs archive [DBTABLE] (39), one row per quest in [DBROW] (38), and
 * js5 index [DBTABLEINDEX] (21) holds the per-table row indexes the clientscripts use to iterate
 * (archive = tableId; file 0 = master "all rows" index, file N = index on column N-1).
 *
 * This tool decodes all three for the quest table and writes a JSON dump + a stdout summary, so
 * the patch step can be designed against the REAL schema instead of guesses. It never writes to
 * the cache.
 *
 * Run from the Alter root (cache at data/cache by default):
 *   gradlew :plugins:tools:dumpQuestTable                 # -> quest-table-dump.json
 *   gradlew :plugins:tools:dumpQuestTable -Pcache=D:/path/to/cache -Pout=dump.json
 */

private const val QUEST_TABLE_ID = 0

/*
 * Standalone opcode readers, mirroring DBRowDecoder/DBTableDecoder byte-for-byte. The library
 * decoders can't be reused directly: they're final, and their single-definition entry point
 * (loadSingle) reads into an empty map (NPE) while their bulk path needs the global CacheManager.
 */

private fun decodeRow(id: Int, data: ByteArray): DBRowType {
    val def = DBRowType(id)
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

private fun decodeTable(id: Int, data: ByteArray): DBTableType {
    val def = DBTableType(id)
    val buffer: Reader = BufferReader(data)
    while (true) {
        when (val opcode = buffer.readUnsignedByte()) {
            0 -> return def
            1 -> {
                val numColumns = buffer.readUnsignedByte()
                val types = arrayOfNulls<Array<ScriptVarType>>(numColumns)
                var defaults: Array<Array<Any?>?>? = null
                while (true) {
                    val setting = buffer.readUnsignedByte()
                    if (setting == 0xFF) break
                    val columnId = setting and 0x7F
                    val hasDefault = setting and 0x80 != 0
                    val columnTypes = Array(buffer.readUnsignedByte()) { ScriptVarType.forId(buffer.readSmart())!! }
                    types[columnId] = columnTypes
                    if (hasDefault) {
                        if (defaults == null) defaults = arrayOfNulls(types.size)
                        defaults[columnId] = decodeColumnFields(buffer, columnTypes)
                    }
                }
                def.types = types
                def.defaultColumnValues = defaults
            }
            else -> error("Unknown DBTable opcode $opcode")
        }
    }
}

fun main(args: Array<String>) {
    val cachePath = args.getOrNull(0) ?: "data/cache"
    val outPath = args.getOrNull(1) ?: "quest-table-dump.json"

    println("Opening cache at ${File(cachePath).absolutePath} (read-only)")
    val cache = Cache.load(Path.of(cachePath), false)
    val dump = linkedMapOf<String, Any?>("cachePath" to File(cachePath).absolutePath, "questTableId" to QUEST_TABLE_ID)

    // ---- 1. Schema: DBTABLE archive, file = table id -------------------------------------
    val schemaData = cache.data(CONFIGS, DBTABLE, QUEST_TABLE_ID)
    if (schemaData == null) {
        println("!! No DBTable $QUEST_TABLE_ID schema found — wrong cache?")
    } else {
        val schema = decodeTable(QUEST_TABLE_ID, schemaData)
        val columns = linkedMapOf<String, Any?>()
        schema.types?.forEachIndexed { columnId, types ->
            if (types != null) {
                columns["$columnId"] = mapOf(
                    "types" to types.map { it.name },
                    "defaults" to schema.defaultColumnValues?.getOrNull(columnId)?.toList(),
                )
            }
        }
        dump["schema"] = columns
        println("Schema (table $QUEST_TABLE_ID): ${columns.size} typed columns")
        columns.forEach { (id, def) -> println("  column $id -> $def") }
    }

    // ---- 2. Rows: DBROW archive, filtered to the quest table ------------------------------
    val rowsByTable = sortedMapOf<Int, Int>()
    val questRows = mutableListOf<Map<String, Any?>>()
    for (fileId in cache.files(CONFIGS, DBROW)) {
        val data = cache.data(CONFIGS, DBROW, fileId) ?: continue
        val row = runCatching { decodeRow(fileId, data) }.getOrElse {
            println("  !! row $fileId failed to decode: ${it.message}"); continue
        }
        rowsByTable.merge(row.tableId, 1, Int::plus)
        if (row.tableId != QUEST_TABLE_ID) continue

        val columns = linkedMapOf<String, Any?>()
        row.columnTypes?.forEachIndexed { columnId, types ->
            if (types != null) {
                columns["$columnId"] = mapOf(
                    "types" to types.map { it.name },
                    "values" to row.columnValues?.getOrNull(columnId)?.toList(),
                )
            }
        }
        questRows += linkedMapOf("id" to fileId, "columns" to columns)
    }
    dump["rowCountsByTable"] = rowsByTable.mapKeys { "${it.key}" }
    dump["questRows"] = questRows
    println("\nDBRow files by table: $rowsByTable")
    println("Quest rows (table $QUEST_TABLE_ID): ${questRows.size}")
    questRows.take(3).forEach { println("  sample: $it") }

    // ---- 3. Row indexes: DBTABLEINDEX js5 index, archive = table id -----------------------
    val indexedTables = cache.archives(DBTABLEINDEX).toList()
    dump["tablesWithIndexes"] = indexedTables
    println("\nTables with DBTableIndex entries (js5 index $DBTABLEINDEX): $indexedTables")
    val questIndexes = linkedMapOf<String, Any?>()
    if (indexedTables.contains(QUEST_TABLE_ID)) {
        for (fileId in cache.files(DBTABLEINDEX, QUEST_TABLE_ID)) {
            val data = cache.data(DBTABLEINDEX, QUEST_TABLE_ID, fileId) ?: continue
            val label = if (fileId == 0) "master" else "column${fileId - 1}"
            questIndexes[label] = runCatching { decodeTableIndex(data) }.getOrElse { "decode failed: ${it.message}" }
        }
    }
    dump["questTableIndexes"] = questIndexes
    questIndexes.forEach { (name, idx) -> println("  index $name -> ${summarize(idx)}") }

    cache.close()

    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    File(outPath).writeText(gson.toJson(dump))
    println("\nFull dump written to ${File(outPath).absolutePath}")
    println("Hand that file (or its contents) to the quest-tab patch step — see docs/quest-tab-handoff.md.")
}

/**
 * DBTableIndex file format (mirrors RuneLite's DBTableIndexLoader): varint tupleCount, then per
 * tuple a BaseVarType byte (0 int / 1 long / 2 string), varint valueCount, and per value the
 * typed key + varint rowCount + that many varint row ids.
 */
private fun decodeTableIndex(data: ByteArray): Map<String, Any?> {
    val r = BufferReader(data)
    val tupleSize = r.readVarInt2()
    val tuples = mutableListOf<Map<String, Any?>>()
    repeat(tupleSize) {
        val typeId = r.readUnsignedByte()
        val valueCount = r.readVarInt2()
        val values = linkedMapOf<String, List<Int>>()
        repeat(valueCount) {
            val key: Any = when (typeId) {
                0 -> r.readInt()
                1 -> r.readLong()
                2 -> r.readString()
                else -> error("unknown BaseVarType $typeId")
            }
            val rowCount = r.readVarInt2()
            values["$key"] = List(rowCount) { r.readVarInt2() }
        }
        tuples += mapOf("type" to typeId, "values" to values)
    }
    return mapOf("tupleCount" to tupleSize, "tuples" to tuples)
}

private fun summarize(index: Any?): String {
    if (index !is Map<*, *>) return "$index"
    val tuples = index["tuples"] as? List<*> ?: return "$index"
    return tuples.joinToString("; ") { tuple ->
        val t = tuple as Map<*, *>
        val values = t["values"] as Map<*, *>
        val rowTotal = values.values.sumOf { (it as List<*>).size }
        "type=${t["type"]} keys=${values.size} rowRefs=$rowTotal"
    }
}
