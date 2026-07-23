package org.alter.plugins.content.economy.grandexchange

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Server → custom-client transport for the Grand Exchange window (the `lofge` overlay), mirroring the
 * shop window's `FOV_SHOP:` stream (`PlayerExt.streamShopToClient`). Lines go out as
 * [ChatMessageType.GAME_MESSAGE] and the client hides them from chat via its `chatFilterCheck` block.
 *
 * Wire format (client `LofGeOverlay` parses this):
 * ```
 * FOV_GE:open                                                              (show window; reset board buffer)
 * FOV_GE:slot|box|state|buy|item|price|qty|filled|collectCoins|collectItems   (8 lines, one per box; empty = state 0)
 * FOV_GE:end                                                               (commit board; open the window)
 * FOV_GE:refreshopen / … / FOV_GE:refreshend                               (live board repaint; no open)
 * FOV_GE:bal|coins                                                         (coin readout: inventory + bank)
 * FOV_GE:setup|box|buy|item|guide|floor|ceil|bestAsk|bestBid|nSell|nBuy|own (enter offer-setup; -1 where absent)
 * FOV_GE:ask|price|qty                                                     (0..N top open sells, cheapest first)
 * FOV_GE:bid|price|qty                                                     (0..N top open buys, dearest first)
 * FOV_GE:setupend                                                          (commit the setup view)
 * FOV_GE:histopen                                                          (open the History tab; reset)
 * FOV_GE:hist|buy|item|qty|price|time                                      (one completed trade, newest first)
 * FOV_GE:histend                                                           (commit the History tab)
 * FOV_GE:close                                                             (server-driven close)
 * ```
 * `state` uses [GeState.wire] (EMPTY 0 … SOLD 6 — the client's enum ordinals). `buy` is 1/0.
 * `guide` is the cache value (the "Guide X gp" readout + guide button); `bestAsk/bestBid` are the live
 * market prices the client uses for its smart default. `own` is how many of the item the player holds
 * (sell only — the client defaults/caps the sell quantity to it; 0 for a buy). `floor/ceil/bestAsk/
 * bestBid = -1` when absent.
 */
object GrandExchangeWindow {
    const val PREFIX = "FOV_GE:"

    /** How many price levels of each side of the book to stream into the setup panel. */
    private const val MARKET_ROWS = 5

    private val coinsId: Int by lazy { getRSCM("item.coins_995") }

    private fun line(p: Player, body: String) = p.message("$PREFIX$body", ChatMessageType.GAME_MESSAGE)

    private fun coins(p: Player): Long =
        p.inventory.getItemCount(coinsId).toLong() + p.bank.getItemCount(coinsId).toLong()

    private fun writeSlots(p: Player) {
        val slots = GrandExchange.slotsOf(p.username.lowercase())
        for (box in 0 until GrandExchange.SLOTS) {
            val o = slots[box]
            if (o == null) {
                line(p, "slot|$box|0|0|0|0|0|0|0|0")
            } else {
                line(
                    p,
                    "slot|$box|${o.state.wire}|${if (o.buy) 1 else 0}|${o.itemId}|${o.price}|${o.qty}|${o.filled}|${o.collectCoins}|${o.collectItems}",
                )
            }
        }
        line(p, "bal|${coins(p)}")
    }

    /** Open the window on the board: the full 8-slot board + the coin readout (shows the window). */
    fun stream(p: Player) {
        line(p, "open")
        writeSlots(p)
        line(p, "end")
    }

    /** Live board update (from the match tick) — repaints the board *in place* without opening the
     *  window or disturbing an open setup/history view. The client applies the slots but never changes
     *  visibility, so a fill you're watching progresses live and one you're not never pops the window. */
    fun refresh(p: Player) {
        line(p, "refreshopen")
        writeSlots(p)
        line(p, "refreshend")
    }

    /** Tell the client to show the offer-setup view for [box] with the chosen buy/sell + the picked
     *  item + its guide/band + a snapshot of the live market (best prices, depth, top listings). The
     *  client owns quantity + price; confirm arrives via `geconfirmclick`. */
    fun sendSetup(p: Player, box: Int, buy: Boolean, item: Int) {
        val guide = GrandExchange.guidePrice(item)
        val band = GrandExchange.band(item)
        val bestAsk = GrandExchange.bestAsk(item) ?: -1
        val bestBid = GrandExchange.bestBid(item) ?: -1
        val nSell = GrandExchange.sellDepth(item)
        val nBuy = GrandExchange.buyDepth(item)
        val own = if (buy) 0 else p.inventory.getItemCount(item)
        line(
            p,
            "setup|$box|${if (buy) 1 else 0}|$item|$guide|${band?.first ?: -1}|${band?.second ?: -1}|$bestAsk|$bestBid|$nSell|$nBuy|$own",
        )
        for ((price, qty) in GrandExchange.topAsks(item, MARKET_ROWS)) line(p, "ask|$price|$qty")
        for ((price, qty) in GrandExchange.topBids(item, MARKET_ROWS)) line(p, "bid|$price|$qty")
        line(p, "setupend")
    }

    /** Open the window straight to the History tab: the player's completed trades, newest first. */
    fun streamHistory(p: Player) {
        line(p, "histopen")
        for (h in GrandExchange.historyOf(p.username.lowercase())) {
            line(p, "hist|${if (h.buy) 1 else 0}|${h.itemId}|${h.qty}|${h.price}|${h.time}")
        }
        line(p, "histend")
    }

    fun close(p: Player) = line(p, "close")
}
