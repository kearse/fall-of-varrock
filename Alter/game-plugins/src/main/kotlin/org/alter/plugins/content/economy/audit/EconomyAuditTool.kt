package org.alter.plugins.content.economy.audit

import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.engine.ArbitrageEngine
import org.alter.plugins.content.economy.audit.engine.EngineConfig
import org.alter.plugins.content.economy.audit.engine.EvMode
import org.alter.plugins.content.economy.audit.engine.FindingsBuilder
import org.alter.plugins.content.economy.audit.engine.Severity
import org.alter.plugins.content.economy.audit.engine.Valuation
import org.alter.plugins.content.economy.audit.extract.CoverageExtractor
import org.alter.plugins.content.economy.audit.extract.ItemExtractor
import org.alter.plugins.content.economy.audit.extract.ItemSetExtractor
import org.alter.plugins.content.economy.audit.extract.RecipeReflection
import org.alter.plugins.content.economy.audit.extract.ShopExtractor
import org.alter.plugins.content.economy.audit.extract.SinkExtractor
import org.alter.plugins.content.economy.audit.model.EconModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.Stack
import org.alter.plugins.content.economy.audit.report.JsonWriter
import org.alter.plugins.content.economy.audit.report.MarkdownWriter
import org.alter.rscm.RSCM.getRSCM
import java.io.File
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * **Economy arbitrage auditor** — Team 2's re-runnable check for every NPC value loop.
 *
 * ```
 * gradlew :game-plugins:economyAudit "-PeconCache=<path to data/cache>" [-PeconMode=audit|selftest]
 *         [-PeconPegs=boss_ticket=1000,blood_money=800] [-PeconFlags=assume-tp-stock,no-guards]
 * ```
 * Writes `docs/economy-arbitrage-audit.{md,json}`. Exit code 1 when any S0/S1 loop, unexplained
 * converter bind or broken recipe adapter exists, so it can gate a CI job later.
 */
object EconomyAuditTool {

    class Args(
        val cache: Path,
        val out: File,
        val mode: String,
        val pegs: Map<String, Int>,
        val flags: Set<String>,
    )

    fun parse(argv: Array<String>): Args {
        var cache = "../data/cache"
        var out = "../../docs"
        var mode = "audit"
        var pegs = "boss_ticket=1000,blood_money=800,vote_ticket=2000,points:DONOR=4444"
        var flags = ""
        for (a in argv) {
            when {
                a.startsWith("--cache=") -> cache = a.removePrefix("--cache=")
                a.startsWith("--out=") -> out = a.removePrefix("--out=")
                a.startsWith("--mode=") -> mode = a.removePrefix("--mode=")
                a.startsWith("--pegs=") -> pegs = a.removePrefix("--pegs=")
                a.startsWith("--flags=") -> flags = a.removePrefix("--flags=")
            }
        }
        val pegMap = pegs.split(',').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2); k.trim() to v.trim().toInt()
        }
        return Args(Path.of(cache), File(out), mode, pegMap, flags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    class Run(
        val model: EconModel,
        val primary: Valuation,
        val evMin: Valuation,
        val unguarded: Valuation,
        val builder: FindingsBuilder,
        val recipes: RecipeReflection.Result,
        val coverage: CoverageExtractor.Result,
        val booted: OfflineBoot.Booted,
    )

    fun build(args: Args): Run {
        val booted = OfflineBoot.boot(args.cache)
        val world = booted.world
        val coins = getRSCM("item.coins_995")
        val itemKeys = ItemExtractor.rscmItemKeys()
        val items = ItemExtractor.extract(itemKeys)
        val shops = ShopExtractor.extract(world, items)
        val alch = SinkExtractor.alchEdges(items, coins)
        val ge = SinkExtractor.geEdges(items, coins)
        val sets = ItemSetExtractor.extract(items)
        val recipes = RecipeReflection.extract(booted.plugins, items, coins)
        val objKeys = rscmKeys("object")
        val coverage = CoverageExtractor.extract(world, items, objKeys, recipes)

        val notes = ArrayList<String>()
        val coinsNode = NodeId.ItemNode(coins)
        val softPegs = LinkedHashMap<NodeId, Int>()
        val pegEdges = ArrayList<Edge>()
        for ((k, price) in args.pegs) {
            val node: NodeId = if (k.startsWith("points:")) NodeId.PointsNode(k.removePrefix("points:")) else {
                val id = runCatching { getRSCM(if (k.startsWith("item.")) k else "item.$k") }.getOrNull() ?: continue
                NodeId.ItemNode(id)
            }
            if (node in shops.hardPegs) continue // an NPC sells it for coins: the hard peg wins
            softPegs[node] = price
            pegEdges += Edge(
                id = "peg:buy:$k", kind = EdgeKind.PEG, source = "--pegs", inputs = listOf(Stack(coinsNode, price.toDouble())),
                outputs = listOf(Stack(node, 1.0)), ticksPerUnit = ActionTimeModel.CLICK_TICKS, soft = true,
            )
            pegEdges += Edge(
                id = "peg:sell:$k", kind = EdgeKind.PEG, source = "--pegs", inputs = listOf(Stack(node, 1.0)),
                outputs = listOf(Stack(coinsNode, price.toDouble())), ticksPerUnit = ActionTimeModel.CLICK_TICKS, soft = true,
            )
        }
        val tpEdges = ArrayList<Edge>()
        if ("assume-tp-stock" in args.flags) {
            notes += "assume-tp-stock: the Trading Post is modelled as SELLING every tradeable at 100% cost (a player listed it)."
            for (info in items.values) {
                if (info.noted || !info.tradeable || info.cost <= 0) continue
                tpEdges += Edge(
                    id = "shop:Trading Post:sell:${info.key ?: info.id}", kind = EdgeKind.SHOP_SELL, source = "Trading Post",
                    inputs = listOf(Stack(coinsNode, info.cost.toDouble())), outputs = listOf(Stack(NodeId.ItemNode(info.id), 1.0)),
                    ticksPerUnit = ActionTimeModel.shopTicks(info.stackable), soft = true, shopName = "Trading Post",
                )
            }
        }
        if (booted.failed.isNotEmpty()) notes += "plugins that failed to construct offline: " + booted.failed.joinToString("; ") { "${it.first.substringAfterLast('.')} (${it.second})" }
        if (recipes.broken.isNotEmpty()) notes += "RECIPE_ADAPTER_BROKEN: " + recipes.broken.joinToString("; ") { "${it.first}: ${it.second}" }

        val model = EconModel(
            items = items,
            edges = shops.edges + alch + ge + sets + recipes.edges + pegEdges + tpEdges,
            coinsId = coins,
            hardPegs = shops.hardPegs,
            softPegs = softPegs,
            shops = shops.shops,
            notes = notes,
        )
        val guardsOn = "no-guards" !in args.flags
        val primary = ArbitrageEngine(model, EngineConfig(EvMode.NO_FAIL, guardsEnabled = guardsOn)).run()
        val evMin = ArbitrageEngine(model, EngineConfig(EvMode.EV_MIN_LEVEL, guardsEnabled = guardsOn)).run()
        val unguarded = ArbitrageEngine(model, EngineConfig(EvMode.NO_FAIL, guardsEnabled = false)).run()
        val builder = FindingsBuilder(model, primary, evMin, unguarded, shops.currencyNodes)
        return Run(model, primary, evMin, unguarded, builder, recipes, coverage, booted)
    }

    fun rscmKeys(prefix: String): Map<Int, String> {
        val file = File("../data/cfg/rscm/$prefix.rscm")
        if (!file.isFile) return emptyMap()
        val out = HashMap<Int, String>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val i = line.lastIndexOf(':')
                if (i <= 0) return@forEach
                val id = line.substring(i + 1).trim().toIntOrNull() ?: return@forEach
                out.putIfAbsent(id, "$prefix." + line.substring(0, i).trim())
            }
        }
        return out
    }

    private fun gitSha(): String = runCatching {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }.getOrDefault("unknown")

    @JvmStatic
    fun main(argv: Array<String>) {
        val args = parse(argv)
        val started = System.currentTimeMillis()
        val run = build(args)
        val findings = run.builder.findings()
        val prevented = run.builder.preventedByGuards()
        val hygiene = run.builder.hygiene()
        val recipeEv = run.builder.recipeEv()
        val header = linkedMapOf<String, Any?>(
            "generatedAt" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "gitSha" to gitSha(),
            "cachePath" to args.cache.toAbsolutePath().normalize().toString(),
            "rerun" to "gradlew :game-plugins:economyAudit \"-PeconCache=<cache dir>\"",
            "pluginsConstructed" to run.booted.plugins.size,
            "pluginsFailed" to run.booted.failed.size,
            "flags" to args.flags.sorted(),
            "toolVersion" to 1,
            "edges" to run.model.edges.size,
            "items" to run.model.items.size,
        )
        val report = JsonWriter.Report(header, run.model, run.primary, findings, prevented, hygiene, recipeEv, run.coverage, run.recipes.broken, ActionTimeModel.rateTable)
        if (args.mode == "selftest") {
            val ok = SelfTest.run(run)
            println("selftest: ${if (ok) "ALL PASS" else "FAILURES"} (${System.currentTimeMillis() - started} ms)")
            exitProcess(if (ok) 0 else 1)
        }
        val json = File(args.out, "economy-arbitrage-audit.json")
        val md = File(args.out, "economy-arbitrage-audit.md")
        JsonWriter.write(json, report)
        MarkdownWriter.write(md, report)

        println("economyAudit: ${run.model.shops.size} shops, ${run.model.edges.size} edges, ${run.model.items.size} items; " +
            "${findings.size} findings (${findings.count { it.severity == Severity.S0 }} S0, ${findings.count { it.severity == Severity.S1 }} S1), " +
            "${prevented.size} prevented by guards, ${run.coverage.unexplained.size} unexplained binds, ${run.recipes.broken.size} broken adapters " +
            "(${System.currentTimeMillis() - started} ms)")
        run.booted.failed.forEach { println("  plugin failed offline: ${it.first} -> ${it.second}") }
        run.recipes.broken.forEach { println("  RECIPE_ADAPTER_BROKEN: ${it.first} -> ${it.second}") }
        run.coverage.unexplained.forEach { println("  UNEXPLAINED_CONVERTER: ${it.kind} ${it.a} x ${it.b}") }
        findings.take(15).forEach {
            println("  [${it.severity}] ${it.loopClass.name} ${it.itemKey}: acquire ${"%.0f".format(it.acquireGp)} -> liquidate ${"%.0f".format(it.liquidateGp)} (${"%,.0f".format(it.gpPerHourSustained)} gp/h)")
        }
        println("wrote ${md.absolutePath} and ${json.absolutePath}")
        val bad = findings.any { it.severity == Severity.S0 || it.severity == Severity.S1 } || run.coverage.unexplained.isNotEmpty() || run.recipes.broken.isNotEmpty()
        exitProcess(if (bad) 1 else 0)
    }
}

fun main(argv: Array<String>) = EconomyAuditTool.main(argv)
