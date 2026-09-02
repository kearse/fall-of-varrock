package org.alter.tools.itemcheck

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import org.alter.game.service.game.ItemMetadataService
import java.nio.file.Path

/**
 * Boot-faithful audit: run the REAL ItemMetadataService.loadAll() (same relative "../data" paths as
 * the live server, so this task must run with workingDir = game-server) and then print the resulting
 * tradeable flag + skillReqs for the given item ids. Distinguishes "cache params fine but boot-time
 * population broken" from "population fine, enforcement elsewhere broken" when equip level
 * requirements misbehave. Note `itemCheck` reads the RAW cache without loadAll(), so it cannot show
 * what an itemOverrides document did to a def — use this task for that.
 *
 * Run (workingDir = game-server):
 *   gradlew :game-server:metaReqCheck -PitemArgs="1127 1333"
 */
fun main(args: Array<String>) {
    CacheManager.init(Cache.load(Path.of("../data/cache"), false), 228)
    ItemMetadataService().loadAll()
    var withReqs = 0
    CacheManager.getItems().forEach { (_, item) -> if (item.skillReqs != null) withReqs++ }
    println("metaReqCheck: items with skillReqs after loadAll = $withReqs")
    for (id in args.mapNotNull { it.toIntOrNull() }) {
        val d = runCatching { CacheManager.getItem(id) }.getOrNull()
        if (d == null) {
            println("$id: (no def)")
            continue
        }
        val reqs = d.skillReqs?.entries?.joinToString(", ") { "skill=${it.key} lvl=${it.value}" } ?: "NONE"
        println("$id ${d.name}: tradeable=${d.isTradeable} skillReqs=[$reqs]")
    }
}
