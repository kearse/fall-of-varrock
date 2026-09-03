package org.alter.plugins.content.economy.audit

import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.engine.ArbitrageEngine
import org.alter.plugins.content.economy.audit.engine.EngineConfig
import org.alter.plugins.content.economy.audit.engine.EvMode
import org.alter.plugins.content.economy.audit.engine.FindingsBuilder
import org.alter.plugins.content.economy.audit.engine.LoopClass
import org.alter.plugins.content.economy.audit.model.EconModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.RecipeCategory
import org.alter.plugins.content.economy.audit.model.Stack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests of the relaxation engine + findings classifier on tiny synthetic graphs (no cache,
 * no world): each of the eight loop classes, the guard/EV modes, and the unbounded-cycle rail.
 */
class ArbitrageEngineTest {

    private val coins = 995
    private fun item(id: Int) = NodeId.ItemNode(id)
    private fun info(id: Int, cost: Int, name: String = "i$id") = ItemInfo(id, "item.$name", name, cost, true, false, id, false, null, null, false, false, false)

    private fun sell(shop: String, id: Int, price: Double, stock: Int? = null, currency: NodeId = item(coins), soft: Boolean = false) = Edge(
        "shop:$shop:sell:$id", EdgeKind.SHOP_SELL, shop, listOf(Stack(currency, price)), listOf(Stack(item(id), 1.0)),
        ActionTimeModel.SHOP_NONSTACK_TICKS, stock = stock, shopName = shop, soft = soft,
    )

    private fun buy(shop: String, id: Int, price: Double, currency: NodeId = item(coins), guard: String? = null) = Edge(
        "shop:$shop:buy:$id", EdgeKind.SHOP_BUYBACK, shop, listOf(Stack(item(id), 1.0)), listOf(Stack(currency, price)),
        ActionTimeModel.SHOP_NONSTACK_TICKS, guardedBy = guard, shopName = shop,
    )

    private fun alch(id: Int, value: Double, guard: String? = null) = Edge(
        "alch:high:$id", EdgeKind.ALCH_HIGH, "AlchemyPlugin", listOf(Stack(item(id), 1.0)), listOf(Stack(item(coins), value)),
        ActionTimeModel.HIGH_ALCH_TICKS, guardedBy = guard,
    )

    private fun recipe(id: String, ins: List<Pair<Int, Double>>, outs: List<Pair<Int, Double>>, evMin: Double = 1.0, evMax: Double = 1.0, cat: RecipeCategory = RecipeCategory.CRAFT) = Edge(
        id, EdgeKind.RECIPE, "test", ins.map { Stack(item(it.first), it.second) }, outs.map { Stack(item(it.first), it.second) },
        ActionTimeModel.CLICK_TICKS, evAtMinLevel = evMin, evAtMaxLevel = evMax, category = cat,
    )

    private fun model(edges: List<Edge>, vararg items: ItemInfo, hardPegs: Map<NodeId, Int> = emptyMap()) = EconModel(
        items = (items.toList() + info(coins, 1, "coins_995")).associateBy { it.id },
        edges = edges, coinsId = coins, hardPegs = hardPegs, softPegs = emptyMap(), shops = emptyList(), notes = emptyList(),
    )

    private fun builder(m: EconModel, currencies: Set<NodeId> = setOf(item(coins))): FindingsBuilder {
        val primary = ArbitrageEngine(m, EngineConfig(EvMode.NO_FAIL, guardsEnabled = true)).run()
        val evMin = ArbitrageEngine(m, EngineConfig(EvMode.EV_MIN_LEVEL, guardsEnabled = true)).run()
        val unguarded = ArbitrageEngine(m, EngineConfig(EvMode.NO_FAIL, guardsEnabled = false)).run()
        return FindingsBuilder(m, primary, evMin, unguarded, currencies)
    }

    @Test
    fun `shop to alch loop is found with the right path and profit`() {
        val m = model(listOf(sell("A", 1, 100.0), alch(1, 120.0)), info(1, 200))
        val f = builder(m).findings().single()
        assertEquals(LoopClass.SHOP_TO_ALCH, f.loopClass)
        assertEquals(100.0, f.acquireGp)
        assertEquals(120.0, f.liquidateGp)
        assertEquals(20.0, f.unitProfit)
        assertEquals(listOf(EdgeKind.SHOP_SELL), f.acquirePath.map { it.kind })
        assertEquals(listOf(EdgeKind.ALCH_HIGH), f.liquidatePath.map { it.kind })
    }

    @Test
    fun `shop A to shop B and same-shop loops are classified`() {
        val ab = builder(model(listOf(sell("A", 1, 100.0), buy("B", 1, 110.0)), info(1, 100))).findings().single()
        assertEquals(LoopClass.SHOP_TO_SHOP, ab.loopClass)
        assertTrue(!ab.sameShop)
        val same = builder(model(listOf(sell("A", 1, 25.0), buy("A", 1, 26.0)), info(1, 38))).findings().single()
        assertTrue(same.sameShop)
    }

    @Test
    fun `no finding below the margin threshold`() {
        val m = model(listOf(sell("A", 1, 100.0), buy("B", 1, 101.0)), info(1, 100))
        assertTrue(builder(m).findings().isEmpty())
    }

    @Test
    fun `craft loop reports expected value at min level and no-fail separately`() {
        val m = model(
            listOf(sell("A", 1, 10.0), recipe("r:bar", listOf(1 to 1.0), listOf(2 to 1.0), evMin = 0.5, evMax = 1.0), buy("B", 2, 14.0)),
            info(1, 10, "ore"), info(2, 20, "bar"),
        )
        val f = builder(m).findings().single { it.node == item(2) }
        assertEquals(LoopClass.NPC_BUY_CRAFT_SELL, f.loopClass)
        assertEquals(10.0, f.acquireGp)          // no-fail: one ore per bar
        assertEquals(20.0, f.acquireGpEvMin)     // 50% burn: two ores per bar
        assertEquals(14.0, f.liquidateGp)
    }

    @Test
    fun `a guard hides the loop and it appears in prevented-by-guards`() {
        val m = model(listOf(sell("A", 1, 100.0), alch(1, 120.0, guard = "SpecialShopGuard")), info(1, 200))
        val b = builder(m)
        assertTrue(b.findings().isEmpty())
        val p = b.preventedByGuards().single()
        assertEquals(listOf("SpecialShopGuard"), p.guardedBy)
        assertEquals(20.0, p.unitProfit)
    }

    @Test
    fun `currency to item to gp uses the hard peg and is classified`() {
        val ticket = 4067
        val m = model(
            listOf(sell("Buy Tickets", ticket, 1000.0), sell("Armoury", 1, 1200.0, currency = item(ticket)), alch(1, 3_600_000.0)),
            info(ticket, 0, "boss_ticket"), info(1, 6_000_000, "justiciar"),
            hardPegs = mapOf(item(ticket) to 1000),
        )
        val f = builder(m, setOf(item(coins), item(ticket))).findings().single { it.node == item(1) }
        assertEquals(LoopClass.CURRENCY_TO_ITEM_TO_GP, f.loopClass)
        assertEquals(1_200_000.0, f.acquireGp)
        assertEquals(2_400_000.0, f.unitProfit)
    }

    @Test
    fun `item to currency to item is classified from a non-coin buyback`() {
        val ticket = 4067
        val m = model(
            listOf(sell("Buy Tickets", ticket, 1000.0), buy("Relics", 1, 2000.0, currency = item(ticket)), sell("Trading Post", 1, 1_000_000.0, soft = true)),
            info(ticket, 0, "boss_ticket"), info(1, 1_000_000, "relic"), hardPegs = mapOf(item(ticket) to 1000),
        )
        // ticket liquidation: the peg edge is what makes tickets worth coins on the way out.
        val peg = Edge("peg:sell:boss_ticket", EdgeKind.PEG, "--pegs", listOf(Stack(item(ticket), 1.0)), listOf(Stack(item(coins), 1000.0)), 1.0, soft = true)
        val m2 = EconModel(m.items, m.edges + peg, coins, m.hardPegs, mapOf(item(ticket) to 1000), emptyList(), emptyList())
        val fs = builder(m2, setOf(item(coins), item(ticket))).findings()
        // The relic (the item sold for tickets) represents the loop; the ticket is folded in.
        val f = fs.singleOrNull { it.node == item(1) } ?: error("no finding for the relic; findings=$fs")
        assertEquals(LoopClass.ITEM_TO_CURRENCY_TO_ITEM, f.loopClass)
        assertEquals(listOf("item.boss_ticket"), f.related)
        assertTrue(f.soft)
    }

    @Test
    fun `GE floor to shop and shop to GE floor mirror classes`() {
        val geBuy = Edge("ge:buy:1", EdgeKind.GE_BUY, "ge", listOf(Stack(item(coins), 100.0)), listOf(Stack(item(1), 1.0)), 0.05)
        val geSell = Edge("ge:sell:2", EdgeKind.GE_SELL, "ge", listOf(Stack(item(2), 1.0)), listOf(Stack(item(coins), 70.0)), 0.05)
        val m = model(listOf(geBuy, buy("B", 1, 120.0), sell("A", 2, 50.0), geSell), info(1, 100), info(2, 100))
        val fs = builder(m).findings().associateBy { it.node }
        assertEquals(LoopClass.GE_FLOOR_TO_SHOP, fs[item(1)]!!.loopClass)
        assertEquals(LoopClass.SHOP_TO_GE_FLOOR, fs[item(2)]!!.loopClass)
    }

    @Test
    fun `convert recipes classify as convert and multi-input others cost is subtracted`() {
        val m = model(
            listOf(sell("A", 1, 10.0), sell("A", 2, 20.0), recipe("r:z", listOf(1 to 1.0, 2 to 1.0), listOf(3 to 1.0), cat = RecipeCategory.CONVERT), buy("B", 3, 100.0)),
            info(1, 10, "x"), info(2, 20, "y"), info(3, 100, "z"),
        )
        val v = ArbitrageEngine(m, EngineConfig()).run()
        assertEquals(90.0, v.liqOf(item(2)))   // y is the dominant input: 100 - cost of x
        assertEquals(0.0, v.liqOf(item(1)))    // x is not: it does not inherit the edge's value
        val marginal = ArbitrageEngine(m, EngineConfig(dominantOnly = false)).run()
        assertEquals(80.0, marginal.liqOf(item(1)))   // 100 - cost of y, when marginal values are wanted
        // y and z liquidate through the same sink (sell z), so they are ONE finding whose
        // representative is z (the item actually sold) with y folded in as related; x is the
        // cheaper, non-dominant input of the recipe and is not a finding at all.
        val fs = builder(m).findings()
        assertEquals(1, fs.size, "findings: $fs")
        assertEquals(item(3), fs.single().node)
        assertEquals(listOf("item.y"), fs.single().related)
        assertEquals(LoopClass.NPC_BUY_CONVERT_SELL, fs.single().loopClass)
    }

    @Test
    fun `a cheap secondary input of a profitable edge is not its own finding`() {
        // A 4 gp nature rune "liquidated" by alching a 3,200 gp platebody is the platebody's loop.
        val m = model(
            listOf(sell("A", 1, 3200.0), sell("A", 2, 4.0),
                Edge("alch:high:1", EdgeKind.ALCH_HIGH, "alch", listOf(Stack(item(1), 1.0), Stack(item(2), 1.0)), listOf(Stack(item(coins), 9984.0)), 5.0)),
            info(1, 16640, "platebody"), info(2, 4, "nature_rune"),
        )
        val fs = builder(m).findings()
        assertEquals(listOf(item(1)), fs.map { it.node })
    }

    @Test
    fun `pass-through inputs are dropped transitively`() {
        // essence -> nature rune -> (alch a platebody): the essence must not become a finding either.
        val m = model(
            listOf(sell("A", 1, 3200.0), sell("GE", 3, 4.0),
                recipe("r:rc", listOf(3 to 1.0), listOf(2 to 1.0)),
                Edge("alch:high:1", EdgeKind.ALCH_HIGH, "alch", listOf(Stack(item(1), 1.0), Stack(item(2), 1.0)), listOf(Stack(item(coins), 9984.0)), 5.0)),
            info(1, 16640, "platebody"), info(2, 4, "nature_rune"), info(3, 4, "essence"),
        )
        assertEquals(listOf(item(1)), builder(m).findings().map { it.node })
    }

    @Test
    fun `a positive cycle is reported UNBOUNDED not as a number`() {
        val m = model(
            listOf(sell("A", 1, 100.0), recipe("r:double", listOf(1 to 1.0), listOf(2 to 2.0)), recipe("r:back", listOf(2 to 1.0), listOf(1 to 1.0)), buy("B", 1, 90.0)),
            info(1, 100), info(2, 50),
        )
        val v = ArbitrageEngine(m, EngineConfig()).run()
        assertTrue(v.unbounded.isNotEmpty())
        val f = builder(m).findings().first()
        assertTrue(f.unbounded)
    }

    @Test
    fun `finite shop stock caps throughput`() {
        val t = ActionTimeModel.throughput(listOf(sell("A", 1, 10.0, stock = 100), alch(1, 20.0)))
        assertEquals(340.0, t.unitsFirstHour)
        assertEquals(240.0, t.unitsSustained)
        val u = ActionTimeModel.throughput(listOf(alch(1, 20.0)))
        assertEquals(1200.0, u.unitsSustained)
    }
}
