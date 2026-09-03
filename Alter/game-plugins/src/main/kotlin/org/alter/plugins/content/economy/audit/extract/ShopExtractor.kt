package org.alter.plugins.content.economy.audit.extract

import org.alter.game.model.World
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.Shop
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.WarEffortCurrency
import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.ShopSnapshot
import org.alter.plugins.content.economy.audit.model.Stack
import org.alter.plugins.content.economy.audit.model.WareSnapshot
import org.alter.plugins.content.economy.tradingpost.TradingPostCurrency
import org.alter.plugins.content.mechanics.shops.GeneralStoreCurrency
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * Turns every registered [Shop] into a snapshot + graph edges, mirroring the live engine rules in
 * `ItemCurrency` exactly:
 *  - sell price = `shopItem.sellPrice ?: currency.getSellPrice(world, shopItem.item)`;
 *  - buyback     = `shopItem.buyPrice ?: currency.getBuyPrice(world, unnotedId)`;
 *  - coins and Blood Money are never accepted;
 *  - BUY_STOCK accepts only stocked ids, and an infinite slot (Int.MAX_VALUE) refuses ("run out of space");
 *  - BUY_TRADEABLES accepts any tradeable item, subject to the currency's own deny rules
 *    (general store cost cap, Trading Post bonds + SpecialShopGuard);
 *  - a `PointsCurrency` shop never buys; a sell-only `WarEffortCurrency` shop never sells.
 */
object ShopExtractor {

    class Result(
        val shops: List<ShopSnapshot>,
        val edges: List<Edge>,
        /** Currency item -> coin price the NPC sells it at (a "buy tickets" tab). */
        val hardPegs: Map<NodeId, Int>,
        /** Every currency node some shop charges in (so pegs / classification know what a currency is). */
        val currencyNodes: Set<NodeId>,
    )

    fun extract(world: World, items: Map<Int, ItemInfo>): Result {
        val coins = getRSCM("item.coins_995")
        val bloodMoney = runCatching { getRSCM("item.blood_money") }.getOrNull()
        val shops = world.plugins.shops.values.sortedBy { it.name }
        val snapshots = ArrayList<ShopSnapshot>()
        val edges = ArrayList<Edge>()
        val currencyNodes = HashSet<NodeId>()

        // First pass: which items are currencies (any ItemCurrency shop's payment item).
        shops.forEach { shop ->
            currencyNode(shop)?.let { currencyNodes += it }
        }

        for (shop in shops) {
            val currency = shop.currency
            val node = currencyNode(shop)
            val sellOnly = currency.sellOnly()
            val wares = ArrayList<WareSnapshot>()
            val stockedIds = HashSet<Int>()
            shop.items.filterNotNull().forEach { stockedIds += it.item }

            for (si in shop.items) {
                si ?: continue
                val info = items[si.item]
                val unnoted = info?.unnotedId ?: si.item
                val unlimited = si.amount == Int.MAX_VALUE
                // ---- sell to player ----
                var sellPrice: Int? = null
                var sellSource = "n/a"
                if (si.amount > 0 && !sellOnly && node != null) {
                    sellPrice = si.sellPrice ?: currency.getSellPrice(world, si.item)
                    sellSource = if (si.sellPrice != null) "explicit" else "cache"
                    val stackable = info?.stackable ?: false
                    edges += Edge(
                        id = "shop:${shop.name}:sell:${items[unnoted]?.key ?: unnoted}",
                        kind = EdgeKind.SHOP_SELL,
                        source = shop.name,
                        inputs = listOf(Stack(node, sellPrice.toDouble())),
                        outputs = listOf(Stack(NodeId.ItemNode(unnoted), 1.0)),
                        ticksPerUnit = ActionTimeModel.shopTicks(stackable),
                        stock = if (unlimited) null else si.amount,
                        shopName = shop.name,
                        unlimited = unlimited,
                    )
                }
                // ---- buy from player (stocked ids) ----
                var buyback: Int? = null
                var buySource = "n/a"
                var allowed = false
                var deny: String? = null
                when {
                    currency is WarEffortCurrency -> {
                        // Sell-only depot: item -> War Effort (a lifetime record, never spendable).
                        val we = currency.getBuyPrice(world, si.item)
                        buyback = we; buySource = "SupplyDepot.valueOf"; allowed = we > 0
                        if (allowed) {
                            edges += Edge(
                                id = "depot:${shop.name}:${items[unnoted]?.key ?: unnoted}",
                                kind = EdgeKind.SUPPLY_DEPOT,
                                source = shop.name,
                                inputs = listOf(Stack(NodeId.ItemNode(unnoted), 1.0)),
                                outputs = listOf(Stack(NodeId.PointsNode(PointKind.WAR_EFFORT.name), we.toDouble())),
                                ticksPerUnit = ActionTimeModel.shopTicks(info?.stackable ?: false),
                                shopName = shop.name,
                            )
                        }
                    }
                    currency is PointsCurrency -> { deny = "PointsCurrency.noBuyback" }
                    shop.purchasePolicy == PurchasePolicy.BUY_NONE -> { deny = "BUY_NONE" }
                    currency is ItemCurrency -> {
                        buyback = si.buyPrice ?: currency.getBuyPrice(world, unnoted)
                        buySource = if (si.buyPrice != null) "explicit" else "cache*0.7"
                        deny = buybackDeny(currency, shop, unnoted, coins, bloodMoney, unlimited, stocked = true, info)
                        allowed = deny == null
                        if (node != null && buyback > 0) {
                            edges += Edge(
                                id = "shop:${shop.name}:buy:${items[unnoted]?.key ?: unnoted}",
                                kind = EdgeKind.SHOP_BUYBACK,
                                source = shop.name,
                                inputs = listOf(Stack(NodeId.ItemNode(unnoted), 1.0)),
                                outputs = listOf(Stack(node, buyback.toDouble())),
                                ticksPerUnit = ActionTimeModel.shopTicks(info?.stackable ?: false),
                                stock = if (unlimited) null else si.amount,
                                guardedBy = deny,
                                shopName = shop.name,
                                unlimited = unlimited,
                            )
                        }
                    }
                }
                wares += WareSnapshot(
                    item = si.item, key = items[si.item]?.key, name = info?.name ?: "item#${si.item}",
                    cost = info?.cost ?: -1, amount = si.amount, unlimited = unlimited,
                    sellPrice = sellPrice, sellPriceSource = sellSource,
                    buyback = buyback, buybackSource = buySource, buybackAllowed = allowed, buybackDeny = deny,
                )
            }

            // ---- buy from player (anything tradeable) ----
            if ((shop.purchasePolicy == PurchasePolicy.BUY_TRADEABLES || shop.purchasePolicy == PurchasePolicy.BUY_ALL) &&
                node != null && currency is ItemCurrency
            ) {
                val hasFreeSlot = shop.items.any { it == null }
                for (info in items.values) {
                    if (info.noted) continue
                    if (info.id in stockedIds) continue // handled above
                    if (shop.purchasePolicy == PurchasePolicy.BUY_TRADEABLES && !info.tradeable) continue
                    if (info.cost <= 0 && info.highAlchOverride == null) {
                        // buyback = max(1, 0) = 1 gp: technically an edge, but a 1 gp faucet for a
                        // cost-0 item is noise; keep it out of the graph.
                        continue
                    }
                    val price = currency.getBuyPrice(world, info.id)
                    val deny = buybackDeny(currency, shop, info.id, coins, bloodMoney, unlimited = false, stocked = false, info)
                        ?: if (!hasFreeSlot) "shop.noFreeSlot" else null
                    edges += Edge(
                        id = "shop:${shop.name}:buy:${info.key ?: info.id}",
                        kind = EdgeKind.SHOP_BUYBACK,
                        source = shop.name,
                        inputs = listOf(Stack(NodeId.ItemNode(info.id), 1.0)),
                        outputs = listOf(Stack(node, price.toDouble())),
                        ticksPerUnit = ActionTimeModel.shopTicks(info.stackable),
                        guardedBy = deny,
                        shopName = shop.name,
                    )
                }
            }

            snapshots += ShopSnapshot(
                name = shop.name,
                currencyClass = currency.javaClass.simpleName,
                currencyLabel = currency.label(),
                currencyNode = node,
                policy = shop.purchasePolicy.name,
                sellOnly = sellOnly,
                wares = wares,
            )
        }

        // Hard pegs: a coin shop that sells a currency item (e.g. "Buy Boss Tickets" @ 1,000 gp).
        val coinsNode = NodeId.ItemNode(coins)
        val hardPegs = HashMap<NodeId, Int>()
        edges.filter { it.kind == EdgeKind.SHOP_SELL && it.inputs.single().node == coinsNode }
            .forEach { e ->
                val out = e.outputs.single().node
                if (out in currencyNodes && out != coinsNode) {
                    val price = e.inputs.single().qty.toInt()
                    hardPegs[out] = minOf(hardPegs[out] ?: Int.MAX_VALUE, price)
                }
            }

        return Result(snapshots, edges, hardPegs, currencyNodes)
    }

    /** The node a shop charges in, or null when it cannot be modelled (unknown currency class). */
    fun currencyNode(shop: Shop): NodeId? = when (val c = shop.currency) {
        is ItemCurrency -> NodeId.ItemNode(c.currencyItem)
        is PointsCurrency -> NodeId.PointsNode((Reflect.field(c, "kind") as PointKind).name)
        is WarEffortCurrency -> NodeId.PointsNode(PointKind.WAR_EFFORT.name)
        else -> null
    }

    /** Mirror of `ItemCurrency.canAcceptItem` + the subclass deny rules, as a reason string or null. */
    private fun buybackDeny(
        currency: ItemCurrency,
        shop: Shop,
        unnoted: Int,
        coins: Int,
        bloodMoney: Int?,
        unlimited: Boolean,
        stocked: Boolean,
        info: ItemInfo?,
    ): String? {
        if (unnoted == coins || unnoted == bloodMoney) return "ItemCurrency.currencyItem"
        when (shop.purchasePolicy) {
            PurchasePolicy.BUY_NONE -> return "BUY_NONE"
            PurchasePolicy.BUY_STOCK -> if (!stocked) return "BUY_STOCK.notStocked"
            PurchasePolicy.BUY_TRADEABLES -> if (info?.tradeable == false) return "untradeable"
            PurchasePolicy.BUY_ALL -> {}
        }
        if (unlimited) return "shop.infiniteSlot" // amount = min(.., Int.MAX_VALUE - count) == 0
        return when (currency) {
            is GeneralStoreCurrency -> if (!GeneralStoreCurrency.accepts(unnoted)) "GeneralStore.cost>${GeneralStoreCurrency.MAX_BUYBACK_COST}" else null
            is TradingPostCurrency -> TradingPostCurrency.refusalReason(unnoted)
            else -> null
        }
    }
}
