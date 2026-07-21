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
 * FOV_GE:end                                                               (commit board)
 * FOV_GE:bal|coins                                                         (coin readout: inventory + bank)
 * FOV_GE:setup|box|buy|item|guide|floor|ceil                               (enter offer-setup for box; floor/ceil = -1 if unbanded)
 * FOV_GE:close                                                             (server-driven close)
 * ```
 * `state` uses [GeState.wire] (EMPTY 0 … SOLD 6 — the client's enum ordinals). `buy` is 1/0.
 */
object GrandExchangeWindow {
    const val PREFIX = "FOV_GE:"

    private val coinsId: Int by lazy { getRSCM("item.coins_995") }

    private fun line(p: Player, body: String) = p.message("$PREFIX$body", ChatMessageType.GAME_MESSAGE)

    private fun coins(p: Player): Long =
        p.inventory.getItemCount(coinsId).toLong() + p.bank.getItemCount(coinsId).toLong()

    /** Open (or refresh) the window: the full 8-slot board + the coin readout. */
    fun stream(p: Player) {
        line(p, "open")
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
        line(p, "end")
        line(p, "bal|${coins(p)}")
    }

    /** Tell the client to show the offer-setup view for [box] with the picked item + its guide/band. */
    fun sendSetup(p: Player, box: Int, buy: Boolean, item: Int) {
        val guide = GrandExchange.guidePrice(item)
        val band = GrandExchange.band(item)
        line(p, "setup|$box|${if (buy) 1 else 0}|$item|$guide|${band?.first ?: -1}|${band?.second ?: -1}")
    }

    fun close(p: Player) = line(p, "close")
}
