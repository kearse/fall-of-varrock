package org.alter.tools.objcheck

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import java.nio.file.Path

/**
 * Audit helper (sibling of ItemStackCheckTool): print name / size / actions for a set of OBJECT
 * (loc) ids, so we can pick the right world object + option string to bind before spawning it
 * (e.g. the wilderness loot chest).
 *
 * Run (workingDir = repo root):
 *   gradlew :game-server:objCheck -PobjArgs="43468 43469"
 */
private const val CACHE_PATH = "data/cache"
private const val REVISION = 228

fun main(args: Array<String>) {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)
    val ids = args.mapNotNull { it.toIntOrNull() }
    println("id     | size  | name | actions | models | varbit/varp -> transforms")
    println("-------+-------+------+---------+--------+--------------------------")
    for (id in ids) {
        val d = runCatching { CacheManager.getObject(id) }.getOrNull()
        if (d == null) { println("%-6d | (no def)".format(id)); continue }
        val transform = if (d.varbit != -1 || d.varp != -1) "varbit=${d.varbit} varp=${d.varp} -> ${d.transforms?.toList()}" else "-"
        // MODELS index = 7: verify each referenced model group actually exists in this cache.
        val modelPresence = d.objectModels?.joinToString(",") { m ->
            val present = runCatching { dev.openrune.cache.CacheManager.cache.data(7, m) != null }.getOrDefault(false)
            "$m=${if (present) "OK" else "MISSING"}"
        }
        println("%-6d | %dx%d | %s | %s | models=[%s] | shapes=%s | %s".format(id, d.sizeX, d.sizeY, d.name, d.actions.toString(), modelPresence, d.objectTypes?.toList() ?: "any", transform))
    }
}
