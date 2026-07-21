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
 *   gradlew :game-server:npcDef -PnpcArgs="krilbodyguards"   # K'ril's 3 bodyguards: add the "Attack" option
 *   gradlew :game-server:npcDef -PnpcArgs="kreearra"         # Kree'arra's 3 minions: add "Attack" + combat level
 *   gradlew :game-server:npcDef -PnpcArgs="restore 1755"
 */

private const val CACHE_PATH = "data/cache"
private const val BACKUP_DIR = "data/cache-backups"
private const val REVISION = 228

private const val VOID_KNIGHT = 1755

// K'ril Tsutsaroth's bodyguards (Tstanon Karlak / Zakl'n Gritch / Balfrug Kreeyath). Their cache
// defs lack the "Attack" menu option, so Combat.canEngage's `def.isAttackable()` check rejects them
// ("You can't attack this npc.") even though the server registers a full combat def. Put "Attack"
// in slot 2 (actions[1]) — the slot OpNpcHandler routes to client.attack — matching K'ril himself.
private val KRIL_BODYGUARDS = listOf(3130, 3131, 3132)

// Kree'arra's bodyguards (Wingman Skree / Flockleader Geerin / Flight Kilisa). Like K'ril's, their
// cache defs lack the "Attack" menu option — but these also ship as decorative birds (absent from
// npc_combat.json, generic "Graceful, bird-like creature." examine) with combatLevel 0, so
// isAttackable()'s `combatLevel > 0` half would still reject them. Set BOTH the Attack option and
// the OSRS combat level (id to combatLevel).
private val KREEARRA_MINIONS = listOf(3163 to 143, 3164 to 149, 3165 to 159)

fun main(args: Array<String>) {
    when (args.getOrNull(0)?.lowercase() ?: "inspect") {
        "inspect" -> inspect(args.drop(1).mapNotNull { it.toIntOrNull() })
        "anims" -> anims(args.drop(1).mapNotNull { it.toIntOrNull() })
        // NOTE: slot 2 (actions[1]) is left null on purpose — the engine hardwires the second
        // npc menu slot to the ATTACK pathway (players got "You can't attack this npc." when
        // "Solo game" sat there). Slots 3/4 route as normal string-matched options.
        "wizardknight" -> setActions(VOID_KNIGHT, listOf("Talk-to", null, "Solo game", "Multi game", null))
        // The inverse of wizardknight: these ARE meant to be attacked, so put "Attack" in slot 2.
        "krilbodyguards" -> KRIL_BODYGUARDS.forEach { setActions(it, listOf(null, "Attack", null, null, null)) }
        // Same as krilbodyguards, but the birds also need a combat level (see KREEARRA_MINIONS).
        "kreearra" -> KREEARRA_MINIONS.forEach { (id, cb) -> setActions(id, listOf(null, "Attack", null, null, null), combatLevel = cb) }
        "restore" -> restore(args.getOrNull(1)?.toIntOrNull() ?: run { println("restore <id>"); return })
        else -> println("usage: inspect <id...> | anims <id...> | wizardknight | krilbodyguards | kreearra | restore <id>")
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

/**
 * List every animation an npc's model can actually PLAY.
 *
 * A sequence's frame ids pack the **frame archive** (the skeleton the frames were authored
 * against) into their high 16 bits, so two sequences sharing a frame archive drive the same
 * skeleton. Playing an animation from a foreign archive is what makes an npc contort or freeze —
 * the goblins were being given animation 422, the human unarmed punch.
 *
 * So: take the npc's own stand/walk anims, read their frame archive, and print every sequence in
 * the cache built on that same archive. Those — and only those — are safe attack/block/death
 * animations for that npc.
 *
 *   gradlew :game-server:npcDef -PnpcArgs="anims 655"
 */
private fun anims(ids: List<Int>) {
    initCache()
    val seqs = CacheManager.getAnims()
    fun archiveOf(seq: Int): Int? = seqs[seq]?.frameIDs?.firstOrNull()?.ushr(16)

    ids.forEach { id ->
        val def = CacheManager.getNpcOrDefault(id)
        val archives = listOfNotNull(archiveOf(def.standAnim), archiveOf(def.walkAnim)).toSet()
        val resolved = org.alter.game.model.combat.NpcAnims.resolve(id)
        println(
            "npc $id '${def.name}' standAnim=${def.standAnim} walkAnim=${def.walkAnim} " +
                "frameArchives=$archives -> RESOLVED attack=${resolved.attack} block=${resolved.block} death=${resolved.death}"
        )
        if (archives.isEmpty()) {
            println("  (no frame archive — this npc has no usable animations)")
            return@forEach
        }
        seqs.keys.sorted().forEach { seq ->
            if (archiveOf(seq) in archives) {
                val s = seqs[seq]!!
                val frames = s.frameIDs?.size ?: 0
                val ticks = s.frameDelays?.sum() ?: 0
                val tag = when (seq) {
                    def.standAnim -> " <- stand"
                    def.walkAnim -> " <- walk"
                    else -> ""
                }
                println("  anim $seq: frames=$frames delayTotal=$ticks loops=${s.maxLoops}$tag")
            }
        }
    }
}

/**
 * Rewrite an npc's right-click [actions] in the cache, and — when [combatLevel] is given — its
 * combat level too. Both live only in the cache def; `isAttackable()` checks the "Attack" action
 * AND `combatLevel > 0`, so a def that ships with neither needs both set here.
 */
private fun setActions(id: Int, actions: List<String?>, combatLevel: Int? = null) {
    initCache()
    println("BEFORE: ${describe(id)}")
    val def = CacheManager.getNpc(id)
    val before = CacheManager.getNpcOrDefault(id)
    val beforeName = before.name
    val beforeModels = before.models?.toList()

    for (i in 0 until 5) def.actions[i] = actions.getOrNull(i)
    if (combatLevel != null) def.combatLevel = combatLevel

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
        after.actions.toList() == actions &&
        (combatLevel == null || after.combatLevel == combatLevel)
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
