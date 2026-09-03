package org.alter.plugins.content.economy.audit

import org.alter.plugins.content.economy.audit.engine.LoopClass
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.rscm.RSCM.getRSCM

/**
 * `--mode=selftest`: spot checks against known shelf lines, computed from the SAME cache values the
 * live shop engine uses, so a wrong expectation fails loudly with both numbers printed.
 */
object SelfTest {

    private class Check(val name: String, val ok: Boolean, val detail: String)

    fun run(audit: EconomyAuditTool.Run): Boolean {
        val checks = ArrayList<Check>()
        val model = audit.model
        val v = audit.primary
        val findings = audit.builder.findings()
        val prevented = audit.builder.preventedByGuards()
        fun id(key: String) = runCatching { getRSCM(key) }.getOrNull()
        fun node(key: String) = id(key)?.let { NodeId.ItemNode(it) }
        fun sellEdge(shop: String, key: String) = model.edges.firstOrNull { it.kind == EdgeKind.SHOP_SELL && it.shopName == shop && it.outputs.single().node == node(key) }
        fun buyEdge(shop: String, key: String) = model.edges.firstOrNull { it.kind == EdgeKind.SHOP_BUYBACK && it.shopName == shop && it.inputs.single().node == node(key) }
        fun findingFor(key: String) = findings.filter { it.node == node(key) }
        fun cost(key: String) = id(key)?.let { model.items[it]?.cost } ?: -1

        // 1. Hunter traps: explicit shelf prices (25 / 75) vs 70% cache buyback.
        for ((key, shelf) in listOf("item.bird_snare" to 25, "item.box_trap" to 75)) {
            val sell = sellEdge("Lumbridge Skilling Tools", key)
            val buy = buyEdge("Lumbridge Skilling Tools", key)
            val c = cost(key)
            val expectBuy = (c * 0.7).toInt().coerceAtLeast(1)
            val sameShop = buy != null && buy.guardedBy == null && buy.outputs.single().qty > (sell?.inputs?.single()?.qty ?: 0.0)
            checks += Check(
                "$key shelf $shelf, cost $c, buyback == ${expectBuy}",
                sell != null && sell.inputs.single().qty == shelf.toDouble() && buy != null && buy.outputs.single().qty == expectBuy.toDouble(),
                "sell=${sell?.inputs?.single()?.qty} buyback=${buy?.outputs?.single()?.qty} deny=${buy?.guardedBy} sameShopLoop=$sameShop",
            )
        }

        // 2. death_rune 270 at the Magic Store: unlimited => no buyback edge; not a commodity; alch < 270.
        run {
            val key = "item.death_rune"
            val sell = sellEdge("Lumbridge Magic Store", key)
            val buy = buyEdge("Lumbridge Magic Store", key)
            val info = id(key)?.let { model.items[it] }
            val alch = model.edges.firstOrNull { it.kind == EdgeKind.ALCH_HIGH && it.inputs.first().node == node(key) }
            val alchValue = alch?.outputs?.single()?.qty ?: 0.0
            checks += Check(
                "death_rune: shelf 270, no buyback (unlimited), not commodity, alch < 270, no finding",
                sell?.inputs?.single()?.qty == 270.0 && (buy == null || buy.guardedBy != null) && info?.isCommodity == false && alchValue < 270 && findingFor(key).isEmpty(),
                "sell=${sell?.inputs?.single()?.qty} buybackDeny=${buy?.guardedBy} commodity=${info?.isCommodity} alch=$alchValue findings=${findingFor(key).map { it.loopClass }}",
            )
        }

        // 3. adamant_arrow 120 premium: no finding.
        run {
            val key = "item.adamant_arrow"
            val sell = sellEdge("Lumbridge Ranged Gear", key)
            checks += Check("adamant_arrow: shelf 120, no finding", sell?.inputs?.single()?.qty == 120.0 && findingFor(key).isEmpty(),
                "sell=${sell?.inputs?.single()?.qty} cost=${cost(key)} liq=${v.liqOf(node(key)!!)} findings=${findingFor(key).map { it.loopClass }}")
        }

        // 4. cooked swordfish 160: raw swordfish not NPC-sold => acquire == 160 via the Fish Stall; shark recipe in EV table.
        run {
            val key = "item.swordfish"
            val acq = v.acqOf(node(key)!!)
            val sharkRecipe = audit.builder.recipeEv().firstOrNull { it.edgeId == "recipe:cooking.shark" }
            checks += Check("swordfish: acquire 160 (no NPC raw route), no finding; cooking.shark in recipe EV",
                acq == 160.0 && findingFor(key).isEmpty() && sharkRecipe != null,
                "acq=$acq via=${v.acqVia[node(key)!!]?.id} findings=${findingFor(key).map { it.loopClass }} shark=${sharkRecipe?.let { "EV@min ${it.profitEvMin} / no-fail ${it.profitNoFail}" }}")
        }

        // 5. justiciar_chestguard (Boss Ticket ware): guarded => in 'prevented', not in findings.
        run {
            val key = "item.justiciar_chestguard"
            val alch = model.edges.firstOrNull { it.kind == EdgeKind.ALCH_HIGH && it.inputs.first().node == node(key) }
            // Any live guard may be the one that stops the best liquidation (today it is the general
            // store's cost cap, since 70% there beats the guarded alch/Trading Post routes).
            val inPrevented = prevented.any { it.node == node(key) && it.guardedBy.isNotEmpty() }
            val unguardedProfit = audit.unguarded.liqOf(node(key)!!) - audit.unguarded.acqOf(node(key)!!)
            checks += Check("justiciar_chestguard: alch guarded by SpecialShopGuard; not in findings; in prevented (some guard) iff unguarded profit > 0",
                alch?.guardedBy == "SpecialShopGuard" && findingFor(key).isEmpty() && (inPrevented == (unguardedProfit > 0)),
                "alchGuard=${alch?.guardedBy} acq=${v.acqOf(node(key)!!)} alch=${alch?.outputs?.single()?.qty} unguardedProfit=$unguardedProfit prevented=$inPrevented findings=${findingFor(key).size}")
        }

        // 6. gilded_platebody (Vote Ticket ware): guarded since PR 2 (the hub ticketShop registers its
        //    gear/cosmetic wares); a shark from the same shelf must stay vendorable (guarded = false).
        run {
            val key = "item.gilded_platebody"
            val alch = model.edges.firstOrNull { it.kind == EdgeKind.ALCH_HIGH && it.inputs.first().node == node(key) }
            val shark = model.edges.firstOrNull { it.kind == EdgeKind.ALCH_HIGH && it.inputs.first().node == node("item.shark") }
            checks += Check("gilded_platebody: alch guarded by SpecialShopGuard; shark (same shelf, a supply) is not",
                alch != null && alch.guardedBy == "SpecialShopGuard" && shark != null && shark.guardedBy == null,
                "alchGuard=${alch?.guardedBy} alchValue=${alch?.outputs?.single()?.qty} acq=${v.acqOf(node(key)!!)} liq=${v.liqOf(node(key)!!)} findings=${findingFor(key).map { "${it.loopClass}/${it.severity}" }}")
        }

        // 7. ranarr_seed -> Farming x2 -> NPC buyback / GE floor.
        run {
            val recipe = model.edges.firstOrNull { it.id == "recipe:farming.ranarr_weed" }
            val seed = node("item.ranarr_seed")!!
            val weed = node("item.ranarr_weed")!!
            val expectLoop = 2 * v.liqOf(weed) > v.acqOf(seed)
            // The seed/weed may be folded into a longer loop's finding (unf potion -> prayer potion -> general store).
            val f = findingFor("item.ranarr_seed").firstOrNull() ?: findingFor("item.ranarr_weed").firstOrNull()
                ?: findings.firstOrNull { "item.ranarr_seed" in it.related || "item.ranarr_weed" in it.related }
            val units = if (f != null) f.throughput.unitsFirstHour else 0.0
            checks += Check("ranarr_seed: farming recipe present; loop iff 2*liq(ranarr) > acq(seed) [${expectLoop}]",
                recipe != null && (expectLoop == (f != null)),
                "acq(seed)=${v.acqOf(seed)} liq(weed)=${v.liqOf(weed)} liq(seed)=${v.liqOf(seed)} finding=${f?.loopClass}/${f?.severity} units/h=$units ticks=${recipe?.ticksPerUnit}")
        }

        // 8. Reconciliation: every snapshot price equals the live engine expression.
        run {
            var mismatches = 0
            var checked = 0
            val world = audit.booted.world
            for (shop in world.plugins.shops.values.toList()) {
                val snap = model.shops.firstOrNull { it.name == shop.name } ?: continue
                for (si in shop.items.toList().filterNotNull()) {
                    val w = snap.wares.firstOrNull { it.item == si.item } ?: continue
                    checked++
                    if (si.amount > 0 && !shop.currency.sellOnly()) {
                        val expect = si.sellPrice ?: shop.currency.getSellPrice(world, si.item)
                        if (w.sellPrice != expect) mismatches++
                    }
                }
            }
            checks += Check("reconciliation: snapshot sell prices == live ItemCurrency expression ($checked wares)", mismatches == 0, "mismatches=$mismatches")
        }

        // 9. Coverage + adapters.
        checks += Check("coverage: no unexplained converter binds", audit.coverage.unexplained.isEmpty(), audit.coverage.unexplained.joinToString { "${it.a}x${it.b}" })
        checks += Check("recipe adapters all read", audit.recipes.broken.isEmpty(), audit.recipes.broken.joinToString { "${it.first}: ${it.second}" })
        checks += Check("loop classes: every finding classified", findings.none { it.loopClass == LoopClass.OTHER }, findings.filter { it.loopClass == LoopClass.OTHER }.joinToString { it.itemKey })

        for (c in checks) println("${if (c.ok) "PASS" else "FAIL"}  ${c.name}\n      ${c.detail}")
        return checks.all { it.ok }
    }
}
