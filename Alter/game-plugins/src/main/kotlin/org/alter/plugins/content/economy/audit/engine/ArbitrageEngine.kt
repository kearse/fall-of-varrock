package org.alter.plugins.content.economy.audit.engine

import org.alter.plugins.content.economy.audit.model.EconModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.NodeId

enum class EvMode { EV_MIN_LEVEL, NO_FAIL }

data class EngineConfig(
    val evMode: EvMode = EvMode.NO_FAIL,
    val guardsEnabled: Boolean = true,
    val includeSoft: Boolean = true,
    /** Relaxation rounds (≥ the longest sensible chain). Nodes still improving afterwards = UNBOUNDED. */
    val maxDepth: Int = 8,
    /** Only the most expensive input of a multi-input edge inherits the edge's liquidation value. */
    val dominantOnly: Boolean = true,
)

/**
 * Result of one relaxation run: for every node, the cheapest gp cost to OBTAIN one unit from NPC
 * systems (`acq`) and the most gp one unit can be LIQUIDATED for through NPC systems (`liq`),
 * with the edge that achieved each value (for path reconstruction).
 */
class Valuation(
    val config: EngineConfig,
    val acq: Map<NodeId, Double>,
    val liq: Map<NodeId, Double>,
    val acqVia: Map<NodeId, Edge>,
    val liqVia: Map<NodeId, Edge>,
    val unbounded: Set<NodeId>,
) {
    fun acqOf(n: NodeId) = acq[n] ?: Double.POSITIVE_INFINITY
    fun liqOf(n: NodeId) = liq[n] ?: 0.0
}

/**
 * Two-direction Bellman-Ford style relaxation over the value graph.
 *
 *  acq[coins] = 1, acq[x] = +inf;  for every edge, each output can be obtained for the FULL input
 *  cost divided by (its quantity × EV) — conservative for multi-output recipes.
 *  liq[coins] = 1, liq[x] = 0;     for every edge, each input can be liquidated for the output value
 *  minus what the OTHER inputs cost to obtain, divided by its quantity.
 *
 * Coins are pinned at 1 in both directions; anything still improving after [EngineConfig.maxDepth]
 * rounds is a positive cycle and is reported as UNBOUNDED rather than as a number.
 */
class ArbitrageEngine(private val model: EconModel, private val config: EngineConfig) {

    /**
     * Edges the relaxation walks. Set-box pack/unpack pairs are value-neutral bijections that the
     * "other inputs at acquisition cost" rule double-counts into a runaway cycle (pack credits the
     * pieces' liquidation value, unpack charges their acquisition cost), so they are excluded here
     * and judged once, non-recursively, in the recipe-EV table instead.
     */
    private val active: List<Edge> = model.edges.filter { e ->
        (e.guardedBy == null || !config.guardsEnabled) && (!e.soft || config.includeSoft) &&
            e.kind != EdgeKind.SET_PACK && e.kind != EdgeKind.SET_UNPACK
    }

    private fun ev(e: Edge): Double = if (config.evMode == EvMode.EV_MIN_LEVEL) e.evAtMinLevel else e.evAtMaxLevel

    fun run(): Valuation {
        val coins = model.coins
        val acq = HashMap<NodeId, Double>()
        val liq = HashMap<NodeId, Double>()
        val acqVia = HashMap<NodeId, Edge>()
        val liqVia = HashMap<NodeId, Edge>()
        acq[coins] = 1.0
        liq[coins] = 1.0

        fun round(): Set<NodeId> {
            val changed = HashSet<NodeId>()
            for (e in active) {
                val ev = ev(e)
                if (ev <= 0.0) continue
                // ---- acquire: outputs from inputs ----
                var inCost = 0.0
                var inKnown = true
                for (s in e.inputs) {
                    val a = acq[s.node]
                    if (a == null) { inKnown = false; break }
                    inCost += a * s.qty
                }
                if (inKnown) {
                    for (o in e.outputs) {
                        if (o.node == coins) continue
                        val cand = inCost / (o.qty * ev)
                        val cur = acq[o.node]
                        if (cur == null || cand < cur - EPS) {
                            acq[o.node] = cand; acqVia[o.node] = e; changed += o.node
                        }
                    }
                }
                // ---- liquidate: inputs from outputs ----
                var outValue = 0.0
                for (o in e.outputs) outValue += (liq[o.node] ?: 0.0) * o.qty * ev
                if (outValue <= 0.0) continue
                for (s in e.inputs) {
                    if (s.node == coins) continue
                    var others = 0.0
                    var othersKnown = true
                    var dominant = true
                    val myCost = (acq[s.node] ?: Double.POSITIVE_INFINITY) * s.qty
                    for (t in e.inputs) {
                        if (t === s) continue
                        val a = acq[t.node]
                        if (a == null) { othersKnown = false; break }
                        others += a * t.qty
                        if (t.node != coins && a * t.qty > myCost) dominant = false
                    }
                    if (!othersKnown) continue
                    // Liquidation value belongs to the input that IS the thing being converted: a
                    // 4 gp nature rune does not "liquidate" for the platebody it alchs.
                    if (config.dominantOnly && !dominant) continue
                    val cand = (outValue - others) / s.qty
                    val cur = liq[s.node] ?: 0.0
                    if (cand > cur + EPS) {
                        liq[s.node] = cand; liqVia[s.node] = e; changed += s.node
                    }
                }
            }
            return changed
        }

        var lastChanged: Set<NodeId> = emptySet()
        for (i in 0 until config.maxDepth) {
            lastChanged = round()
            if (lastChanged.isEmpty()) break
        }
        val unbounded: Set<NodeId> = if (lastChanged.isEmpty()) emptySet() else round()
        return Valuation(config, acq, liq, acqVia, liqVia, unbounded)
    }

    companion object {
        const val EPS = 1e-6

        /**
         * The chain of edges that produced `acq[node]`: the edge, then (recursively) the most
         * expensive non-coin input's own acquire chain. Bounded by [maxLen].
         */
        fun acquirePath(v: Valuation, node: NodeId, coins: NodeId, maxLen: Int = 8): List<Edge> {
            val out = ArrayList<Edge>()
            var cur = node
            val seen = HashSet<NodeId>()
            while (out.size < maxLen && seen.add(cur)) {
                val e = v.acqVia[cur] ?: break
                out += e
                val next = e.inputs.filter { it.node != coins }
                    .maxByOrNull { (v.acq[it.node] ?: 0.0) * it.qty } ?: break
                cur = next.node
            }
            return out.reversed()
        }

        /** The chain of edges that produced `liq[node]`, following the most valuable non-coin output. */
        fun liquidatePath(v: Valuation, node: NodeId, coins: NodeId, maxLen: Int = 8): List<Edge> {
            val out = ArrayList<Edge>()
            var cur = node
            val seen = HashSet<NodeId>()
            while (out.size < maxLen && seen.add(cur)) {
                val e = v.liqVia[cur] ?: break
                out += e
                val next = e.outputs.filter { it.node != coins }
                    .maxByOrNull { (v.liq[it.node] ?: 0.0) * it.qty } ?: break
                if ((v.liq[next.node] ?: 0.0) <= 0.0) break
                cur = next.node
            }
            return out
        }
    }
}
