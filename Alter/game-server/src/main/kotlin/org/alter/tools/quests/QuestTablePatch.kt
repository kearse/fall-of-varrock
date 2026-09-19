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
 * or indexed column changes, so the other OSRS quests still list. HIDING the other ~180 OSRS rows
 * is the custom client's job (`lofquests` / `LofQuestTab`): RuneLite's per-row QuestFilter script
 * asks the client whether to show each row, and ours answers "hide" for every row that isn't in
 * [PLAN]. That is robust to however the quest-list clientscript enumerates rows — the earlier
 * cache-side [hide] (pruning the master row index) is NOT: on the live cache it left the tab
 * listing two rows. `hide` is kept only as a legacy action; [unhide] puts the full row list back.
 *
 * `free` clears the members flag (column 5, indexed) on our reused rows that were members' quests
 * (Recruitment Drive, Death Plateau, Dwarf Cannon, Wanted!, Tree Gnome Village) and moves them in the column-5 index,
 * so every FoV quest lists under the one "Free Quests" header instead of four sitting under
 * "Members' Quests".
 *
 *   gradlew :game-server:questTable -PquestArgs="inspect"        # read-only
 *   gradlew :game-server:questTable -PquestArgs="sync"           # unhide + relabel + free, in one go
 *   gradlew :game-server:questTable -PquestArgs="relabel"        # back up + rename the rows
 *   gradlew :game-server:questTable -PquestArgs="unhide"         # full row list back (undo `hide`)
 *   gradlew :game-server:questTable -PquestArgs="free"           # our members' rows -> Free Quests
 *   gradlew :game-server:questTable -PquestArgs="restore"        # undo the relabels
 *   gradlew :game-server:questTable -PquestArgs="hide"           # LEGACY — prune the master index
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
private const val COL_MEMBERS = 5 // BOOLEAN members flag — the tab groups rows into Free / Members' Quests by it

/**
 * The relabel plan. `dbrowId` is the DBROW file id (from the dump); `questId` is its col0 (the id
 * `QUEST_STATUS_GET` keys on, kept only for reference). `sortName` is prefixed with an order digit
 * so our quests list in quest-line order; `displayName` is what players see. The `varp` is the
 * reused quest's progress varp the server drives (kept in lock-step with [QuestJournal]).
 *
 * All LIVE, server-driven quests are relabelled. Act II is TWO rows off one server chain:
 * "Rogue Hunting I" (the hunt) reuses The Restless Ghost (varp 107) and "Rogue Hunting II" (the
 * Rogue Knight ladder) reuses The Knight's Sword (varp 122); "War-Prep II — Ranged" reuses Imp
 * Catcher (varp 160); "War-Prep III — Survival" reuses Sheep Shearer (varp 179). The legacy chains
 * are driven by [QuestJournal] (`LegacyChains`); framework quests by `QuestEngine.publish` from
 * their `QuestDefinition.nativeTabVarp`. Sort names are TWO-digit strings ("01".."15") so the
 * lexicographic sort keeps quest-line order past nine rows. Mappings for any future quests:
 * docs/quest-tab-handoff.md.
 */
private data class Relabel(
    val dbrowId: Int,
    val questId: Int,
    val sortName: String,
    val displayName: String,
    val varp: Int,
)

private val PLAN = listOf(
    // The Last Free City (Main Story Quest 1 — the Recruit Trials chain under its story name) reuses
    // Cook's Assistant (dbrow 17, quest id 1, varp 29, complete 2). Renamed 2026-09-11: re-run
    // `relabel` (the workflow) so the live tab picks up the new name.
    // Sort keys are TWO-digit strings: the tab orders rows by this STRING, so "10 …" would sort before
    // "2 …" — every row is zero-padded and the relabel rewrites them all anyway.
    Relabel(dbrowId = 17, questId = 1, sortName = "01 The Last Free City", displayName = "The Last Free City", varp = 29),
    Relabel(dbrowId = 30, questId = 11, sortName = "02 War-Prep I - Magic", displayName = "War-Prep I - Magic", varp = 31),
    // Rogue Hunting I (Act II's 30-rogue hunt; formerly listed as "The Rogue Problem") reuses The
    // Restless Ghost (dbrow 120, quest id 3, varp 107, driven by QuestJournal from RogueProblem.step —
    // complete the moment the hunt clears). Sort key 03 places it after War-Prep I.
    Relabel(dbrowId = 120, questId = 3, sortName = "03 Rogue Hunting I", displayName = "Rogue Hunting I", varp = 107),
    // Rogue Hunting II (the Rogue Knight ladder — complete when every camp is broken) reuses The
    // Knight's Sword (dbrow 83, quest id 14, varp 122, complete 7). Same server chain, windowed:
    // QuestJournal drives it locked until the hunt clears, complete at RogueProblem DONE.
    Relabel(dbrowId = 83, questId = 14, sortName = "04 Rogue Hunting II", displayName = "Rogue Hunting II", varp = 122),
    // War-Prep II — Ranged reuses Imp Catcher (dbrow 76, quest id 9, varp 160, complete 2) — a fresh
    // slot, since the rogue quests took Ranged's old Restless-Ghost mapping. Driven from WarPrepRanged.step.
    Relabel(dbrowId = 76, questId = 9, sortName = "05 War-Prep II - Ranged", displayName = "War-Prep II - Ranged", varp = 160),
    // War-Prep III — Survival reuses Sheep Shearer (dbrow 131, quest id 5, varp 179, complete 21).
    // Driven from WarPrepSurvival.step.
    Relabel(dbrowId = 131, questId = 5, sortName = "06 War-Prep III - Survival", displayName = "War-Prep III - Survival", varp = 179),
    // King of Lumbridge (endgame conquest) reuses Witch's Potion (varp 67, driven by QuestJournal from
    // Conquest.step). Sort key 07 keeps it last of the legacy hallway.
    Relabel(dbrowId = 161, questId = 13, sortName = "07 King of Lumbridge", displayName = "King of Lumbridge", varp = 67),
    // The North (Main Story Quest 3 — a framework quest, chain index 7) reuses Ernest the Chicken
    // (dbrow 44, quest id 7, varp 32, complete 3). Driven by QuestEngine.publish from
    // TheNorth.nativeTabVarp. Framework story quests append after the hallway in story order.
    Relabel(dbrowId = 44, questId = 7, sortName = "08 The North", displayName = "The North", varp = 32),
    // First Reclamation (Main Story Quest 4, framework quest) reuses Romeo & Juliet (dbrow 121, quest
    // id 4, varp 144, complete 100) — driven by QuestDefinition.nativeTabVarp through QuestEngine.publish.
    Relabel(dbrowId = 121, questId = 4, sortName = "09 First Reclamation", displayName = "First Reclamation", varp = 144),
    // A Kingdom Alone (Main Story Quest 5, framework quest `a_kingdom_alone`) reuses Rune Mysteries
    // (dbrow 125, quest id 53, varp 63, complete 6). Driven by QuestEngine.publish (nativeTabVarp).
    Relabel(dbrowId = 125, questId = 53, sortName = "10 A Kingdom Alone", displayName = "A Kingdom Alone", varp = 63),
    // The regional phase's four strategic objectives (opened by A Kingdom Alone; each completed by
    // its regional campaign's payoff). Red / yellow / green = not yet open / open / solved.
    Relabel(dbrowId = 10, questId = 12, sortName = "11 BREACH - Asgarnia", displayName = "BREACH - Asgarnia", varp = 130),          // Black Knights' Fortress, complete 4
    Relabel(dbrowId = 112, questId = 10, sortName = "12 SECURE - Morytania", displayName = "SECURE - Morytania", varp = 273),      // Prince Ali Rescue, complete 110
    Relabel(dbrowId = 155, questId = 8, sortName = "13 UNDERSTAND - Wilderness", displayName = "UNDERSTAND - Wilderness / Desert", varp = 178), // Vampyre Slayer, complete 3
    Relabel(dbrowId = 108, questId = 16, sortName = "14 SUSTAIN - Kandarin", displayName = "SUSTAIN - Kandarin / War Effort", varp = 71),      // Pirate's Treasure, complete 4
    // At the White Wall (Asgarnia — BREACH, Quest 1; a framework quest, chain index 14) reuses
    // Recruitment Drive (dbrow 118, quest id 86, varp 657, complete 2) — the OSRS quest whose start
    // NPC is Sir Amik Varze himself. Driven by QuestEngine.publish from AtTheWhiteWall.nativeTabVarp.
    // Sort key 15 — the regional campaign quests follow the objectives, campaign by campaign.
    Relabel(dbrowId = 118, questId = 86, sortName = "15 At the White Wall", displayName = "At the White Wall", varp = 657),
    // A Matter of Trolls (Asgarnia — BREACH, quest 2; framework quest, chain index 15) reuses Death
    // Plateau (dbrow 23, quest id 58, varp 314, complete 80) — on-theme, driven by
    // QuestDefinition.nativeTabVarp through QuestEngine.publish. Sort keys are slot+1.
    Relabel(dbrowId = 23, questId = 58, sortName = "16 A Matter of Trolls", displayName = "A Matter of Trolls", varp = 314),
    // The Guns of Asgarnia (Asgarnia — BREACH, quest 3; framework quest `guns_of_asgarnia`, chain 16)
    // reuses Dwarf Cannon (dbrow 35, quest id 47, varp 0, complete 11) — Nulodion's own row. Driven
    // by QuestEngine.publish (nativeTabVarp).
    Relabel(dbrowId = 35, questId = 47, sortName = "17 The Guns of Asgarnia", displayName = "The Guns of Asgarnia", varp = 0),
    // Old Wounds (Asgarnia — BREACH, Quest 4; framework quest `old_wounds`, chain index 17) reuses
    // Wanted! (dbrow 156, quest id 92, varp 1051, complete 11 — that quest's own start NPC is Sir
    // Tiffy Cashien). Driven by QuestEngine.publish from OldWounds.nativeTabVarp.
    Relabel(dbrowId = 156, questId = 92, sortName = "18 Old Wounds", displayName = "Old Wounds", varp = 1051),
    // First March (Main Story Quest 2; framework quest `first_march`, chain index 18 — built after
    // the quests around it, so it lists in build order like the client chain) reuses Tree Gnome
    // Village (dbrow 150, quest id 32, varp 111, complete 9 — a members' row; `free` moves it under
    // Free Quests). Driven by QuestEngine.publish from FirstMarch.nativeTabVarp.
    Relabel(dbrowId = 150, questId = 32, sortName = "19 First March", displayName = "First March", varp = 111),
)

/** The quest rows we relabelled (the only rows the client lets the tab show; `hide`'s legacy keep-set). */
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
        "dump" -> dump(cachePath)
        "relabel" -> relabel(cachePath)
        "restore" -> restore(cachePath)
        "unhide" -> unhide(cachePath)
        "free" -> free(cachePath)
        "sync" -> {
            // The one-click path: full row list, our names, one Free Quests header. Each step
            // verifies itself; the client (lofquests) hides every row that isn't ours.
            unhide(cachePath)
            relabel(cachePath)
            free(cachePath)
        }
        "hide" -> {
            println("!! 'hide' is LEGACY: pruning the master row index does not reliably control what the")
            println("   quest list shows (the live tab ended up with two rows). Hiding is done by the custom")
            println("   client now — run 'unhide' (or 'sync') instead. Proceeding anyway.")
            hide(cachePath)
        }
        else -> println("usage: inspect | sync | relabel | unhide | free | restore | hide(legacy)  [cachePath]")
    }
}

/**
 * **dump** — read-only listing of EVERY row in the quest table: dbrow id, quest id, display name
 * and every integer column. The varp a quest's progress lives in is one of those columns, which is
 * what makes this the authoritative place to look it up rather than guessing from the wiki.
 *
 * Added 2026-09-18 while answering "spell book still locked behind a quest so spells don't light
 * up": the standard spellbook greys Ardougne/Watchtower/Trollheim/Ape Atoll/Kourend teleports and
 * Iban Blast / Magic Dart / the higher enchants against their quests' progress varps, and this
 * server has no such quests, so those varps sit at 0 forever. Pinning them needs the real ids.
 */
private fun dump(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        val ids = questRowIds(lib)
        println("quest table rows: ${ids.size}")
        println("dbrow | questId | display name                          | int columns (col=value)")
        for (id in ids) {
            val data = readRow(lib, id) ?: continue
            val row = decodeRow(data)
            val ints = (0 until (row.columnValues?.size ?: 0)).mapNotNull { c ->
                int(row, c)?.let { "$c=$it" }
            }.joinToString(" ")
            println("%-5d | %-7s | %-37s | %s".format(id, int(row, 0) ?: "-", str(row, COL_DISPLAY_NAME) ?: "-", ints))
        }
    } finally {
        lib.close()
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
                "sort='${str(row, COL_SORT_NAME)}' display='${str(row, COL_DISPLAY_NAME)}' members=${int(row, COL_MEMBERS)}" +
                "  ->  will become '${r.displayName}'")
        }
        val master = lib.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(MASTER_INDEX_FILE)?.data
        if (master == null) {
            println("  quest master index: MISSING")
        } else {
            val listed = decodeIndex(master).flatMap { t -> t.values.flatMap { it.second } }
            val all = questRowIds(lib)
            println("  quest master index lists ${listed.size} rows of ${all.size} in the table" +
                (if (listed.toSet() != all.toSet()) "  (PRUNED — run 'unhide' or 'sync')" else ""))
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
        // `free` rewrote the members-column index too; put its pristine copy back if we have one.
        val membersIndex = File(BACKUP_DIR, "questindex_${QUEST_TABLE_ID}_${COL_MEMBERS + 1}.bin")
        if (membersIndex.exists()) {
            lib.put(DBTABLEINDEX, QUEST_TABLE_ID, COL_MEMBERS + 1, membersIndex.readBytes())
            println("restored column-$COL_MEMBERS index from backup")
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

private fun int(row: DBRowType, col: Int): Int? =
    row.columnValues?.getOrNull(col)?.getOrNull(0) as? Int

private fun hasIntColumn(row: DBRowType, col: Int, type: ScriptVarType): Boolean {
    val t = row.columnTypes?.getOrNull(col) ?: return false
    return t.size == 1 && t[0] == type
}

private fun setInt(row: DBRowType, col: Int, value: Int) {
    val values = row.columnValues ?: return
    values[col] = arrayOf<Any?>(value)
}

/** Every DBROW file that belongs to the quest table, ascending — what the pristine master index lists. */
private fun questRowIds(lib: CacheLibrary): List<Int> {
    val archive = lib.index(CONFIGS).archive(DBROW) ?: return emptyList()
    return archive.fileIds()
        .filter { id -> archive.file(id)?.data?.let { decodeRow(it).tableId == QUEST_TABLE_ID } == true }
        .sorted()
}

// --- unhide: the full quest row list back in the master index (undoes the legacy `hide`) ----

/**
 * **unhide** — rewrite the quest table's master row index to list EVERY row of the table again
 * (the pristine master is exactly "all table-0 rows, ascending": verified against the dump). Needs
 * no backup file: the list is regenerated from the rows themselves, so it also repairs a partial
 * or stale prune. Hiding the OSRS rows from the tab is the custom client's job now.
 */
private fun unhide(cachePath: String) {
    val lib = CacheLibrary(cachePath)
    try {
        val current = lib.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(MASTER_INDEX_FILE)?.data
            ?: run { println("ABORT: no quest master index (idx $DBTABLEINDEX / archive $QUEST_TABLE_ID / file $MASTER_INDEX_FILE)"); return }
        val tuples = decodeIndex(current)
        val all = questRowIds(lib)
        val listed = tuples.flatMap { t -> t.values.flatMap { it.second } }
        if (listed.toSet() == all.toSet()) {
            println("quest master index already lists all ${all.size} rows — nothing to do")
            return
        }
        // The master has one tuple (int key 0 -> every row); keep whatever key it carries.
        val key: Any = tuples.firstOrNull()?.values?.firstOrNull()?.first ?: 0
        val type = tuples.firstOrNull()?.type ?: 0
        val rebuilt = listOf(IndexTuple(type, mutableListOf(key to all.toMutableList())))
        println("quest master index: ${listed.size} row refs -> ${all.size} (full row list restored)")
        lib.put(DBTABLEINDEX, QUEST_TABLE_ID, MASTER_INDEX_FILE, encodeIndex(rebuilt))
        lib.update()
    } finally {
        lib.close()
    }

    val lib2 = CacheLibrary(cachePath)
    try {
        val data = lib2.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(MASTER_INDEX_FILE)?.data
            ?: run { println("VERIFY FAIL: master index missing after write"); return }
        val rows = decodeIndex(data).flatMap { t -> t.values.flatMap { it.second } }
        val all = questRowIds(lib2)
        if (rows == all) println("OK — quest master index lists all ${all.size} quest rows again. Restart the server to serve it.")
        else println("VERIFY FAIL: master lists ${rows.size} rows, expected ${all.size}")
    } finally {
        lib2.close()
    }
}

// --- free: our reused members' rows under the "Free Quests" header --------------------------

/**
 * **free** — clear the members flag (column [COL_MEMBERS], a BOOLEAN the tab groups by) on the
 * [PLAN] rows that were members' quests, and move them from key 1 to key 0 in that column's index
 * (DBTABLEINDEX file = column + 1), so the tab shows every FoV quest under one "Free Quests"
 * header. Both halves are kept consistent; each row's pristine bytes are backed up first (same
 * files `restore` reads) and the index file alongside them.
 */
private fun free(cachePath: String) {
    val indexFile = COL_MEMBERS + 1
    val lib = CacheLibrary(cachePath)
    try {
        val archive = lib.index(CONFIGS).archive(DBROW) ?: run { println("ABORT: no DBROW archive"); return }
        val indexData = lib.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(indexFile)?.data
            ?: run { println("ABORT: no column-$COL_MEMBERS index (idx $DBTABLEINDEX / archive $QUEST_TABLE_ID / file $indexFile)"); return }

        val moved = ArrayList<Int>()
        for (r in PLAN) {
            val original = archive.file(r.dbrowId)?.data ?: run { println("ABORT: no existing DBROW ${r.dbrowId}"); return }
            val row = decodeRow(original)
            if (!hasIntColumn(row, COL_MEMBERS, ScriptVarType.BOOLEAN)) {
                println("  dbrow ${r.dbrowId} '${r.displayName}': no members column — skipping")
                continue
            }
            if (int(row, COL_MEMBERS) != 1) continue

            val backup = File(BACKUP_DIR, "dbrow_${r.dbrowId}.bin")
            if (!backup.exists()) {
                backup.parentFile.mkdirs()
                backup.writeBytes(original)
                println("backed up dbrow ${r.dbrowId} (${original.size} bytes) -> $backup")
            }
            setInt(row, COL_MEMBERS, 0)
            val writer = BufferWriter(4096)
            with(DBRowEncoder()) { writer.encode(row) }
            archive.add(r.dbrowId, writer.toArray())
            moved.add(r.dbrowId)
            println("dbrow ${r.dbrowId} '${r.displayName}': members -> free")
        }
        if (moved.isEmpty()) {
            println("no members' rows among our quests — nothing to do")
            return
        }

        val indexBackup = File(BACKUP_DIR, "questindex_${QUEST_TABLE_ID}_$indexFile.bin")
        if (!indexBackup.exists()) {
            indexBackup.parentFile.mkdirs()
            indexBackup.writeBytes(indexData)
            println("backed up column-$COL_MEMBERS index (${indexData.size} bytes) -> $indexBackup")
        }
        val tuples = decodeIndex(indexData)
        val tuple = tuples.singleOrNull() ?: run { println("ABORT: column-$COL_MEMBERS index has ${tuples.size} tuples, expected 1"); return }
        val freeRows = tuple.values.firstOrNull { it.first == 0 }?.second
            ?: run { println("ABORT: column-$COL_MEMBERS index has no key 0 (free) entry"); return }
        val membersRows = tuple.values.firstOrNull { it.first == 1 }?.second
            ?: run { println("ABORT: column-$COL_MEMBERS index has no key 1 (members) entry"); return }
        membersRows.removeAll(moved)
        for (id in moved) if (id !in freeRows) freeRows.add(id)
        freeRows.sort()
        println("column-$COL_MEMBERS index: free ${freeRows.size} rows, members ${membersRows.size} rows")
        lib.put(DBTABLEINDEX, QUEST_TABLE_ID, indexFile, encodeIndex(tuples))
        lib.update()
    } finally {
        lib.close()
    }

    val lib2 = CacheLibrary(cachePath)
    try {
        var ok = true
        val data = lib2.index(DBTABLEINDEX).archive(QUEST_TABLE_ID)?.file(indexFile)?.data
        val freeRows = data?.let { d -> decodeIndex(d).single().values.firstOrNull { it.first == 0 }?.second }?.toSet() ?: emptySet()
        for (r in PLAN) {
            val row = readRow(lib2, r.dbrowId)?.let { decodeRow(it) } ?: continue
            val flag = int(row, COL_MEMBERS) ?: continue
            if (flag != 0 || r.dbrowId !in freeRows) {
                ok = false
                println("VERIFY FAIL: dbrow ${r.dbrowId} '${r.displayName}' members=$flag inFreeIndex=${r.dbrowId in freeRows}")
            }
        }
        println(if (ok) "OK — every FoV quest row is a free quest. Restart the server to serve it." else "VERIFY FAILED — run 'restore' to roll the rows back.")
    } finally {
        lib2.close()
    }
}

// --- Phase 2: list ONLY our quests, by pruning the quest table's master row index ----------

/**
 * **hide** (LEGACY — superseded by the client-side filter; undo with [unhide]) — prune the quest
 * table's master row index down to [KEPT]. In practice the rev-228 list did not follow the pruned
 * master the way this assumed (the live tab ended up listing two rows), so it is no longer part of
 * the workflow. Kept for the record.
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
