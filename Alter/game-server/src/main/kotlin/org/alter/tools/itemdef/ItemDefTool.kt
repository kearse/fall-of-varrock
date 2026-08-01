package org.alter.tools.itemdef

import com.displee.cache.CacheLibrary
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.CacheManager
import dev.openrune.cache.ITEM
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.buffer.BufferWriter
import dev.openrune.cache.filestore.definition.encoder.ItemEncoder
import java.io.File
import java.nio.file.Path

/**
 * **Item def cache tool** — edit an item's cache definition (the NpcDefTool pattern, for items).
 *
 * Item NAMES are rendered by the CLIENT from its cache def: the server-side itemOverride YAML
 * renames an item for server logic only, so a repurposed currency id (e.g. Boss Ticket on the
 * Castle wars ticket id) still shows its old cache name in the player's inventory. Fix = edit
 * the name in the cache itself; the client refetches the changed archive over JS5 after a
 * server restart (same as the npcDef Void Knight edit).
 *
 * Same sanctioned codec pair as NpcDefTool: decode via OpenRune's [CacheManager], re-encode via
 * [ItemEncoder] (the PackItems path), write through displee `archive.add(id, bytes)`. Original
 * records are backed up to `data/cache-backups/item_<id>.bin` before the first write;
 * `restore <id>` puts one back. After writing, the def is re-decoded and asserted.
 *
 * Item EXAMINES live in the cache def too (opcode 3, rendered client-side) — the server's
 * `objs.csv` copy only feeds the server-side ground-item examine path, so an examine change
 * needs both this tool (client) and the csv (server).
 *
 * Run (workingDir = repo root = the Alter project dir):
 *   gradlew :game-server:itemDef -PitemDefArgs="inspect 4067 8851 4278"
 *   gradlew :game-server:itemDef -PitemDefArgs="tickets"          # Boss/Vote Ticket names
 *   gradlew :game-server:itemDef -PitemDefArgs="commendation"     # Ecto-token -> Commendation
 *   gradlew :game-server:itemDef -PitemDefArgs="rename 4067 Boss Ticket"
 *   gradlew :game-server:itemDef -PitemDefArgs="examine 4067 Redeem me at the boss vendor."
 *   gradlew :game-server:itemDef -PitemDefArgs="restore 4067"
 */

private const val CACHE_PATH = "data/cache"
private const val BACKUP_DIR = "data/cache-backups"
private const val REVISION = 228

private const val BOSS_TICKET = 4067 // Castle wars ticket sprite — our Boss Ticket currency
private const val VOTE_TICKET = 8851 // Warrior guild token sprite — our Vote Ticket currency
private const val COMMENDATION = 4278 // Ecto-token sprite — our Commendation war currency (WarForge)

// Keep in sync with the 4278 line in data/cfg/objs.csv (the server-side examine source).
private const val COMMENDATION_EXAMINE =
    "Proof of service in the realm's wars. Thurgo, the Royal Smith at Lumbridge Castle, forges armour for these."

fun main(args: Array<String>) {
    when (args.getOrNull(0)?.lowercase() ?: "inspect") {
        "inspect" -> inspect(args.drop(1).mapNotNull { it.toIntOrNull() })
        "tickets" -> {
            edit(BOSS_TICKET, name = "Boss Ticket")
            edit(VOTE_TICKET, name = "Vote Ticket")
        }
        "commendation" -> edit(COMMENDATION, name = "Commendation", examine = COMMENDATION_EXAMINE)
        "rename" -> {
            val id = args.getOrNull(1)?.toIntOrNull() ?: run { println("rename <id> <name...>"); return }
            val name = args.drop(2).joinToString(" ").trim()
            if (name.isEmpty()) { println("rename <id> <name...>"); return }
            edit(id, name = name)
        }
        "examine" -> {
            val id = args.getOrNull(1)?.toIntOrNull() ?: run { println("examine <id> <text...>"); return }
            val text = args.drop(2).joinToString(" ").trim()
            if (text.isEmpty()) { println("examine <id> <text...>"); return }
            edit(id, examine = text)
        }
        "restore" -> restore(args.getOrNull(1)?.toIntOrNull() ?: run { println("restore <id>"); return })
        else -> println("usage: inspect <id...> | tickets | commendation | rename <id> <name...> | examine <id> <text...> | restore <id>")
    }
}

private fun initCache() = CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)

private fun describe(id: Int): String {
    val def = CacheManager.getItemOrDefault(id)
    return "item $id '${def.name}' cost=${def.cost} stacks=${def.stacks} noteTpl=${def.noteTemplateId} " +
        "invOptions=${def.options.toList()} examine='${def.examine}'"
}

private fun inspect(ids: List<Int>) {
    initCache()
    ids.forEach { println(describe(it)) }
}

private fun edit(id: Int, name: String? = null, examine: String? = null) {
    initCache()
    println("BEFORE: ${describe(id)}")
    val def = CacheManager.getItem(id)
    val before = CacheManager.getItemOrDefault(id)
    // capture BEFORE mutating — getItem/getItemOrDefault may hand back the same instance
    val beforeCost = before.cost
    val beforeStacks = before.stacks
    val beforeNoteTpl = before.noteTemplateId
    val wantName = name ?: before.name
    val wantExamine = examine ?: before.examine

    name?.let { def.name = it }
    examine?.let { def.examine = it }

    val writer = BufferWriter(4096)
    with(ItemEncoder()) { writer.encode(def) }
    val bytes = writer.toArray()

    val lib = CacheLibrary(CACHE_PATH)
    try {
        val archive = lib.index(CONFIGS).archive(ITEM) ?: run { println("ABORT: no ITEM archive"); return }
        // one-time backup of the pristine record
        val backup = File(BACKUP_DIR, "item_$id.bin")
        if (!backup.exists()) {
            val original = archive.file(id)?.data
            if (original == null) { println("ABORT: no existing record for item $id"); return }
            backup.parentFile.mkdirs()
            backup.writeBytes(original)
            println("backed up original (${original.size} bytes) -> $backup")
        }
        archive.add(id, bytes)
        lib.update()
    } finally {
        lib.close()
    }

    // verify: re-decode from the updated cache and assert nothing else was mangled
    initCache()
    val after = CacheManager.getItemOrDefault(id)
    println("AFTER:  ${describe(id)}")
    val ok = after.name == wantName &&
        after.examine == wantExamine &&
        after.cost == beforeCost &&
        after.stacks == beforeStacks &&
        after.noteTemplateId == beforeNoteTpl
    if (ok) {
        println("OK — def updated, cost/stacks/note intact. Restart the server to serve the new cache.")
    } else {
        println("VERIFY FAILED — restoring the original record!")
        restore(id)
    }
}

private fun restore(id: Int) {
    val backup = File(BACKUP_DIR, "item_$id.bin")
    if (!backup.exists()) { println("no backup at $backup"); return }
    val lib = CacheLibrary(CACHE_PATH)
    try {
        lib.index(CONFIGS).archive(ITEM)?.add(id, backup.readBytes())
        lib.update()
    } finally {
        lib.close()
    }
    initCache()
    println("restored item $id from backup: ${describe(id)}")
}
