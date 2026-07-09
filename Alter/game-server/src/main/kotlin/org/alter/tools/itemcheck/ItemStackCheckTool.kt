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
    }
}
