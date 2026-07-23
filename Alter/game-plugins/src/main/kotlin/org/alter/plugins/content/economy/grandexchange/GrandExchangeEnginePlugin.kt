package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getItem
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
 * + save on a world timer, delivering completion notices + the login collect prompt) and exposes dev
 * commands that drive the engine directly. Players use the custom `lofge` window
 * ([GrandExchangeClickPlugin] + [GrandExchangeWindow]); these dev commands stay for testing/admin.
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
            GrandExchange.backstopEnabled = true // NPC floors/ceilings for the commodity allowlist
            world.timers[matchTimer] = MATCH_INTERVAL
        }
        onTimer(matchTimer) {
            val changed = GrandExchange.matchTick()
            GrandExchange.save() // no-op unless something changed
            deliverNotices()
            if (changed) refreshOnlineBoards() // so open boards show fills progressing live
            world.timers[matchTimer] = MATCH_INTERVAL
        }
        onLogout { GrandExchange.save() }

        // Login prompt so completed offers (matched while offline) are discoverable.
        onLogin {
            if (GrandExchange.hasCollectables(player.username.lowercase())) {
                player.message("<col=801700>Grand Exchange:</col> you have items waiting to collect. Talk to the clerk or use ::ge.")
            }
        }

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

    /** Repaint the board for every online player who owns an offer, so a match they're watching
     *  progresses live (bars + filled counts) without them having to reopen the window. */
    private fun refreshOnlineBoards() {
        world.players.forEach { p ->
            if (GrandExchange.ownsOffer(p.username.lowercase())) GrandExchangeWindow.refresh(p)
        }
    }

    /** Message online owners whose offers just completed (events queued by the matching pass). */
    private fun deliverNotices() {
        val pending = GrandExchange.drainNotices()
        if (pending.isEmpty()) return
        for (n in pending) {
            val who = world.players.firstOrNull { it.username.lowercase() == n.owner } ?: continue
            val name = runCatching { getItem(n.itemId).name }.getOrDefault("item ${n.itemId}")
            who.message("<col=801700>Grand Exchange:</col> your offer to ${if (n.buy) "buy" else "sell"} ${n.qty} x $name has completed.")
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
