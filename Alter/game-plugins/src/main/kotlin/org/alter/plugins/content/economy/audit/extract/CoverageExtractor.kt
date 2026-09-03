package org.alter.plugins.content.economy.audit.extract

import org.alter.game.model.World
import org.alter.plugins.content.economy.audit.model.ItemInfo

/**
 * Every item-on-item / item-on-object pair the constructed plugins bound, minus the pairs a
 * recipe adapter explains, minus a small allow-list of non-economic binds. Whatever is left is a
 * converter the audit is BLIND to and must be reported (a new crafting path that slipped in).
 */
object CoverageExtractor {

    data class Unexplained(val kind: String, val a: String, val b: String)

    class Result(val unexplained: List<Unexplained>, val allowlisted: List<Unexplained>, val boundItemPairs: Int, val boundObjPairs: Int)

    /** Non-economic binds: things that consume an item for xp/state but never produce an item. */
    private val ALLOW_ITEM_KEYS = listOf("item.tinderbox")
    private val ALLOW_OBJ_KEY_PARTS = listOf("altar", "workbench", "fountain", "sink", "well", "pump", "fire", "range", "tap", "water")

    fun extract(
        world: World,
        items: Map<Int, ItemInfo>,
        objKeys: Map<Int, String>,
        recipes: RecipeReflection.Result,
    ): Result {
        val repo = world.plugins
        @Suppress("UNCHECKED_CAST")
        val itemOnItem = Reflect.field(repo, "itemOnItemPlugins") as Map<Int, Any>
        @Suppress("UNCHECKED_CAST")
        val itemOnObj = Reflect.field(repo, "itemOnObjectPlugins") as Map<Int, Map<Int, Any>>

        fun itemName(id: Int) = items[id]?.key ?: "item#$id"
        fun objName(id: Int) = objKeys[id] ?: "object#$id"

        val unexplained = ArrayList<Unexplained>()
        val allowlisted = ArrayList<Unexplained>()
        for (hash in itemOnItem.keys.sorted()) {
            if (hash in recipes.explainedItemPairs) continue
            val a = hash ushr 16
            val b = hash and 0xFFFF
            val u = Unexplained("item-on-item", itemName(a), itemName(b))
            if (ALLOW_ITEM_KEYS.any { it == u.a || it == u.b }) allowlisted += u else unexplained += u
        }
        var objCount = 0
        for ((item, byObj) in itemOnObj.entries.sortedBy { it.key }) {
            for (obj in byObj.keys.sorted()) {
                objCount++
                if (RecipeReflection.objPair(item, obj) in recipes.explainedObjPairs) continue
                val u = Unexplained("item-on-object", itemName(item), objName(obj))
                val objKey = u.b.lowercase()
                if (ALLOW_OBJ_KEY_PARTS.any { objKey.contains(it) }) allowlisted += u else unexplained += u
            }
        }
        return Result(unexplained, allowlisted, itemOnItem.size, objCount)
    }
}
