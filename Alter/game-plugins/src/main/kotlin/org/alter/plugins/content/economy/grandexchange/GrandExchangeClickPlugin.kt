package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.api.ext.searchItemInput
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The Grand Exchange **window channel** — opens the `lofge` overlay and handles the public-chat
 * tokens it sends (routed here by `MessagePublicHandler`). Drives the already-built [GrandExchange]
 * engine and re-streams the board via [GrandExchangeWindow] after every change.
 *
 * Client → server tokens (see `MessagePublicHandler`):
 *  - `::lofgenew <box> <buy>`                      → pick an item (native `::item` search) → show setup
 *  - `::lofgeconfirm <box> <buy> <item> <price> <qty>` → create the offer
 *  - `::lofgecollect <box|all> [bank]`             → collect proceeds
 *  - `::lofgecancel <box>`                         → abort an offer
 *  - `::lofgeclose`                                → close the window
 *
 * Opened by the Grand Exchange clerk (npc.grand_exchange_clerk) or `::ge`. Offer creation reuses the
 * native item search (`searchItemInput`, the `::item` box); quantity/price are set in the overlay and
 * arrive on the confirm token. All engine calls are the dupe-safe escrow paths.
 */
class GrandExchangeClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The GE clerk in the Lumbridge market (the tile the Trading Post reserved for it).
        runCatching { spawnNpc(CLERK, 3217, 3208, 0, 0, Direction.WEST) }
            .onFailure { logger.warn { "grand-exchange: could not spawn $CLERK; use ::ge." } }
        bindClerk(CLERK)
        onCommand("ge", description = "Open the Grand Exchange") { GrandExchangeWindow.stream(player) }

        onCommand("genewclick", description = "GE window: pick an item for a new offer") {
            val a = player.getCommandArgs()
            val box = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val buy = (a.getOrNull(1)?.toIntOrNull() ?: 1) != 0
            player.queue {
                val item = searchItemInput(player, if (buy) "Buy which item?" else "Sell which item?")
                if (item <= 0) return@queue
                GrandExchangeWindow.sendSetup(player, box, buy, item)
            }
        }

        onCommand("geconfirmclick", description = "GE window: place an offer") {
            val a = player.getCommandArgs()
            val box = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val buy = (a.getOrNull(1)?.toIntOrNull() ?: 1) != 0
            val item = a.getOrNull(2)?.toIntOrNull() ?: return@onCommand
            val price = a.getOrNull(3)?.toIntOrNull() ?: return@onCommand
            val qty = a.getOrNull(4)?.toIntOrNull() ?: return@onCommand
            if (buy) GrandExchange.createBuy(player, box, item, price, qty)
            else GrandExchange.createSell(player, box, item, price, qty)
            GrandExchangeWindow.stream(player)
        }

        onCommand("gecollectclick", description = "GE window: collect proceeds") {
            val a = player.getCommandArgs()
            val raw = a.getOrNull(0) ?: return@onCommand
            val toBank = a.getOrNull(1)?.equals("bank", ignoreCase = true) == true
            if (raw.equals("all", ignoreCase = true)) {
                for (box in 0 until GrandExchange.SLOTS) GrandExchange.collect(player, box, toBank)
            } else {
                val box = raw.toIntOrNull() ?: return@onCommand
                GrandExchange.collect(player, box, toBank)
            }
            GrandExchangeWindow.stream(player)
        }

        onCommand("gecancelclick", description = "GE window: abort an offer") {
            val box = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            GrandExchange.cancel(player, box)
            GrandExchangeWindow.stream(player)
        }

        onCommand("gecloseclick", description = "GE window: close") { GrandExchangeWindow.close(player) }
    }

    /** Bind whichever click option the clerk's cache def actually carries (Exchange/Talk-to/…). */
    private fun bindClerk(npc: String) {
        val acts = try {
            getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val opt = listOf("exchange", "trade", "talk-to", "view-offers", "history").firstNotNullOfOrNull { want ->
            acts.firstOrNull { it.equals(want, ignoreCase = true) }
        } ?: acts.firstOrNull()
        if (opt != null) {
            onNpcOption(npc, option = opt) { GrandExchangeWindow.stream(player) }
        } else {
            logger.warn { "grand-exchange: '$npc' has no click options; use ::ge." }
        }
    }

    private companion object {
        const val CLERK = "npc.grand_exchange_clerk"
    }
}
