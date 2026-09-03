package org.alter.plugins.content.economy.audit.extract

import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.Stack
import org.alter.plugins.content.economy.grandexchange.GrandExchangePricing
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * The two NPC gp sinks that are not shops: **alchemy** (`AlchemyPlugin`, guard order mirrored
 * exactly) and the **GE commodity backstop** (`GrandExchange.backstopSweep`: unlimited quantity at
 * 100% / 70% of value for the commodity allowlist). The backstop is only enabled by
 * `GrandExchangeEnginePlugin` at world init, so offline we assume it is on and say so.
 */
object SinkExtractor {

    const val HIGH_RATE = 0.6
    const val LOW_RATE = 0.4

    /** Rune cost of one high alch cast, as the spell metadata declares it (nature + fire runes). */
    fun alchRunes(high: Boolean): List<Stack> {
        val wanted = if (high) "high level alchemy" else "low level alchemy"
        val spell = MagicSpells.getMiscSpells().values.firstOrNull { it.name.lowercase().contains(wanted) }
            ?: return emptyList()
        // A fire staff supplies the fire runes for free in practice; only count the non-elemental
        // runes so the model does not overstate the cast cost. (Elemental staves are cheap, permanent.)
        val elemental = setOf("item.air_rune", "item.water_rune", "item.earth_rune", "item.fire_rune")
            .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
        return spell.items.filter { it.id !in elemental }.map { Stack(NodeId.ItemNode(it.id), it.amount.toDouble()) }
    }

    fun alchEdges(items: Map<Int, ItemInfo>, coins: Int): List<Edge> {
        val denylist = listOf("item.bond", "item.bond_untradeable", "item.boss_ticket", "item.vote_ticket")
            .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
        val highRunes = alchRunes(high = true)
        val lowRunes = alchRunes(high = false)
        val out = ArrayList<Edge>()
        for (info in items.values) {
            if (info.noted) continue
            if (info.id == coins) continue
            for (high in listOf(true, false)) {
                val explicit = if (high) info.highAlchOverride else info.lowAlchOverride
                val rate = if (high) HIGH_RATE else LOW_RATE
                val guard = when {
                    info.id in denylist -> "Alchemy.denylist"
                    !info.tradeable -> "Alchemy.untradeable"
                    info.guarded -> "SpecialShopGuard"
                    explicit != null && explicit <= 0 -> "Alchemy.override0"
                    else -> null
                }
                val value = explicit ?: (info.cost * rate).toInt().coerceAtLeast(1)
                if (value <= 1 && guard == null && info.cost <= 0) continue // 1 gp for a worthless item: noise
                out += Edge(
                    id = "alch:${if (high) "high" else "low"}:${info.key ?: info.id}",
                    kind = if (high) EdgeKind.ALCH_HIGH else EdgeKind.ALCH_LOW,
                    source = "AlchemyPlugin",
                    inputs = listOf(Stack(NodeId.ItemNode(info.id), 1.0)) + (if (high) highRunes else lowRunes),
                    outputs = listOf(Stack(NodeId.ItemNode(coins), value.toDouble())),
                    ticksPerUnit = if (high) ActionTimeModel.HIGH_ALCH_TICKS else ActionTimeModel.LOW_ALCH_TICKS,
                    guardedBy = guard,
                    levelNote = if (explicit != null) "explicit YAML alch override" else "",
                )
            }
        }
        return out
    }

    fun geEdges(items: Map<Int, ItemInfo>, coins: Int): List<Edge> {
        val out = ArrayList<Edge>()
        for (info in items.values) {
            if (info.noted || !info.isCommodity || info.cost <= 0) continue
            val ceiling = info.cost
            val floor = (ceiling * ItemCurrency.BUY_RATE).toInt().coerceAtLeast(1)
            val band = GrandExchangePricing.bounds(ceiling)
            out += Edge(
                id = "ge:buy:${info.key ?: info.id}",
                kind = EdgeKind.GE_BUY,
                source = "GrandExchange.backstop",
                inputs = listOf(Stack(NodeId.ItemNode(coins), ceiling.toDouble())),
                outputs = listOf(Stack(NodeId.ItemNode(info.id), 1.0)),
                ticksPerUnit = ActionTimeModel.GE_OFFER_TICKS,
                levelNote = "band ${band.first}..${band.second}",
            )
            out += Edge(
                id = "ge:sell:${info.key ?: info.id}",
                kind = EdgeKind.GE_SELL,
                source = "GrandExchange.backstop",
                inputs = listOf(Stack(NodeId.ItemNode(info.id), 1.0)),
                outputs = listOf(Stack(NodeId.ItemNode(coins), floor.toDouble())),
                ticksPerUnit = ActionTimeModel.GE_OFFER_TICKS,
            )
        }
        return out
    }
}
