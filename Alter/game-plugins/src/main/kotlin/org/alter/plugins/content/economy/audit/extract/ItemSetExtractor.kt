package org.alter.plugins.content.economy.audit.extract

import dev.openrune.cache.CacheManager.getEnum
import org.alter.api.cfg.Enums
import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.RecipeCategory
import org.alter.plugins.content.economy.audit.model.Stack

/**
 * Item sets from the cache (enum 1034 maps a set-box item to a sub-enum; in the sub-enum the entry
 * keyed -1 is the box and every other entry is a piece). `ItemsetsPlugin` packs/unpacks them 1:1,
 * so the edges are zero-cost conversions in both directions.
 */
object ItemSetExtractor {
    fun extract(items: Map<Int, ItemInfo>): List<Edge> {
        val out = ArrayList<Edge>()
        val sets = runCatching { getEnum(Enums.ITEM_SETS) }.getOrNull() ?: return out
        for ((boxKey, subId) in sets.values) {
            val sub = runCatching { getEnum(subId as Int) }.getOrNull() ?: continue
            val box = (sub.values[-1] as? Int) ?: (boxKey as? Int) ?: continue
            val pieces = sub.values.filter { it.key != -1 }.mapNotNull { it.value as? Int }
            if (pieces.isEmpty()) continue
            val boxKeyName = items[box]?.key ?: "item#$box"
            out += Edge(
                id = "set:unpack:$boxKeyName", kind = EdgeKind.SET_UNPACK, source = "ItemsetsPlugin",
                inputs = listOf(Stack(NodeId.ItemNode(box), 1.0)),
                outputs = pieces.map { Stack(NodeId.ItemNode(it), 1.0) },
                ticksPerUnit = ActionTimeModel.CLICK_TICKS, category = RecipeCategory.CONVERT,
            )
            out += Edge(
                id = "set:pack:$boxKeyName", kind = EdgeKind.SET_PACK, source = "ItemsetsPlugin",
                inputs = pieces.map { Stack(NodeId.ItemNode(it), 1.0) },
                outputs = listOf(Stack(NodeId.ItemNode(box), 1.0)),
                ticksPerUnit = ActionTimeModel.CLICK_TICKS, category = RecipeCategory.CONVERT,
            )
        }
        return out
    }
}
