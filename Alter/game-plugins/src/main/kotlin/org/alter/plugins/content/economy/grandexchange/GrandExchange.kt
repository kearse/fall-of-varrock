package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.service.game.ItemMetadataService
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The **Grand Exchange engine** — the world-level order book plus the escrow, matching, collect and
 * cancel logic. UI-free by design: [GrandExchangeWindow] reads [slotsOf] to paint the board and
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

    /** A just-completed offer, queued for the plugin to notify the (possibly offline) owner. */
    data class GeNotice(val owner: String, val buy: Boolean, val itemId: Int, val qty: Int)
    private val notices = ArrayList<GeNotice>()

    /** A completed trade recorded for the player's History tab. `coins` = price × qty (the offer price);
     *  `time` = epoch millis at completion. Newest entries live at the end of [history]. */
    data class GeHistoryEntry(
        val owner: String, val buy: Boolean, val itemId: Int, val qty: Int, val price: Int, val time: Long,
    ) {
        fun toDocument(): Document = Document()
            .append("owner", owner).append("buy", buy).append("item", itemId)
            .append("qty", qty).append("price", price).append("time", time)

        companion object {
            fun fromDocument(d: Document) = GeHistoryEntry(
                owner = d.getString("owner") ?: "",
                buy = d.getBoolean("buy", true),
                itemId = d.getInteger("item", -1),
                qty = d.getInteger("qty", 0),
                price = d.getInteger("price", 0),
                time = (d.get("time") as? Number)?.toLong() ?: 0L,
            )
        }
    }

    private val history = ArrayList<GeHistoryEntry>()
    private const val HISTORY_PER_OWNER = 20

    /** A player's completed trades, newest first (for the History tab). */
    fun historyOf(owner: String): List<GeHistoryEntry> =
        history.filter { it.owner == owner }.asReversed()

    /** Append a completed trade, trimming the owner's history to the most recent [HISTORY_PER_OWNER]. */
    private fun recordHistory(o: GeOffer) {
        history.add(GeHistoryEntry(o.owner, o.buy, o.itemId, o.qty, o.price, System.currentTimeMillis()))
        val mine = history.filter { it.owner == o.owner }
        if (mine.size > HISTORY_PER_OWNER) {
            history.removeAll(mine.take(mine.size - HISTORY_PER_OWNER).toSet())
        }
        markDirty()
    }

    /** Take + clear the pending completion notices (delivered to online owners by the plugin). */
    fun drainNotices(): List<GeNotice> {
        if (notices.isEmpty()) return emptyList()
        val out = ArrayList(notices)
        notices.clear()
        return out
    }

    /** True if [owner] has any collectable proceeds waiting (for the login prompt). */
    fun hasCollectables(owner: String): Boolean =
        offers.any { it.owner == owner && (it.collectCoins > 0 || it.collectItems > 0) }

    /** True if [owner] has any offer on the book (used to push live board refreshes to them). */
    fun ownsOffer(owner: String): Boolean = offers.any { it.owner == owner }

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
        save() // persist immediately: coins already left the player, the offer must not be lost on a crash
        p.message("<col=801700>Grand Exchange:</col> buying ${qty} x ${nameOf(itemId)} at ${price} gp each.")
        return true
    }

    /** Create a SELL offer, escrowing `qty` of [itemId] from the player's inventory. */
    fun createSell(p: Player, box: Int, itemId: Int, price: Int, qty: Int): Boolean {
        // Quest-locked items can't be listed — escrow would hide them from the quest's
        // "does the player still have them?" checks. See [WarPrepChain.bonesLocked].
        if (WarPrepChain.bonesLocked(p, itemId)) {
            WarPrepChain.warnBonesLocked(p)
            return false
        }
        if (!validNew(p, box, itemId, price, qty)) return false
        val have = p.inventory.getItemCount(itemId)
        if (have < qty) { p.message("You don't have ${qty} of that to sell."); return false }
        val removed = p.inventory.remove(item = itemId, amount = qty, assureFullRemoval = true)
        if (removed.hasFailed()) { p.message("You don't have ${qty} of that to sell."); return false }
        put(GeOffer(box = box, buy = false, itemId = itemId, price = price, qty = qty,
            escrowItems = qty, state = GeState.SELLING, owner = key(p), seq = seqCounter++))
        save() // persist immediately: items already left the player, the offer must not be lost on a crash
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
        // Price sanity rail (GrandExchangePricing). Deliberately wide — it exists to refuse a price with
        // no relationship to the item (the 1 gp buy that the NPC backstop then filled), not to peg the
        // market. Enforced HERE, server-side, because the price arrives from the client.
        val value = economyValue(itemId)
        if (value != null && !GrandExchangePricing.permits(value, price)) {
            val (min, max) = GrandExchangePricing.bounds(value)
            if (price < min) {
                p.message("<col=801700>Grand Exchange:</col> the clerk won't take less than ${min} gp each for that.")
            } else {
                p.message("<col=801700>Grand Exchange:</col> the clerk won't take more than ${max} gp each for that.")
            }
            return false
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
        save() // proceeds are now collectable — persist so a crash can't double-hand-out on reload
    }

    /** Move one slot's collectable proceeds into the player; free it when drained (no save — see callers). */
    private fun collectBox(p: Player, box: Int, toBank: Boolean) {
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

    /** Collect one slot's proceeds (persisted immediately — items left the book, must not reappear). */
    fun collect(p: Player, box: Int, toBank: Boolean) {
        collectBox(p, box, toBank)
        save()
    }

    /** Collect every slot's proceeds, persisting once at the end. */
    fun collectAll(p: Player, toBank: Boolean) {
        for (box in 0 until SLOTS) collectBox(p, box, toBank)
        save()
    }

    // ---- matching ------------------------------------------------------------------------------

    /** One matching pass: cross players first (price-time priority), then the NPC commodity backstop.
     *  Returns true if any offer changed this pass (so the caller can push a live board refresh). */
    fun matchTick(): Boolean {
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
        return changed
    }

    private fun applyFill(buy: GeOffer, sell: GeOffer, fill: GeFill) {
        val (q, p) = fill
        // buyer: the full reservation for these q units (buy.price each) leaves escrow — p per item is
        // paid to the seller, the (buy.price - p) overpay is refunded into collectCoins. Draining only
        // p*q here would strand the overpay in escrow forever, so the slot never becomes fullyDrained.
        buy.filled += q
        buy.escrowCoins -= buy.price * q
        buy.collectItems += q
        buy.collectCoins += (buy.price - p) * q
        buy.refreshState()
        // seller: hand over items, receive p per item
        sell.filled += q
        sell.escrowItems -= q
        sell.collectCoins += p * q
        sell.refreshState()
        // Feed the clerk's ledger — real price discovery, player↔player only (backstop is deliberately
        // excluded so the trend never pegs to the fixed store band; see MarketMemory).
        MarketMemory.record(buy.itemId, q, p, System.currentTimeMillis())
        noticeIfComplete(buy)
        noticeIfComplete(sell)
    }

    /** Queue an owner notification + a History entry when an offer has just fully filled. */
    private fun noticeIfComplete(o: GeOffer) {
        if (o.filled >= o.qty && (o.state == GeState.BOUGHT || o.state == GeState.SOLD)) {
            notices.add(GeNotice(o.owner, o.buy, o.itemId, o.qty))
            recordHistory(o)
        }
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
                // Full reservation (o.price each) leaves escrow: ceiling*q is the NPC sink, the
                // (o.price - ceiling) overpay per item is refunded into collectCoins.
                o.filled += q; o.escrowCoins -= o.price * q
                o.collectItems += q; o.collectCoins += (o.price - ceiling) * q
                o.refreshState(); changed = true; noticeIfComplete(o)
            } else if (!o.buy && o.price <= floor) {
                val q = o.remaining
                o.filled += q; o.escrowItems -= q
                o.collectCoins += floor * q
                o.refreshState(); changed = true; noticeIfComplete(o)
            }
        }
        return changed
    }

    /**
     * The item's **economy value** — the single price source the whole exchange reads, and the same one
     * the coin shops use (`ItemCurrency.getSellPrice` is `max(1, cost)` over this field, and
     * `ItemMarketValueService` mirrors it), so the GE and the stores can't drift apart.
     *
     * Null means the cache has **no credible value** for the item. That case must stay null and must not
     * be papered over with a `maxOf(1, …)`: doing that used to hand every unvalued commodity a 1 gp
     * ceiling, which [backstopSweep] then filled instantly — a 1 gp buy for anything. An item with no
     * value simply gets no NPC band and no backstop, and floats purely player-to-player.
     */
    fun economyValue(itemId: Int): Int? =
        runCatching { getItem(itemId).cost }.getOrNull()?.takeIf { it > 0 }

    /** Ceiling = full item value; null when not a backstopped commodity, or when it has no value. */
    private fun commodityCeiling(itemId: Int): Int? =
        if (isBackstopped(itemId)) economyValue(itemId) else null

    /** Floor = [ItemCurrency.BUY_RATE] (70%) of value — the same rate every NPC buyer pays (coin
     *  shops, Trading Post), so a sell offer can never do worse here than vendoring. */
    private fun commodityFloor(itemId: Int): Int? =
        commodityCeiling(itemId)?.let { (it * ItemCurrency.BUY_RATE).toInt().coerceAtLeast(1) }

    /** The `(min, max)` prices the book will accept for [itemId], or null when it has no value to band
     *  against (see [economyValue]). The offer-setup window shows this so a price is refused *before*
     *  the player commits, not after. */
    fun priceBand(itemId: Int): Pair<Int, Int>? =
        economyValue(itemId)?.let { GrandExchangePricing.bounds(it) }

    // ---- display helpers for the offer window (UI reads these) ---------------------------------

    /** Guide price (economy value) shown in the offer-setup box; 1 when the item has no value. */
    fun guidePrice(itemId: Int): Int = economyValue(itemId) ?: 1

    /** The store band `(floor, ceiling)` if [itemId] is a backstopped commodity, else null (floats). */
    fun band(itemId: Int): Pair<Int, Int>? {
        val ceil = commodityCeiling(itemId) ?: return null
        val floor = commodityFloor(itemId) ?: return null
        return floor to ceil
    }

    // ---- market view (read-only order-book queries for the offer-setup panel) -------------------
    // All ignore the viewer's own book position; they simply summarise open interest so a player can
    // price sensibly. "Ask" = an open sell (you buy from it); "bid" = an open buy (you sell to it).

    private fun openSells(itemId: Int) =
        offers.filter { !it.buy && it.itemId == itemId && it.isActive && it.remaining > 0 }

    private fun openBuys(itemId: Int) =
        offers.filter { it.buy && it.itemId == itemId && it.isActive && it.remaining > 0 }

    /** Lowest open sell price (what it costs to buy right now), or null if nobody is selling. */
    fun bestAsk(itemId: Int): Int? = openSells(itemId).minOfOrNull { it.price }

    /** Highest open buy price (what you'd get selling right now), or null if nobody is buying. */
    fun bestBid(itemId: Int): Int? = openBuys(itemId).maxOfOrNull { it.price }

    /** Number of distinct open sell / buy offers (the "N selling / N buying" readout). */
    fun sellDepth(itemId: Int): Int = openSells(itemId).size
    fun buyDepth(itemId: Int): Int = openBuys(itemId).size

    /** Top [n] open sells as `(price, totalQty)`, cheapest first (the listings a buyer walks up). */
    fun topAsks(itemId: Int, n: Int): List<Pair<Int, Int>> = aggregate(openSells(itemId), ascending = true, n)

    /** Top [n] open buys as `(price, totalQty)`, dearest first (the bids a seller can hit). */
    fun topBids(itemId: Int, n: Int): List<Pair<Int, Int>> = aggregate(openBuys(itemId), ascending = false, n)

    private fun aggregate(os: List<GeOffer>, ascending: Boolean, n: Int): List<Pair<Int, Int>> {
        val byPrice = os.groupBy { it.price }.map { (price, list) -> price to list.sumOf { it.remaining } }
        val sorted = if (ascending) byPrice.sortedBy { it.first } else byPrice.sortedByDescending { it.first }
        return sorted.take(n)
    }

    /** Smart default price: the best offer on the side the player takes (lowest sell for a buy,
     *  highest buy for a sell), then the last real trade, then the cache guide when both are empty. */
    fun guideFor(itemId: Int, buy: Boolean): Int =
        (if (buy) bestAsk(itemId) else bestBid(itemId)) ?: MarketMemory.last(itemId) ?: guidePrice(itemId)

    // ---- the clerk's market read (memory + live book → a human recommendation) ------------------

    /** The last price [itemId] actually changed hands at here, or null if it never has. */
    fun lastTrade(itemId: Int): Int? = MarketMemory.last(itemId)

    /** Momentum trend in permille (‰); positive = climbing, negative = sliding, null = no memory. */
    fun trendPermille(itemId: Int): Int? = MarketMemory.trendPermille(itemId)

    /**
     * The clerk's one-line pricing advice for an offer of [qty]×[itemId] at [price] on the [buy]/sell
     * side, spoken in his post-collapse voice. Reads the live book (best ask/bid) and the ledger
     * ([MarketMemory.last]) so it's grounded in what's actually happening, not the static guide.
     */
    fun advice(itemId: Int, buy: Boolean, price: Int, @Suppress("UNUSED_PARAMETER") qty: Int = 1): String {
        val ask = bestAsk(itemId)
        val bid = bestBid(itemId)
        val last = MarketMemory.last(itemId)
        if (ask == null && bid == null && last == null) {
            return "Quiet market — you're setting the price on this one."
        }
        return if (buy) when {
            ask != null && price >= ask -> "Stock's on sale at your price. This fills straight away."
            last != null && price >= last -> "Fair for what these have gone for. Should fill."
            bid != null && price <= bid -> "You're bidding under folk already waiting. Patience, or pay up."
            else -> "Under recent prices — a steal if it lands, but it may sit a while."
        } else when {
            bid != null && price <= bid -> "A buyer's already waiting. That's coin in hand."
            last != null && price <= last -> "Priced to move. It'll clear soon enough."
            ask != null && price >= ask -> "Above what others are asking — you'll be last in the queue."
            else -> "Steep for this market. It'll wait for a keen buyer."
        }
    }

    /** Whether [itemId] may be listed on the GE at all (tradeable, not coins, not excluded). Used to
     *  reject a sell pick from the inventory grid before opening the setup box. */
    fun isListable(itemId: Int): Boolean =
        itemId != coinsId && itemId !in EXCLUDED &&
            !ItemMetadataService.isGeExcluded(itemId) &&
            runCatching { getItem(itemId).isTradeable }.getOrDefault(false)

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
            history.clear()
            doc.getList("history", Document::class.java)?.forEach { history.add(GeHistoryEntry.fromDocument(it)) }
            MarketMemory.load(doc.get("market", Document::class.java))
            reconcile()
            logger.info { "Loaded Grand Exchange: ${offers.size} offer(s), ${history.size} history entr(ies)." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load Grand Exchange; starting empty." }
        }
    }

    /** Post-load repair: correct any buy escrow stranded by the old drain bug (the invariant is
     *  `escrowCoins == (qty - filled) * price` for a live buy) and drop finished, fully-drained offers
     *  that were stuck on the board. Runs once per load; safe/idempotent on already-correct data. */
    private fun reconcile() {
        var repaired = 0
        for (o in offers) {
            if (o.buy && o.state != GeState.CANCELLED_BUY) {
                val correct = (o.qty - o.filled) * o.price
                if (o.escrowCoins != correct) { o.escrowCoins = correct; repaired++ }
            }
        }
        val removed = offers.removeAll { it.fullyDrained && (it.filled >= it.qty ||
            it.state == GeState.CANCELLED_BUY || it.state == GeState.CANCELLED_SELL) }
        if (repaired > 0 || removed) {
            dirty = true
            logger.info { "Grand Exchange reconcile: repaired $repaired buy escrow(s), pruned drained offers." }
        } else {
            dirty = false
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
                .append("history", history.map { it.toDocument() })
                .append("market", MarketMemory.toDocument())
            saveFile.writeText(doc.toJson(pretty))
            dirty = false
        } catch (e: Exception) {
            logger.error(e) { "Failed to save Grand Exchange; will retry." }
        }
    }
}
