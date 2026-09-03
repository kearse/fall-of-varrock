package org.alter.plugins.content.economy.audit.engine

import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.NodeId

/**
 * A deliberately crude time model (game ticks per unit) so findings can be ranked by gp/hour.
 * Every number is a documented guess anchored on a `task.wait` / timer in the plugin it names;
 * treat gp/h as an order-of-magnitude ranking, not a measurement.
 */
object ActionTimeModel {
    const val TICKS_PER_HOUR = 6000.0

    /** Anything with no timer in code: one click ≈ one tick. */
    const val CLICK_TICKS = 1.0
    const val HIGH_ALCH_TICKS = 5.0      // AlchemyPlugin ALCH_DELAY
    const val LOW_ALCH_TICKS = 3.0
    const val GE_OFFER_TICKS = 0.05      // one offer covers a whole stack
    const val SMELT_TICKS = 3.0          // SmithingPlugin task.wait
    const val SMITH_TICKS = 4.0
    const val COOK_TICKS = 4.0           // CookingPlugin COOK_TICKS
    const val CRAFT_TICKS = 2.0          // CraftingPlugin task.wait(2)
    const val FLETCH_TICKS = 2.0         // FletchingPlugin task.wait(2)
    const val FARM_TICKS = 18.0          // FarmingPlugin GROW_TICKS 17 + plant
    const val HUNTER_TICKS = 4.0         // HunterPlugin CATCH_TICKS
    const val RUNECRAFT_TICKS = 1.0      // RunecraftPlugin task.wait(1)

    /** A 28-slot shop trip between adjacent hub vendors ≈ 6 ticks; stackables move 50/X per click. */
    const val SHOP_NONSTACK_TICKS = 6.0 / 28.0
    const val SHOP_STACK_TICKS = 0.02

    /** Finite shop slots regain 1 unit per 25 ticks (Shop.DEFAULT_RESUPPLY_*) = 240/hour. */
    const val RESTOCK_PER_HOUR = 240

    fun shopTicks(stackable: Boolean): Double = if (stackable) SHOP_STACK_TICKS else SHOP_NONSTACK_TICKS

    data class Throughput(val unitsFirstHour: Double, val unitsSustained: Double, val limitingFactor: String)

    /**
     * Units of the finding item per hour along the loop. [acquirePath] runs source → item and
     * [liquidatePath] item → sink; every edge's time and stock cap is scaled by how many of its
     * units one unit of the item needs (five bars per platebody, two coal per bar, two herbs per
     * seed, ...), so a finite shop slot caps the loop in ITEM units: first hour = stock + 240,
     * sustained = 240 per hour, both divided by the units consumed per item.
     */
    fun throughput(item: NodeId, acquirePath: List<Edge>, liquidatePath: List<Edge>): Throughput {
        var ticks = 0.0
        var first = Double.POSITIVE_INFINITY
        var sustained = Double.POSITIVE_INFINITY
        var limit = ""
        /** [execs] = executions of [e] per unit of the finding item (a shop edge moves one ware per execution). */
        fun cap(e: Edge, execs: Double) {
            ticks += e.ticksPerUnit * execs
            val stock = e.stock ?: return
            if (e.kind != EdgeKind.SHOP_SELL && e.kind != EdgeKind.SHOP_BUYBACK) return
            val f = (stock + RESTOCK_PER_HOUR) / execs
            val s = RESTOCK_PER_HOUR / execs
            if (f < first) first = f
            if (s < sustained) {
                sustained = s
                limit = "shop stock $stock (+$RESTOCK_PER_HOUR/h restock) @ ${e.shopName}" +
                    if (execs != 1.0) " × %.2f per item".format(execs) else ""
            }
        }
        // Walk the acquire path backwards from the item. `needed` = units of `node` per item; an
        // edge producing outQty of it (× EV) runs needed / (outQty × ev) times, and each run
        // consumes inQty of the input the previous edge supplied.
        var needed = 1.0
        var node = item
        for (i in acquirePath.indices.reversed()) {
            val e = acquirePath[i]
            val outQty = e.outputs.firstOrNull { it.node == node }?.qty ?: 1.0
            val execs = needed / (outQty * e.evAtMaxLevel.coerceAtLeast(1e-9))
            cap(e, execs)
            val prev = acquirePath.getOrNull(i - 1) ?: break
            val inp = e.inputs.firstOrNull { s -> prev.outputs.any { it.node == s.node } } ?: break
            needed = execs * inp.qty
            node = inp.node
        }
        // The liquidate path consumes one item per step (a unit sold, alched or converted on).
        for (e in liquidatePath) cap(e, 1.0)
        ticks = ticks.coerceAtLeast(0.01)
        val byTicks = TICKS_PER_HOUR / ticks
        if (byTicks < first) first = byTicks
        if (byTicks < sustained) { sustained = byTicks; limit = "%.1f ticks/unit".format(ticks) }
        return Throughput(first, sustained, limit)
    }

    /** Convenience for tests: the last edge liquidates, the rest acquire the item they produce. */
    fun throughput(path: List<Edge>): Throughput {
        val acquire = path.dropLast(1)
        val item = acquire.lastOrNull()?.outputs?.firstOrNull()?.node ?: path.last().inputs.first().node
        return throughput(item, acquire, path.takeLast(1))
    }

    val rateTable: List<Pair<String, String>> = listOf(
        "shop buy/sell (non-stackable)" to "%.2f ticks/unit (28-slot trip ≈ 6 ticks)".format(SHOP_NONSTACK_TICKS),
        "shop buy/sell (stackable)" to "$SHOP_STACK_TICKS ticks/unit",
        "finite shop slot" to "restocks $RESTOCK_PER_HOUR/hour (1 per 25 ticks); first hour = stock + $RESTOCK_PER_HOUR",
        "high / low alchemy" to "$HIGH_ALCH_TICKS / $LOW_ALCH_TICKS ticks per cast",
        "GE backstop offer" to "$GE_OFFER_TICKS ticks/unit (one offer per stack, no NPC quantity cap)",
        "smelt / smith" to "$SMELT_TICKS / $SMITH_TICKS ticks",
        "cook" to "$COOK_TICKS ticks",
        "craft / fletch" to "$CRAFT_TICKS ticks",
        "herblore / superheat / enchant / forge / dye / sets" to "$CLICK_TICKS tick (no timer in code)",
        "runecraft" to "$RUNECRAFT_TICKS tick per essence",
        "farming" to "$FARM_TICKS ticks per seed (GROW_TICKS 17)",
        "hunter" to "$HUNTER_TICKS ticks per catch",
        "bones to bananas/peaches" to "1 tick per inventory (whole stack per cast)",
    )
}
