package org.alter.tools.itemcheck

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import java.nio.file.Path

/**
 * Throwaway audit helper: print name / stacks / stackable / tradeable for a set of item ids so we can
 * confirm whether a candidate "currency" cache id (e.g. the reward tickets) actually stacks before
 * wiring it as an [org.alter.plugins.content.mechanics.shops.ItemCurrency]. A non-stacking currency
 * would eat one inventory slot per unit — useless as coins-like money.
 *
 * Run (workingDir = repo root):
 *   gradlew :game-server:itemCheck -PitemArgs="619 621 620 622 1464 995"
 */
private const val CACHE_PATH = "data/cache"
private const val REVISION = 228

fun main(args: Array<String>) {
    CacheManager.init(Cache.load(Path.of(CACHE_PATH), false), REVISION)
    // "param:<id>" audits the whole cache for one equipment param instead of printing items:
    // how many defs carry it at all, and the highest few. Used to establish that rev-228 ships
    // NO ranged-strength (189) values, so every quiver and thrown weapon rolls 0.
    args.firstOrNull { it.startsWith("param:") }?.let { arg ->
        val param = arg.removePrefix("param:").toIntOrNull() ?: return
        val carrying = CacheManager.getItems().values.mapNotNull { item ->
            val v = runCatching { CacheManager.getItem(item.id).params?.get(param) }.getOrNull() ?: return@mapNotNull null
            item.id to v
        }
        println("param $param: ${carrying.size} of ${CacheManager.getItems().size} item defs carry it")
        carrying.sortedByDescending { it.second.toString().toIntOrNull() ?: 0 }.take(10).forEach { (id, v) ->
            println("  %-6d %-30s = %s".format(id, runCatching { CacheManager.getItem(id).name }.getOrNull(), v))
        }
        return
    }
    val ids = args.mapNotNull { it.toIntOrNull() }
    println("id     | stacks | stackable | tradeable | noteTpl | cost     | name | invOptions")
    println("-------+--------+-----------+-----------+---------+----------+------+-----------")
    for (id in ids) {
        val d = runCatching { CacheManager.getItem(id) }.getOrNull()
        if (d == null) { println("%-6d | (no def)".format(id)); continue }
        println(
            "%-6d | %-6d | %-9s | %-9s | %-7d | %-8d | %s | inv=%s ground=%s".format(
                id, d.stacks, d.stackable.toString(), d.isTradeable.toString(), d.noteTemplateId, d.cost, d.name,
                d.interfaceOptions.toString(), d.options.toString(),
            ),
        )
        // Raw cache wear-requirement params (skill/level pairs): 434/436 primary, 435/437 secondary,
        // 191/613 tertiary, 579/614 quaternary. See ParamMapper.item and ItemMetadataService.
        val reqParams = intArrayOf(434, 436, 435, 437, 191, 613, 579, 614)
        val reqs = reqParams.joinToString(" ") { p -> "$p=${d.params?.get(p) ?: "-"}" }
        println("         equipSlot=${d.equipSlot} wearReqParams: $reqs")
        // Equipment bonuses (ParamMapper.item): an all-"-" row means the cache carries no stats
        // for this item at all, which is what "it has no stats" looks like in-game.
        val bonusParams = linkedMapOf(
            "astab" to 0, "aslash" to 1, "acrush" to 2, "amagic" to 3, "arange" to 4,
            "dstab" to 5, "dslash" to 6, "dcrush" to 7, "dmagic" to 8, "drange" to 9,
            "str" to 10, "pray" to 11, "speed" to 14, "rstr" to 189, "mdmg" to 299,
        )
        val bonuses = bonusParams.entries.joinToString(" ") { (n, p) -> "$n=${d.params?.get(p) ?: "-"}" }
        println("         bonuses: $bonuses")
    }
}
