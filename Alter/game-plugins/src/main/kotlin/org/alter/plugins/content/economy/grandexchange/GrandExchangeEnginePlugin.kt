package org.alter.plugins.content.economy.grandexchange

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Grand Exchange — engine lifecycle + dev test harness (Part A).**
 *
 * Owns the [GrandExchange] engine's lifecycle (load on world init, periodic [GrandExchange.matchTick]
 * + save on a world timer) and exposes dev commands that drive the engine directly, so the whole
 * order book — escrow, price-time matching, partial fills, collect, cancel — is testable in-game
 * **before** the native interface-465 offer packets are wired (that step needs the rsprot GE message
 * classes from `net.rsprot:osrs-228-api`, plus the in-game component map from `::geproto`).
 *
 * Dev commands (all `dev`-gated):
 *  - `::gebuy  <itemId> <price> <qty>`  — place a buy offer (escrows coins).
 *  - `::gesell <itemId> <price> <qty>`  — place a sell offer (escrows items from your inventory).
 *  - `::geoffers`                       — print your 8 slots (state, filled/qty, escrow, collectable).
 *  - `::gecollect <box>`                — collect a slot's proceeds to your inventory.
 *  - `::gecancel  <box>`                — cancel an active offer (unspent escrow becomes collectable).
 *  - `::gematch`                        — force a match pass now (otherwise it ticks automatically).
 */
class GrandExchangeEnginePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val matchTimer = TimerKey()
        onWorldInit {
            GrandExchange.load()
            world.timers[matchTimer] = MATCH_INTERVAL
        }
        onTimer(matchTimer) {
            GrandExchange.matchTick()
            GrandExchange.save() // no-op unless something changed
            world.timers[matchTimer] = MATCH_INTERVAL
        }
        onLogout { GrandExchange.save() }

        onCommand("gebuy", Privilege.DEV_POWER, description = "GE test: place a buy offer <item> <price> <qty>") {
            place(player, buy = true)
        }
        onCommand("gesell", Privilege.DEV_POWER, description = "GE test: place a sell offer <item> <price> <qty>") {
            place(player, buy = false)
        }
        onCommand("gematch", Privilege.DEV_POWER, description = "GE test: force a match pass") {
            GrandExchange.matchTick()
            player.message("<col=801700>GE:</col> match pass run.")
            printOffers(player)
        }
        onCommand("geoffers", Privilege.DEV_POWER, description = "GE test: print your offers") { printOffers(player) }
        onCommand("gecollect", Privilege.DEV_POWER, description = "GE test: collect a slot <box>") {
            val box = player.getCommandArgs().firstOrNull()?.toIntOrNull() ?: return@onCommand player.message("Usage: ::gecollect <box>")
            GrandExchange.collect(player, box, toBank = false)
            printOffers(player)
        }
        onCommand("gecancel", Privilege.DEV_POWER, description = "GE test: cancel a slot <box>") {
            val box = player.getCommandArgs().firstOrNull()?.toIntOrNull() ?: return@onCommand player.message("Usage: ::gecancel <box>")
            GrandExchange.cancel(player, box)
            printOffers(player)
        }
    }

    private fun place(player: Player, buy: Boolean) {
        val a = player.getCommandArgs()
        if (a.size < 3) {
            player.message("Usage: ::${if (buy) "gebuy" else "gesell"} <itemId> <price> <qty>")
            return
        }
        val item = a[0].toIntOrNull(); val price = a[1].toIntOrNull(); val qty = a[2].toIntOrNull()
        if (item == null || price == null || qty == null) {
            player.message("Item, price and quantity must be numbers.")
            return
        }
        val box = GrandExchange.slotsOf(player.username.lowercase()).indexOfFirst { it == null }
        if (box == -1) {
            player.message("All 8 Grand Exchange slots are in use.")
            return
        }
        if (buy) GrandExchange.createBuy(player, box, item, price, qty)
        else GrandExchange.createSell(player, box, item, price, qty)
        printOffers(player)
    }

    private fun printOffers(player: Player) {
        val slots = GrandExchange.slotsOf(player.username.lowercase())
        player.message("<col=801700>--- Your Grand Exchange ---</col>")
        slots.forEachIndexed { i, o ->
            if (o == null) {
                player.message("[$i] empty")
            } else {
                val kind = if (o.buy) "BUY" else "SELL"
                player.message(
                    "[$i] $kind ${o.itemId} @ ${o.price} — ${o.filled}/${o.qty} (${o.state}) " +
                        "escrow[c=${o.escrowCoins} i=${o.escrowItems}] collect[c=${o.collectCoins} i=${o.collectItems}]",
                )
            }
        }
    }

    private companion object {
        /** Ticks between automatic match passes (~3s at 600ms/tick). Cheap; the book is small. */
        const val MATCH_INTERVAL = 5
    }
}
