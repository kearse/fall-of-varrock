package org.alter.plugins.content.economy.audit.extract

import dev.openrune.cache.CacheManager
import org.alter.game.service.game.ItemMetadataService
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.grandexchange.GrandExchangeCommodities
import java.io.File

/**
 * Snapshot of every cache item the economy can price: cost (post-override), tradeability,
 * stackability, note link, explicit alch overrides, GE exclusion, commodity + guard flags.
 * Must run AFTER the shop plugins are constructed (the guard set is populated by them).
 */
object ItemExtractor {

    /** `item.<name>` keys by id, parsed straight from the rscm file (RSCM keeps its map private). */
    fun rscmItemKeys(rscmDir: File = File("../data/cfg/rscm")): Map<Int, String> {
        val file = File(rscmDir, "item.rscm")
        if (!file.isFile) return emptyMap()
        val out = HashMap<Int, String>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val i = line.lastIndexOf(':')
                if (i <= 0) return@forEach
                val id = line.substring(i + 1).trim().toIntOrNull() ?: return@forEach
                val name = line.substring(0, i).trim()
                // First key wins, but prefer a non-"_noted" alias if both point at the same id.
                val existing = out[id]
                if (existing == null || (existing.endsWith("_noted") && !name.endsWith("_noted"))) out[id] = "item.$name"
            }
        }
        return out
    }

    fun extract(keys: Map<Int, String>): Map<Int, ItemInfo> {
        val out = HashMap<Int, ItemInfo>()
        CacheManager.getItems().forEach { (id, def) ->
            val name = def.name ?: return@forEach
            if (name.isBlank() || name == "null") return@forEach
            val noted = def.noteTemplateId > 0
            val unnoted = if (noted && def.noteLinkId > -1) def.noteLinkId else id
            val stackableRaw: Any? = def.stackable
            val stackable = when (stackableRaw) {
                is Boolean -> stackableRaw
                is Number -> stackableRaw.toInt() == 1
                else -> def.stacks == 1
            } || noted
            out[id] = ItemInfo(
                id = id,
                key = keys[id],
                name = name,
                cost = def.cost,
                tradeable = def.isTradeable,
                stackable = stackable,
                unnotedId = unnoted,
                noted = noted,
                highAlchOverride = ItemMetadataService.highAlchOverride(id),
                lowAlchOverride = ItemMetadataService.lowAlchOverride(id),
                geExcluded = ItemMetadataService.isGeExcluded(id),
                isCommodity = GrandExchangeCommodities.isCommodity(id),
                guarded = SpecialShopGuard.isGuarded(id),
            )
        }
        return out
    }
}
