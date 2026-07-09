package org.alter.tools.npcdef

import com.displee.cache.CacheLibrary
import dev.openrune.cache.CONFIGS
import dev.openrune.cache.CacheManager
import dev.openrune.cache.NPC
import dev.openrune.cache.filestore.Cache
import dev.openrune.cache.filestore.buffer.BufferWriter
import dev.openrune.cache.filestore.definition.encoder.NpcEncoder
import java.io.File
import java.nio.file.Path

/**
 * **NPC def cache tool** — edit an npc's cache definition (right-click menu options live in the
 * CLIENT's cache def, so a server-side override can't add them; rsprot extended-info can rename an
 * npc but not re-text its options).
 *
 * Uses the repo's own sanctioned codec pair: decode via OpenRune's [CacheManager] (the same
 * decoder the server boots with), re-encode via [NpcEncoder] (the same encoder the repo's
 * PackNpcs/PackConfig packers use), and write through displee `archive.add(id, bytes)` (same
 * path as every other cache tool here). The original record is backed up to
 * `data/cache-backups/npc_<id>.bin` before the first write; `restore <id>` puts it back.
 * After writing, the def is re-decoded from the updated cache and key fields asserted.
 *
 * The cache is shared and streamed to clients over JS5 — restart the server afterwards (and the
 * client will refetch the changed archive).
 *
 * Run (workingDir = repo root = the Alter project dir):
 *   gradlew :game-server:npcDef -PnpcArgs="inspect 1755"
 *   gradlew :game-server:npcDef -PnpcArgs="wizardknight"     # Void Knight 1755: Talk-to / Solo game / Multi game
 *   gradlew :game-server:npcDef -PnpcArgs="restore 1755"
 */

private const val CACHE_PATH = "data/cache"
private const val BACKUP_DIR = "data/cache-backups"
private const val REVISION = 228

private const val VOID_KNIGHT = 1755

fun main(args: Array<String>) {
    when (args.getOrNull(0)?.lowercase() ?: "inspect") {
        "inspect" -> inspect(args.drop(1).mapNotNull { it.toIntOrNull() })
        // NOTE: slot 2 (actions[1]) is left null on purpose — the engine hardwires the second
        // npc menu slot to the ATTACK pathway (players got "You can't attack this npc." when
        // "Solo game" sat there). Slots 3/4 route as normal string-matched options.
        "wizardknight" -> setActions(VOID_KNIGHT, listOf("Talk-to", null, "Solo game", "Multi game", null))
        "restore" -> restore(args.getOrNull(1)?.toIntOrNull() ?: run { println("restore <id>"); return })
        else -> println("usage: inspect <id...> | wizardknight | restore <id>")
    }
}

private fun initCache() = CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)

private fun describe(id: Int): String {
    val def = CacheManager.getNpcOrDefault(id)
    return "npc $id '${def.name}' cb=${def.combatLevel} size=${def.size} actions=${def.actions.toList()} " +
        "models=${def.models?.toList()} standAnim=${def.standAnim} walkAnim=${def.walkAnim}"
}

private fun inspect(ids: List<Int>) {
    initCache()
    ids.forEach { println(describe(it)) }
}

private fun setActions(id: Int, actions: List<String?>) {
    initCache()
    println("BEFORE: ${describe(id)}")
    val def = CacheManager.getNpc(id)
    val before = CacheManager.getNpcOrDefault(id)
    val beforeName = before.name
    val beforeModels = before.models?.toList()

    for (i in 0 until 5) def.actions[i] = actions.getOrNull(i)

    val writer = BufferWriter(4096)
    with(NpcEncoder()) { writer.encode(def) }
    val bytes = writer.toArray()

    val lib = CacheLibrary(CACHE_PATH)
    try {
        val archive = lib.index(CONFIGS).archive(NPC) ?: run { println("ABORT: no NPC archive"); return }
        // one-time backup of the pristine record
        val backup = File(BACKUP_DIR, "npc_$id.bin")
        if (!backup.exists()) {
            val original = archive.file(id)?.data
            if (original == null) { println("ABORT: no existing record for npc $id"); return }
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
    val after = CacheManager.getNpcOrDefault(id)
    println("AFTER:  ${describe(id)}")
    val ok = after.name == beforeName &&
        after.models?.toList() == beforeModels &&
        after.actions.toList() == actions
    if (ok) {
        println("OK — actions updated, name/models intact. Restart the server to serve the new cache.")
    } else {
        println("VERIFY FAILED — restoring the original record!")
        restore(id)
    }
}

private fun restore(id: Int) {
    val backup = File(BACKUP_DIR, "npc_$id.bin")
    if (!backup.exists()) { println("no backup at $backup"); return }
    val lib = CacheLibrary(CACHE_PATH)
    try {
        lib.index(CONFIGS).archive(NPC)?.add(id, backup.readBytes())
        lib.update()
    } finally {
        lib.close()
    }
    initCache()
    println("restored npc $id from backup: ${describe(id)}")
}
