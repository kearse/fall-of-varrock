package org.alter.plugins.content.economy.audit.model

/**
 * The economy value graph the arbitrage auditor reasons over.
 *
 * Every node is either an ITEM (canonical unnoted cache id — coins, tickets and Blood Money are
 * items too) or a POINTS counter (Donor, Prestige, War Effort). Every edge is a *conversion* the
 * game lets a player perform against an NPC system: buy from a shop, sell to a shop, alch, the
 * GE commodity backstop, a skill recipe, a potion dose, a set box, the Supply Depot hand-in.
 * Player-to-player trade is deliberately NOT an edge — the audit is about value the NPCs mint.
 */
sealed class NodeId : Comparable<NodeId> {
    data class ItemNode(val id: Int) : NodeId() {
        override fun toString() = "item:$id"
    }

    data class PointsNode(val kind: String) : NodeId() {
        override fun toString() = "points:$kind"
    }

    override fun compareTo(other: NodeId): Int = toString().compareTo(other.toString())

    companion object {
        /** Parse the `toString` form back (`item:995`, `points:DONOR`). */
        fun parse(s: String): NodeId? {
            val i = s.indexOf(':')
            if (i <= 0) return null
            val kind = s.substring(0, i)
            val rest = s.substring(i + 1)
            return when (kind) {
                "item" -> rest.toIntOrNull()?.let { ItemNode(it) }
                "points" -> PointsNode(rest)
                else -> null
            }
        }
    }
}

/** A quantity of a node consumed or produced by an edge. Fractional = expected value. */
data class Stack(val node: NodeId, val qty: Double)

enum class EdgeKind {
    SHOP_SELL,      // NPC sells to the player: currency -> item
    SHOP_BUYBACK,   // NPC buys from the player: item -> currency
    ALCH_HIGH,
    ALCH_LOW,
    GE_BUY,         // GE backstop sells to the player at the ceiling (100% of value)
    GE_SELL,        // GE backstop buys from the player at the floor (70% of value)
    RECIPE,         // a skill / spell / forge conversion
    DOSE,           // potion dose ladder (4 -> 3 -> 2 -> 1 -> vial)
    SET_PACK,       // pieces -> set box
    SET_UNPACK,     // set box -> pieces
    SUPPLY_DEPOT,   // item -> War Effort (a points record, never spendable)
    PEG,            // an ASSUMED player-market price for a currency (soft, from --pegs)
}

/** Recipe flavour used for loop classification: a skill "craft" vs a mechanical "convert". */
enum class RecipeCategory { CRAFT, CONVERT, NONE }

data class Edge(
    /** Stable, human-readable id: `shop:<name>:sell:<key>`, `recipe:smithing.smelt.iron_bar`, ... */
    val id: String,
    val kind: EdgeKind,
    /** Which plugin / system defines it (for the report). */
    val source: String,
    val inputs: List<Stack>,
    val outputs: List<Stack>,
    /** Game ticks one unit of this edge costs the player (see ActionTimeModel). */
    val ticksPerUnit: Double,
    /** Initial shop stock, or null when unlimited / not stock-bound. Finite stock restocks 1 per 25 ticks. */
    val stock: Int? = null,
    /** Output multiplier at the minimum level the edge is usable at (e.g. 0.5 for iron smelting). */
    val evAtMinLevel: Double = 1.0,
    /** Output multiplier at max level / no-fail level. */
    val evAtMaxLevel: Double = 1.0,
    val levelNote: String = "",
    /** Non-null = the live game BLOCKS this edge for that reason (the audit still models it, flagged). */
    val guardedBy: String? = null,
    /** True when the edge rests on a non-NPC assumption (a --pegs value, assumed Trading Post stock). */
    val soft: Boolean = false,
    /** Shop name for shop edges (same-shop loop detection). */
    val shopName: String? = null,
    val category: RecipeCategory = RecipeCategory.NONE,
    /** Whether the shop slot is infinite (never decrements) — affects buyback acceptance. */
    val unlimited: Boolean = false,
)

data class ItemInfo(
    val id: Int,
    val key: String?,
    val name: String,
    val cost: Int,
    val tradeable: Boolean,
    val stackable: Boolean,
    /** The canonical unnoted id (== id unless this is a note). */
    val unnotedId: Int,
    val noted: Boolean,
    val highAlchOverride: Int?,
    val lowAlchOverride: Int?,
    val geExcluded: Boolean,
    val isCommodity: Boolean,
    val guarded: Boolean,
)

/** One shelf line, exactly as the live shop engine would price it. */
data class WareSnapshot(
    val item: Int,
    val key: String?,
    val name: String,
    val cost: Int,
    val amount: Int,
    val unlimited: Boolean,
    val sellPrice: Int?,
    val sellPriceSource: String,
    val buyback: Int?,
    val buybackSource: String,
    val buybackAllowed: Boolean,
    val buybackDeny: String?,
)

data class ShopSnapshot(
    val name: String,
    val currencyClass: String,
    val currencyLabel: String,
    val currencyNode: NodeId?,
    val policy: String,
    val sellOnly: Boolean,
    val wares: List<WareSnapshot>,
)

class EconModel(
    val items: Map<Int, ItemInfo>,
    val edges: List<Edge>,
    val coinsId: Int,
    /** Currency node -> gp price at which an NPC SELLS that currency (a "buy tickets" tab). */
    val hardPegs: Map<NodeId, Int>,
    /** Currency node -> assumed player-market gp price (only used where no hard peg exists). */
    val softPegs: Map<NodeId, Int>,
    val shops: List<ShopSnapshot>,
    val notes: List<String>,
) {
    val coins: NodeId get() = NodeId.ItemNode(coinsId)

    fun name(node: NodeId): String = when (node) {
        is NodeId.ItemNode -> items[node.id]?.let { it.key?.removePrefix("item.") ?: it.name } ?: "item#${node.id}"
        is NodeId.PointsNode -> node.kind.lowercase().replace('_', ' ') + " points"
    }

    fun key(node: NodeId): String = when (node) {
        is NodeId.ItemNode -> items[node.id]?.key ?: "item#${node.id}"
        is NodeId.PointsNode -> "points:${node.kind}"
    }
}
