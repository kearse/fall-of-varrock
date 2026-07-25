package org.alter.plugins.content.economy.grandexchange

import org.bson.Document

/**
 * The Survivors' Exchange **market memory** — the clerk's ledger of what things have *actually*
 * gone for. Pure price-discovery state: no player, world, or IO, so it stays unit-testable and the
 * matcher can feed it from the hot path without a dependency tangle.
 *
 * Where the cache "guide" price ([GrandExchange.guidePrice]) is a static, pre-collapse valuation,
 * this is the living one — rebuilt only from **real player↔player fills** (see
 * [GrandExchange.applyFill]). The NPC commodity backstop is deliberately **not** recorded: it trades
 * at the fixed store floor/ceiling and would peg the signal to the band instead of the market.
 *
 * Per item we keep:
 *  - [Stat.last]   — the most recent trade price (the "last seen change hands" figure).
 *  - [Stat.ema]    — an exponential moving average of trade prices, the momentum baseline.
 *  - [Stat.volume] — total units ever traded (open-interest depth cue).
 *  - [Stat.time]   — epoch-millis of the last trade.
 *
 * From `last` vs `ema` we derive a signed **trend** (are prices climbing or sliding right now?), which
 * the client draws as an arrow the player can't compute themselves. A short global **tape** of recent
 * fills powers the "market pulse" read-outs.
 *
 * Persistence rides inside the [GrandExchange] world save ([toDocument]/[load]) — one file, one write.
 */
object MarketMemory {

    /** Smoothing factor for the momentum EMA — low enough to lag a single spike, high enough to move. */
    private const val EMA_ALPHA = 0.2

    /** How many recent fills the tape retains (the "market pulse" ring; newest at the end). */
    private const val TAPE_MAX = 40

    class Stat(
        var last: Int,
        var ema: Double,
        var volume: Long,
        var time: Long,
    )

    /** One recorded fill for the tape. */
    data class Trade(val item: Int, val qty: Int, val price: Int, val time: Long)

    private val stats = HashMap<Int, Stat>()
    private val tape = ArrayDeque<Trade>()

    /** Record a real player↔player fill of [qty] units of [item] at [price] (epoch-millis [now]). */
    fun record(item: Int, qty: Int, price: Int, now: Long) {
        if (item <= 0 || qty <= 0 || price <= 0) return
        val s = stats[item]
        if (s == null) {
            stats[item] = Stat(last = price, ema = price.toDouble(), volume = qty.toLong(), time = now)
        } else {
            s.ema += EMA_ALPHA * (price - s.ema)
            s.last = price
            s.volume += qty
            s.time = now
        }
        tape.addLast(Trade(item, qty, price, now))
        while (tape.size > TAPE_MAX) tape.removeFirst()
    }

    /** The last price [item] actually changed hands at, or null if it never has. */
    fun last(item: Int): Int? = stats[item]?.last

    /** Total units of [item] ever traded here (0 if none). */
    fun volume(item: Int): Long = stats[item]?.volume ?: 0L

    /** Epoch-millis of [item]'s last trade, or null. */
    fun lastTime(item: Int): Long? = stats[item]?.time

    /**
     * Signed momentum trend in **permille** (‰): `(last − ema) / ema × 1000`, positive when the last
     * trade sits above the moving average (prices climbing), negative below. Null when there's no
     * memory yet (the caller shows "flat"/no arrow). Clamped so a wild outlier can't overflow the wire.
     */
    fun trendPermille(item: Int): Int? {
        val s = stats[item] ?: return null
        if (s.ema <= 0.0) return null
        val permille = (s.last - s.ema) / s.ema * 1000.0
        return permille.coerceIn(-9999.0, 9999.0).let { Math.round(it).toInt() }
    }

    /** The most recent [n] fills across all items, newest first (for the market-pulse tape). */
    fun recent(n: Int): List<Trade> {
        if (n <= 0 || tape.isEmpty()) return emptyList()
        val out = ArrayList<Trade>(minOf(n, tape.size))
        val it = tape.descendingIterator()
        while (it.hasNext() && out.size < n) out.add(it.next())
        return out
    }

    // ---- persistence (folded into the GrandExchange save doc) ----------------------------------

    fun toDocument(): Document = Document()
        .append("stats", stats.map { (item, s) ->
            Document().append("item", item).append("last", s.last)
                .append("ema", s.ema).append("volume", s.volume).append("time", s.time)
        })
        .append("tape", tape.map {
            Document().append("item", it.item).append("qty", it.qty)
                .append("price", it.price).append("time", it.time)
        })

    fun load(doc: Document?) {
        stats.clear()
        tape.clear()
        if (doc == null) return
        doc.getList("stats", Document::class.java)?.forEach { d ->
            val item = d.getInteger("item", -1)
            if (item <= 0) return@forEach
            stats[item] = Stat(
                last = d.getInteger("last", 0),
                ema = (d.get("ema") as? Number)?.toDouble() ?: d.getInteger("last", 0).toDouble(),
                volume = (d.get("volume") as? Number)?.toLong() ?: 0L,
                time = (d.get("time") as? Number)?.toLong() ?: 0L,
            )
        }
        doc.getList("tape", Document::class.java)?.forEach { d ->
            tape.addLast(Trade(
                item = d.getInteger("item", -1),
                qty = d.getInteger("qty", 0),
                price = d.getInteger("price", 0),
                time = (d.get("time") as? Number)?.toLong() ?: 0L,
            ))
        }
        while (tape.size > TAPE_MAX) tape.removeFirst()
    }

    /** Test/reset hook — wipe all memory. */
    fun clear() {
        stats.clear()
        tape.clear()
    }
}
