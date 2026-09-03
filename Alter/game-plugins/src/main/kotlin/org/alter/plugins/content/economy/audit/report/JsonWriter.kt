package org.alter.plugins.content.economy.audit.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.alter.plugins.content.economy.audit.engine.Finding
import org.alter.plugins.content.economy.audit.engine.Hygiene
import org.alter.plugins.content.economy.audit.engine.RecipeEv
import org.alter.plugins.content.economy.audit.engine.Valuation
import org.alter.plugins.content.economy.audit.extract.CoverageExtractor
import org.alter.plugins.content.economy.audit.model.EconModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.NodeId
import java.io.File

/** Deterministic JSON (sorted keys, stable ordering) so a re-run after a reprice diffs cleanly. */
object JsonWriter {
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    class Report(
        val header: Map<String, Any?>,
        val model: EconModel,
        /** NO_FAIL / guards-on valuation, so every shelf line carries its NPC acquire/liquidate gp. */
        val primary: Valuation,
        val findings: List<Finding>,
        val prevented: List<Finding>,
        val hygiene: Hygiene,
        val recipeEv: List<RecipeEv>,
        val coverage: CoverageExtractor.Result,
        val brokenAdapters: List<Pair<String, String>>,
        val rateTable: List<Pair<String, String>>,
    )

    fun write(file: File, r: Report) {
        val root = LinkedHashMap<String, Any?>()
        root["header"] = r.header
        root["assumptions"] = mapOf(
            "geBackstopEnabled" to true,
            "hardPegs" to r.model.hardPegs.entries.associate { r.model.key(it.key) to it.value },
            "softPegs" to r.model.softPegs.entries.associate { r.model.key(it.key) to it.value },
            "notes" to r.model.notes,
        )
        root["shops"] = r.model.shops.map { s ->
            mapOf(
                "name" to s.name, "currency" to s.currencyLabel, "currencyClass" to s.currencyClass,
                "currencyNode" to s.currencyNode?.toString(), "policy" to s.policy, "sellOnly" to s.sellOnly,
                "wares" to s.wares.map { w ->
                    val node = NodeId.ItemNode(r.model.items[w.item]?.unnotedId ?: w.item)
                    mapOf(
                        "item" to w.item, "key" to w.key, "name" to w.name, "cost" to w.cost, "amount" to w.amount,
                        "unlimited" to w.unlimited, "sellPrice" to w.sellPrice, "sellPriceSource" to w.sellPriceSource,
                        "buyback" to w.buyback, "buybackSource" to w.buybackSource, "buybackAllowed" to w.buybackAllowed,
                        "buybackDeny" to w.buybackDeny,
                        "npcAcquireGp" to nz(r.primary.acqOf(node)), "npcLiquidateGp" to nz(r.primary.liqOf(node)),
                        "acquireVia" to r.primary.acqVia[node]?.id, "liquidateVia" to r.primary.liqVia[node]?.id,
                    )
                },
            )
        }
        root["recipes"] = r.model.edges.filter { it.kind.name == "RECIPE" || it.kind.name == "DOSE" || it.kind.name.startsWith("SET_") }
            .sortedBy { it.id }.map { edge(it, r.model) }
        root["findings"] = r.findings.map { finding(it) }
        root["preventedByGuards"] = r.prevented.map { finding(it) }
        root["hygiene"] = mapOf(
            "belowCost" to r.hygiene.belowCost, "zeroCostWares" to r.hygiene.zeroCostWares,
            "inconsistentSell" to r.hygiene.inconsistentSell, "sameShopLoop" to r.hygiene.sameShopLoop,
            "nearMiss" to r.hygiene.nearMiss,
        )
        root["recipeEv"] = r.recipeEv.map {
            mapOf(
                "edge" to it.edgeId, "source" to it.source, "inputs" to it.inputs, "outputs" to it.outputs,
                "inputCost" to it.inputCost, "outputValueEvMin" to it.outputValueEvMin, "outputValueNoFail" to it.outputValueNoFail,
                "profitEvMin" to it.profitEvMin, "profitNoFail" to it.profitNoFail, "note" to it.note, "guardedBy" to it.guardedBy,
            )
        }
        root["coverage"] = mapOf(
            "boundItemOnItem" to r.coverage.boundItemPairs, "boundItemOnObject" to r.coverage.boundObjPairs,
            "unexplained" to r.coverage.unexplained.map { mapOf("kind" to it.kind, "a" to it.a, "b" to it.b) },
            "allowlisted" to r.coverage.allowlisted.map { mapOf("kind" to it.kind, "a" to it.a, "b" to it.b) },
            "brokenAdapters" to r.brokenAdapters.map { mapOf("adapter" to it.first, "error" to it.second) },
        )
        root["rateTable"] = r.rateTable.associate { it.first to it.second }
        file.parentFile?.mkdirs()
        file.writeText(mapper.writeValueAsString(root) + "\n", Charsets.UTF_8)
    }

    private fun edge(e: Edge, m: EconModel): Map<String, Any?> = mapOf(
        "id" to e.id, "kind" to e.kind.name, "source" to e.source, "category" to e.category.name,
        "inputs" to e.inputs.map { mapOf("node" to m.key(it.node), "qty" to it.qty) },
        "outputs" to e.outputs.map { mapOf("node" to m.key(it.node), "qty" to it.qty) },
        "ticksPerUnit" to e.ticksPerUnit, "evAtMinLevel" to e.evAtMinLevel, "evAtMaxLevel" to e.evAtMaxLevel,
        "levelNote" to e.levelNote, "guardedBy" to e.guardedBy,
    )

    private fun finding(f: Finding): Map<String, Any?> = mapOf(
        "id" to f.id, "loopClass" to f.loopClass.name, "severity" to f.severity.name, "item" to f.itemKey, "name" to f.itemName,
        "acquireGp" to f.acquireGp, "liquidateGp" to f.liquidateGp, "unitProfit" to f.unitProfit,
        "marginPct" to (if (f.marginPct.isInfinite()) null else f.marginPct),
        "acquireGpEvMin" to nz(f.acquireGpEvMin), "liquidateGpEvMin" to f.liquidateGpEvMin,
        "acquirePath" to f.acquirePath.map { mapOf("edge" to it.edgeId, "kind" to it.kind.name, "text" to it.text, "guardedBy" to it.guardedBy, "soft" to it.soft) },
        "liquidatePath" to f.liquidatePath.map { mapOf("edge" to it.edgeId, "kind" to it.kind.name, "text" to it.text, "guardedBy" to it.guardedBy, "soft" to it.soft) },
        "unitsPerHourFirstHour" to f.throughput.unitsFirstHour, "unitsPerHourSustained" to f.throughput.unitsSustained,
        "limitingFactor" to f.throughput.limitingFactor,
        "gpPerHourFirstHour" to f.gpPerHourFirstHour, "gpPerHourSustained" to f.gpPerHourSustained,
        "unbounded" to f.unbounded, "guardedBy" to f.guardedBy, "soft" to f.soft, "sameShop" to f.sameShop, "levelNotes" to f.levelNotes,
        "related" to f.related,
    )

    private fun nz(d: Double): Any? = if (d.isInfinite() || d.isNaN()) null else d
}
