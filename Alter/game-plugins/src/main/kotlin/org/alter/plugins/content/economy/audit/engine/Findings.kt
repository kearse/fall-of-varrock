package org.alter.plugins.content.economy.audit.engine

import org.alter.plugins.content.economy.audit.model.EconModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.RecipeCategory

/** The user's eight loop classes (+ one for the mirror of the GE case). */
enum class LoopClass(val title: String) {
    NPC_BUY_CRAFT_SELL("NPC buy → craft → NPC sell"),
    NPC_BUY_CONVERT_SELL("NPC buy → convert → NPC sell"),
    SHOP_TO_SHOP("Shop A → Shop B"),
    SHOP_TO_ALCH("Shop → high alch"),
    GE_FLOOR_TO_SHOP("GE NPC floor → shop"),
    SHOP_TO_GE_FLOOR("Shop → GE NPC floor (mirror)"),
    TRADING_POST_TO_SHOP("Trading Post → shop"),
    CURRENCY_TO_ITEM_TO_GP("currency → item → GP"),
    ITEM_TO_CURRENCY_TO_ITEM("item → currency → item"),
    OTHER("other"),
}

enum class Severity { S0, S1, S2, S3, INFO }

data class PathStep(val edgeId: String, val kind: EdgeKind, val text: String, val guardedBy: String?, val soft: Boolean)

data class Finding(
    val id: String,
    val loopClass: LoopClass,
    val node: NodeId,
    val itemKey: String,
    val itemName: String,
    val acquireGp: Double,
    val liquidateGp: Double,
    val unitProfit: Double,
    val marginPct: Double,
    val acquireGpEvMin: Double,
    val liquidateGpEvMin: Double,
    val acquirePath: List<PathStep>,
    val liquidatePath: List<PathStep>,
    val throughput: ActionTimeModel.Throughput,
    val gpPerHourFirstHour: Double,
    val gpPerHourSustained: Double,
    val severity: Severity,
    val unbounded: Boolean,
    val guardedBy: List<String>,
    val soft: Boolean,
    val sameShop: Boolean,
    val levelNotes: List<String>,
    /** Other items whose best liquidation ends in the SAME sink edge: the same loop seen from an
     *  earlier input (ore → bar → platebody all "sell the platebody"). Folded into this finding. */
    val related: List<String> = emptyList(),
)

data class RecipeEv(
    val edgeId: String, val source: String, val inputs: String, val outputs: String,
    val inputCost: Double, val outputValueEvMin: Double, val outputValueNoFail: Double,
    val profitEvMin: Double, val profitNoFail: Double, val note: String, val guardedBy: String?,
)

data class Hygiene(
    val belowCost: List<Map<String, Any?>>,
    val zeroCostWares: List<Map<String, Any?>>,
    val inconsistentSell: List<Map<String, Any?>>,
    val sameShopLoop: List<Map<String, Any?>>,
    val nearMiss: List<Map<String, Any?>>,
)

/**
 * Turns the four valuations into ranked findings, the "prevented by guards" regression table,
 * the recipe EV table and the price-hygiene lists.
 */
class FindingsBuilder(
    private val model: EconModel,
    private val primary: Valuation,        // NO_FAIL, guards on
    private val evMin: Valuation,          // EV_MIN_LEVEL, guards on
    private val unguarded: Valuation,      // NO_FAIL, guards off
    private val currencyNodes: Set<NodeId>,
) {
    private val coins = model.coins

    companion object {
        const val MIN_MARGIN = 0.02
        const val MIN_PROFIT = 1.0
        const val NEAR_MISS_LOW = 0.8
    }

    private fun isCandidate(v: Valuation, n: NodeId): Boolean {
        if (n == coins) return false
        if (n is NodeId.PointsNode) return false
        val a = v.acqOf(n); val l = v.liqOf(n)
        if (a.isInfinite() || l <= 0.0) return false
        if (a <= 0.0) return true
        return l - a >= MIN_PROFIT && l / a >= 1.0 + MIN_MARGIN
    }

    fun findings(): List<Finding> = candidates(primary, requireGuardFree = true)

    /** Nodes profitable with guards OFF that are not with guards ON — what the guards currently stop. */
    fun preventedByGuards(): List<Finding> {
        val live = findings().map { it.node }.toSet()
        return candidates(unguarded, requireGuardFree = false).filter { it.node !in live && it.guardedBy.isNotEmpty() }
    }

    /**
     * A node is the *dominant* input of its own liquidation edge when no other input of that edge
     * costs more to acquire. A nature rune liquidated "through" alching a platebody is not (the
     * platebody is), so its marginal value is the platebody loop's profit, not a loop of its own.
     */
    private fun isDominantInput(v: Valuation, n: NodeId, e: Edge): Boolean {
        val mine = e.inputs.firstOrNull { it.node == n } ?: return true
        val myCost = v.acqOf(n) * mine.qty
        return e.inputs.none { it.node != n && it.node != coins && v.acqOf(it.node) * it.qty > myCost }
    }

    /** True when [n] is the dominant input at EVERY step of its liquidation chain (essence → nature
     *  rune → "alch a platebody" fails at the alch step: the platebody dominates there). */
    private fun liquidatesAsDominant(v: Valuation, n: NodeId, path: List<Edge>): Boolean {
        var cur = n
        for (e in path) {
            if (!isDominantInput(v, cur, e)) return false
            val next = e.outputs.filter { it.node != coins }.maxByOrNull { v.liqOf(it.node) * it.qty } ?: return true
            cur = next.node
        }
        return true
    }

    private fun candidates(v: Valuation, requireGuardFree: Boolean): List<Finding> {
        val nodes = (v.acq.keys + v.liq.keys).toSet().filter { isCandidate(v, it) }
        val out = ArrayList<Finding>()
        for (n in nodes) {
            val acqPath = ArbitrageEngine.acquirePath(v, n, coins)
            val liqPath = ArbitrageEngine.liquidatePath(v, n, coins)
            if (acqPath.isEmpty() || liqPath.isEmpty()) continue
            if (!liquidatesAsDominant(v, n, liqPath)) continue
            val path = acqPath + liqPath
            val guards = path.mapNotNull { it.guardedBy }.distinct()
            if (requireGuardFree && guards.isNotEmpty()) continue
            val soft = path.any { it.soft }
            val a = v.acqOf(n); val l = v.liqOf(n)
            val profit = l - a
            val margin = if (a > 0) (l / a - 1.0) * 100.0 else Double.POSITIVE_INFINITY
            val thr = ActionTimeModel.throughput(path)
            val unbounded = n in v.unbounded
            val gphFirst = profit * thr.unitsFirstHour
            val gphSust = profit * thr.unitsSustained
            val sev = severity(gphSust, unbounded)
            val cls = classify(acqPath, liqPath)
            val sameShop = cls == LoopClass.SHOP_TO_SHOP && acqPath.last().shopName != null && acqPath.last().shopName == liqPath.first().shopName
            out += Finding(
                id = "${cls.name.lowercase()}:${model.key(n)}",
                loopClass = cls, node = n, itemKey = model.key(n), itemName = model.name(n),
                acquireGp = a, liquidateGp = l, unitProfit = profit, marginPct = margin,
                acquireGpEvMin = evMin.acqOf(n), liquidateGpEvMin = evMin.liqOf(n),
                acquirePath = acqPath.map { step(it) }, liquidatePath = liqPath.map { step(it) },
                throughput = thr, gpPerHourFirstHour = gphFirst, gpPerHourSustained = gphSust,
                severity = sev, unbounded = unbounded, guardedBy = guards, soft = soft, sameShop = sameShop,
                levelNotes = path.map { it.levelNote }.filter { it.isNotBlank() }.distinct(),
            )
        }
        // One loop, one finding: every input of a profitable chain "liquidates" through the same
        // terminal sink (the rune that alchs the platebody is worth the platebody's profit). Keep
        // the item nearest the sink as the representative and fold the rest in as `related`.
        // A currency is the medium of a loop, never its subject, so an item beats a ticket as the
        // representative even when the ticket sits nearer the sink.
        val grouped = out.groupBy { it.liquidatePath.last().edgeId }.values.map { g ->
            val rep = g.sortedWith(
                compareBy<Finding> { if (it.node in currencyNodes) 1 else 0 }
                    .thenBy { it.liquidatePath.size }.thenByDescending { it.unitProfit },
            ).first()
            rep.copy(related = g.filter { it !== rep }.map { it.itemKey }.sorted())
        }
        return grouped.sortedWith(compareBy<Finding> { it.severity.ordinal }.thenByDescending { it.gpPerHourSustained }.thenBy { it.itemKey })
    }

    fun severity(gpPerHour: Double, unbounded: Boolean): Severity = when {
        unbounded || gpPerHour >= 1_000_000 -> Severity.S0
        gpPerHour >= 100_000 -> Severity.S1
        gpPerHour >= 10_000 -> Severity.S2
        gpPerHour >= 1_000 -> Severity.S3
        else -> Severity.INFO
    }

    fun classify(acqPath: List<Edge>, liqPath: List<Edge>): LoopClass {
        val all = acqPath + liqPath
        // Judged over the WHOLE loop, whichever node represents it: buying anything with a
        // non-coin currency, or being paid a non-coin currency by an NPC, anywhere in the chain.
        val acqCurrency = all.any { e -> e.kind == EdgeKind.SHOP_SELL && e.inputs.any { it.node != coins && it.node in currencyNodes } }
        val liqCurrency = all.any { e -> e.kind == EdgeKind.SHOP_BUYBACK && e.outputs.any { it.node != coins && it.node in currencyNodes } }
        val hasCraft = all.any { it.kind == EdgeKind.RECIPE && it.category == RecipeCategory.CRAFT }
        val hasConvert = all.any { (it.kind == EdgeKind.RECIPE && it.category == RecipeCategory.CONVERT) || it.kind == EdgeKind.DOSE || it.kind == EdgeKind.SET_PACK || it.kind == EdgeKind.SET_UNPACK }
        val acqGe = acqPath.any { it.kind == EdgeKind.GE_BUY }
        val liqGe = liqPath.any { it.kind == EdgeKind.GE_SELL }
        val acqTp = acqPath.any { it.kind == EdgeKind.SHOP_SELL && it.shopName == "Trading Post" }
        val liqAlch = liqPath.any { it.kind == EdgeKind.ALCH_HIGH || it.kind == EdgeKind.ALCH_LOW }
        val liqShop = liqPath.any { it.kind == EdgeKind.SHOP_BUYBACK }
        val acqShop = acqPath.any { it.kind == EdgeKind.SHOP_SELL }
        return when {
            liqCurrency -> LoopClass.ITEM_TO_CURRENCY_TO_ITEM
            acqCurrency -> LoopClass.CURRENCY_TO_ITEM_TO_GP
            hasCraft -> LoopClass.NPC_BUY_CRAFT_SELL
            hasConvert -> LoopClass.NPC_BUY_CONVERT_SELL
            acqGe && liqShop -> LoopClass.GE_FLOOR_TO_SHOP
            acqShop && liqGe -> LoopClass.SHOP_TO_GE_FLOOR
            acqTp -> LoopClass.TRADING_POST_TO_SHOP
            acqShop && liqAlch -> LoopClass.SHOP_TO_ALCH
            acqShop && liqShop -> LoopClass.SHOP_TO_SHOP
            else -> LoopClass.OTHER
        }
    }

    fun step(e: Edge): PathStep {
        val ins = e.inputs.joinToString(" + ") { qty(it.qty) + " " + model.name(it.node) }
        val outs = e.outputs.joinToString(" + ") { qty(it.qty) + " " + model.name(it.node) }
        val text = when (e.kind) {
            EdgeKind.SHOP_SELL -> "buy ${outs} @ ${e.shopName} for $ins${if (e.unlimited) " (unlimited)" else e.stock?.let { " (stock $it)" } ?: ""}"
            EdgeKind.SHOP_BUYBACK -> "sell $ins to ${e.shopName} for $outs"
            EdgeKind.ALCH_HIGH -> "high alch $ins → $outs"
            EdgeKind.ALCH_LOW -> "low alch $ins → $outs"
            EdgeKind.GE_BUY -> "GE backstop sells $outs for $ins"
            EdgeKind.GE_SELL -> "GE backstop buys $ins for $outs"
            EdgeKind.RECIPE -> "${e.source}: $ins → $outs" + (if (e.levelNote.isNotBlank()) " [${e.levelNote}]" else "")
            EdgeKind.DOSE -> "drink $ins → $outs"
            EdgeKind.SET_PACK -> "pack $ins → $outs"
            EdgeKind.SET_UNPACK -> "unpack $ins → $outs"
            EdgeKind.SUPPLY_DEPOT -> "hand in $ins → $outs"
            EdgeKind.PEG -> "ASSUMED player price: $ins ↔ $outs"
        }
        return PathStep(e.id, e.kind, text, e.guardedBy, e.soft)
    }

    private fun qty(q: Double): String = if (q == Math.floor(q) && q < 1e9) q.toLong().toString() else "%.2f".format(q)

    fun recipeEv(): List<RecipeEv> {
        val out = ArrayList<RecipeEv>()
        for (e in model.edges) {
            if (e.kind != EdgeKind.RECIPE && e.kind != EdgeKind.DOSE && e.kind != EdgeKind.SET_PACK && e.kind != EdgeKind.SET_UNPACK) continue
            var inCost = 0.0
            var known = true
            for (s in e.inputs) { val a = primary.acq[s.node]; if (a == null) { known = false; break }; inCost += a * s.qty }
            if (!known) continue
            val outMin = e.outputs.sumOf { primary.liqOf(it.node) * it.qty * e.evAtMinLevel }
            val outMax = e.outputs.sumOf { primary.liqOf(it.node) * it.qty * e.evAtMaxLevel }
            out += RecipeEv(
                edgeId = e.id, source = e.source,
                inputs = e.inputs.joinToString(" + ") { qty(it.qty) + " " + model.name(it.node) },
                outputs = e.outputs.joinToString(" + ") { qty(it.qty) + " " + model.name(it.node) },
                inputCost = inCost, outputValueEvMin = outMin, outputValueNoFail = outMax,
                profitEvMin = outMin - inCost, profitNoFail = outMax - inCost, note = e.levelNote, guardedBy = e.guardedBy,
            )
        }
        return out.sortedByDescending { it.profitNoFail }
    }

    fun hygiene(): Hygiene {
        val below = ArrayList<Map<String, Any?>>()
        val zero = ArrayList<Map<String, Any?>>()
        val inconsistent = ArrayList<Map<String, Any?>>()
        val sameShop = ArrayList<Map<String, Any?>>()
        val sellByItem = HashMap<Pair<Int, NodeId?>, MutableList<Pair<String, Int>>>()
        for (shop in model.shops) {
            // Shelf prices in a non-coin currency are compared through that currency's gp peg
            // (an NPC "buy tickets" tab, else the --pegs assumption); no peg = no comparison.
            val node = shop.currencyNode
            val peg: Double? = when {
                node == coins -> 1.0
                node != null && model.hardPegs[node] != null -> model.hardPegs[node]!!.toDouble()
                node != null && model.softPegs[node] != null -> model.softPegs[node]!!.toDouble()
                else -> null
            }
            for (w in shop.wares) {
                val sell = w.sellPrice ?: continue
                if (w.cost > 0 && peg != null) {
                    val sellGp = sell * peg
                    val floor70 = (w.cost * 0.7).toInt()
                    val alch60 = (w.cost * 0.6).toInt()
                    if (sellGp < floor70 || sellGp < alch60) below += mapOf(
                        "shop" to shop.name, "item" to (w.key ?: w.name), "cost" to w.cost, "sell" to sell,
                        "sellGp" to sellGp, "buyback70" to floor70, "alch60" to alch60, "currency" to shop.currencyLabel,
                    )
                }
                if (w.cost <= 0 && w.sellPriceSource == "cache") zero += mapOf(
                    "shop" to shop.name, "item" to (w.key ?: w.name), "sell" to sell, "currency" to shop.currencyLabel,
                )
                if (w.buybackAllowed && w.buyback != null && w.buyback > sell) sameShop += mapOf(
                    "shop" to shop.name, "item" to (w.key ?: w.name), "sell" to sell, "buyback" to w.buyback, "currency" to shop.currencyLabel,
                )
                sellByItem.getOrPut(w.item to shop.currencyNode) { ArrayList() } += shop.name to sell
            }
        }
        for ((k, sellers) in sellByItem) {
            if (sellers.map { it.second }.distinct().size > 1) inconsistent += mapOf(
                "item" to (model.items[k.first]?.key ?: k.first), "currency" to k.second?.let { model.name(it) },
                "prices" to sellers.sortedBy { it.second }.map { "${it.first}=${it.second}" },
            )
        }
        val near = ArrayList<Map<String, Any?>>()
        for (n in primary.acq.keys) {
            if (n == coins || n is NodeId.PointsNode) continue
            val a = primary.acqOf(n); val l = primary.liqOf(n)
            if (a <= 0 || a.isInfinite() || l <= 0) continue
            val r = l / a
            if (r >= NEAR_MISS_LOW && r < 1.0 + MIN_MARGIN && a >= 50) near += mapOf(
                "item" to model.key(n), "acquire" to a, "liquidate" to l, "ratio" to r,
                "acquireVia" to primary.acqVia[n]?.id, "liquidateVia" to primary.liqVia[n]?.id,
            )
        }
        return Hygiene(
            below.sortedBy { it["item"].toString() }, zero.sortedBy { it["item"].toString() },
            inconsistent.sortedBy { it["item"].toString() }, sameShop.sortedBy { it["item"].toString() },
            near.sortedByDescending { it["ratio"] as Double },
        )
    }
}
