package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The **Grand Exchange engine** — the world-level order book plus the escrow, matching, collect and
 * cancel logic. UI-free by design: [GrandExchangeInterface] reads [slotsOf] to paint the board and
 * calls [createBuy]/[createSell]/[collect]/[cancel]; a world service ticks [matchTick].
 *
 * **Slots.** Each player owns 8 boxes (0..7). A `(owner, box)` pair is one [GeOffer]. Offers persist
 * independently of the session (JSON world save, [WarMemory] pattern) so a match can complete while
 * the maker is offline and the proceeds wait in that offer's collection fields until collected.
 *
 * **Money conservation.** Escrow leaves the player on create; every fill only *moves* value between
 * an offer's escrow and its collectable proceeds (see [GeOffer]); collect/cancel return whatever is
 * left. Nothing is minted or destroyed by a player↔player match. The NPC backstop ([backstopFill])
 * is the *one* deliberate faucet/sink and is gated to commodity items only.
 */
object GrandExchange {
    private val logger = KotlinLogging.logger {}
    private const val SCHEMA_VERSION = 1
    const val SLOTS = 8

    private val saveFile = Paths.get("../data/saves/world/grand_exchange.json")
    private val pretty: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    /** Every live offer (active, or drained-but-still-holding-collectables). Small N; a flat list is fine. */
    private val offers = ArrayList<GeOffer>()
    private var seqCounter = 0L
    @Volatile private var dirty = false

    private val coinsId: Int by lazy { getRSCM("item.coins_995") }

    // ---- queries -------------------------------------------------------------------------------

    /** The player's 8 boxes in order, null where empty. */
    fun slotsOf(owner: String): Array<GeOffer?> {
        val arr = arrayOfNulls<GeOffer>(SLOTS)
        offers.filter { it.owner == owner }.forEach { if (it.box in 0 until SLOTS) arr[it.box] = it }
        return arr
    }

    fun slot(owner: String, box: Int): GeOffer? =
        offers.firstOrNull { it.owner == owner && it.box == box }

    private fun key(p: Player) = p.username.lowercase()

    // ---- offer creation (escrow out) -----------------------------------------------------------

    /** Create a BUY offer, escrowing `price × qty` coins from inventory (then bank). */
    fun createBuy(p: Player, box: Int, itemId: Int, price: Int, qty: Int): Boolean {
        if (!validNew(p, box, itemId, price, qty)) return false
        val total = price.toLong() * qty.toLong()
        if (total > Int.MAX_VALUE) { p.message("That offer is too large."); return false }
        if (!takeCoins(p, total.toInt())) { p.message("You don't have enough coins for that offer."); return false }
        put(GeOffer(box = box, buy = true, itemId = itemId, price = price, qty = qty,
            escrowCoins = total.toInt(), state = GeState.BUYING, owner = key(p), seq = seqCounter++))
        p.message("<col=801700>Grand Exchange:</col> buying ${qty} x ${nameOf(itemId)} at ${price} gp each.")
        return true
    }

    /** Create a SELL offer, escrowing `qty` of [itemId] from the player's inventory. */
    fun createSell(p: Player, box: Int, itemId: Int, price: Int, qty: Int): Boolean {
        if (!validNew(p, box, itemId, price, qty)) return false
        val have = p.inventory.getItemCount(itemId)
        if (have < qty) { p.message("You don't have ${qty} of that to sell."); return false }
        val removed = p.inventory.remove(item = itemId, amount = qty, assureFullRemoval = true)
        if (removed.hasFailed()) { p.message("You don't have ${qty} of that to sell."); return false }
        put(GeOffer(box = box, buy = false, itemId = itemId, price = price, qty = qty,
            escrowItems = qty, state = GeState.SELLING, owner = key(p), seq = seqCounter++))
        p.message("<col=801700>Grand Exchange:</col> selling ${qty} x ${nameOf(itemId)} at ${price} gp each.")
        return true
    }

    private fun validNew(p: Player, box: Int, itemId: Int, price: Int, qty: Int): Boolean {
        if (box !in 0 until SLOTS) return false
        if (slot(key(p), box) != null) { p.message("That Grand Exchange slot is already in use."); return false }
        if (price <= 0 || qty <= 0) { p.message("Set a price and quantity first."); return false }
        if (itemId == coinsId) { p.message("You can't trade coins on the Grand Exchange."); return false }
        if (itemId in EXCLUDED) { p.message("That item can't be traded on the Grand Exchange."); return false }
        if (!runCatching { getItem(itemId).isTradeable }.getOrDefault(false)) {
            p.message("That item can't be traded on the Grand Exchange."); return false
        }
        return true
    }

    // ---- cancel / collect ----------------------------------------------------------------------

    /** Abort an active offer: unspent escrow becomes collectable, slot marked cancelled. */
    fun cancel(p: Player, box: Int) {
        val o = slot(key(p), box) ?: return
        if (!o.isActive) return
        o.collectCoins += o.escrowCoins; o.escrowCoins = 0
        o.collectItems += o.escrowItems; o.escrowItems = 0
        o.state = if (o.buy) GeState.CANCELLED_BUY else GeState.CANCELLED_SELL
        markDirty()
    }

    /** Move an offer's collectable proceeds into the player (inventory or bank); free the slot when drained. */
    fun collect(p: Player, box: Int, toBank: Boolean) {
        val o = slot(key(p), box) ?: return
        if (o.collectCoins > 0) {
            val added = give(p, coinsId, o.collectCoins, toBank)
            o.collectCoins -= added
        }
        if (o.collectItems > 0) {
            val added = give(p, o.itemId, o.collectItems, toBank)
            o.collectItems -= added
        }
        // Free the slot once the offer is finished (or cancelled) and nothing is left to hand back.
        if (o.fullyDrained && (o.filled >= o.qty || o.state == GeState.CANCELLED_BUY || o.state == GeState.CANCELLED_SELL)) {
            offers.remove(o)
        }
        markDirty()
    }

    // ---- matching ------------------------------------------------------------------------------

    /** One matching pass: cross players first (price-time priority), then the NPC commodity backstop. */
    fun matchTick() {
        var changed = false
        val active = offers.filter { it.isActive }
        for (item in active.map { it.itemId }.toSet()) {
            val buys = active.filter { it.buy && it.itemId == item && it.remaining > 0 }
                .sortedWith(compareByDescending<GeOffer> { it.price }.thenBy { it.seq }).toMutableList()
            val sells = active.filter { !it.buy && it.itemId == item && it.remaining > 0 }
                .sortedWith(compareBy<GeOffer> { it.price }.thenBy { it.seq }).toMutableList()
            var bi = 0; var si = 0
            while (bi < buys.size && si < sells.size) {
                val b = buys[bi]; val s = sells[si]
                val fill = GrandExchangeMatching.cross(b, s)
                if (fill == null) break // best buy can't reach best sell → nothing else can either
                applyFill(b, s, fill); changed = true
                if (b.remaining == 0) bi++
                if (s.remaining == 0) si++
            }
        }
        if (backstopEnabled) changed = backstopSweep() || changed
        if (changed) markDirty()
    }

    private fun applyFill(buy: GeOffer, sell: GeOffer, fill: GeFill) {
        val (q, p) = fill
        // buyer: spend p per item, receive items, refund the (buy.price - p) overpay
        buy.filled += q
        buy.escrowCoins -= p * q
        buy.collectItems += q
        buy.collectCoins += (buy.price - p) * q
        buy.refreshState()
        // seller: hand over items, receive p per item
        sell.filled += q
        sell.escrowItems -= q
        sell.collectCoins += p * q
        sell.refreshState()
    }

    // ---- NPC commodity backstop (the one intentional faucet/sink; gated to commodities) ---------

    /** Wired by the plugin once the commodity allowlist + prices are in; false keeps it player-only. */
    @Volatile var backstopEnabled = false

    /** Buy from / sell to the NPC any active offer that crosses the commodity price band. */
    private fun backstopSweep(): Boolean {
        var changed = false
        for (o in offers.filter { it.isActive && it.remaining > 0 }) {
            val ceiling = commodityCeiling(o.itemId) ?: continue // null = not backstopped
            val floor = commodityFloor(o.itemId) ?: continue
            if (o.buy && o.price >= ceiling) {
                val q = o.remaining
                o.filled += q; o.escrowCoins -= ceiling * q
                o.collectItems += q; o.collectCoins += (o.price - ceiling) * q
                o.refreshState(); changed = true
            } else if (!o.buy && o.price <= floor) {
                val q = o.remaining
                o.filled += q; o.escrowItems -= q
                o.collectCoins += floor * q
                o.refreshState(); changed = true
            }
        }
        return changed
    }

    /** Ceiling = full item value; null when the item is not a store-backstopped commodity. */
    private fun commodityCeiling(itemId: Int): Int? =
        if (isBackstopped(itemId)) runCatching { maxOf(1, getItem(itemId).cost) }.getOrNull() else null

    /** Floor = 85% of value (matches the Trading Post buy rate / gp sink). */
    private fun commodityFloor(itemId: Int): Int? =
        commodityCeiling(itemId)?.let { (it * 0.85).toInt().coerceAtLeast(1) }

    // ---- display helpers for the offer window (UI reads these) ---------------------------------

    /** Guide price (cache value) shown in the offer-setup box. */
    fun guidePrice(itemId: Int): Int = runCatching { maxOf(1, getItem(itemId).cost) }.getOrDefault(1)

    /** The store band `(floor, ceiling)` if [itemId] is a backstopped commodity, else null (floats). */
    fun band(itemId: Int): Pair<Int, Int>? {
        val ceil = commodityCeiling(itemId) ?: return null
        val floor = commodityFloor(itemId) ?: return null
        return floor to ceil
    }

    /**
     * Backstop only the coin-store commodities ([GrandExchangeCommodities]); special-currency gear
     * and everything else stays player-listed with no NPC floor.
     */
    private fun isBackstopped(itemId: Int): Boolean = GrandExchangeCommodities.isCommodity(itemId)

    /** Items never allowed on the GE (bonds; extend with `tradeable_on_ge=false` later). */
    private val EXCLUDED: Set<Int> = listOf("item.bond", "item.bond_untradeable")
        .mapNotNull { k -> runCatching { getRSCM(k) }.getOrNull() }.toSet()

    // ---- player IO helpers ---------------------------------------------------------------------

    /** Total coins the player can spend (inventory first, then bank). */
    private fun spendableCoins(p: Player): Long =
        p.inventory.getItemCount(coinsId).toLong() + p.bank.getItemCount(coinsId).toLong()

    /** Remove [total] coins, inventory first then bank; false (and untouched) if unaffordable. */
    private fun takeCoins(p: Player, total: Int): Boolean {
        if (spendableCoins(p) < total) return false
        val fromInv = minOf(p.inventory.getItemCount(coinsId), total)
        if (fromInv > 0 && p.inventory.remove(item = coinsId, amount = fromInv, assureFullRemoval = true).hasFailed()) return false
        val fromBank = total - fromInv
        if (fromBank > 0 && p.bank.remove(item = coinsId, amount = fromBank, assureFullRemoval = true).hasFailed()) {
            if (fromInv > 0) p.inventory.add(item = coinsId, amount = fromInv) // refund the inv portion
            return false
        }
        return true
    }

    /** Add up to [amount] of [itemId] to inventory or bank; returns how many actually fit. */
    private fun give(p: Player, itemId: Int, amount: Int, toBank: Boolean): Int {
        val target = if (toBank) p.bank else p.inventory
        return target.add(item = itemId, amount = amount, assureFullInsertion = false).completed
    }

    private fun nameOf(itemId: Int) = runCatching { getItem(itemId).name }.getOrDefault("item $itemId")

    private fun put(o: GeOffer) { offers.add(o); markDirty() }
    private fun markDirty() { dirty = true }

    // ---- persistence (WarMemory pattern) -------------------------------------------------------

    fun load() {
        try {
            if (!Files.exists(saveFile)) return
            val doc = Document.parse(saveFile.readText().trimStart('﻿'))
            seqCounter = (doc.get("seq") as? Number)?.toLong() ?: 0L
            offers.clear()
            doc.getList("offers", Document::class.java)?.forEach { offers.add(GeOffer.fromDocument(it)) }
            dirty = false
            logger.info { "Loaded Grand Exchange: ${offers.size} offer(s)." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load Grand Exchange; starting empty." }
        }
    }

    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        try {
            Files.createDirectories(saveFile.parent)
            val doc = Document()
                .append("version", SCHEMA_VERSION)
                .append("seq", seqCounter)
                .append("offers", offers.map { it.toDocument() })
            saveFile.writeText(doc.toJson(pretty))
            dirty = false
        } catch (e: Exception) {
            logger.error(e) { "Failed to save Grand Exchange; will retry." }
        }
    }
}
