package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getNpc
import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.inputInt
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.api.ext.searchItemInput
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
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
 * Opened by the Grand Exchange clerk (npc.grand_exchange_clerk), the GE booth on the old south
 * fountain tile (3221,3210), or `::ge`. Offer creation reuses the
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
        runCatching { spawnNpc(CLERK, 3214, 3219, 0, 0, Direction.NORTH) }
            .onFailure { logger.warn { "grand-exchange: could not spawn $CLERK; use ::ge." } }
        bindClerk(CLERK)
        onCommand("ge", description = "Open the Grand Exchange") { GrandExchangeWindow.stream(player) }

        // The GE stand on the old south courtyard fountain's footprint (fountain 879, type 10,
        // 2x2 @ 3221,3210 — the tile the Occult Altar stood on before it moved to 3215,3211).
        // CRITICAL SHAPE LESSON (dump-ge-locs, region 12598 pre-ruins cache): the real GE
        // booths are WALL-shaped locs — e.g. "3164 3487 lvl1 10061 type=0 rot=1" — not
        // type-10 scenery. A loc only draws when spawned in the shape slot its models are
        // keyed to, which is why every type-10 spawn of 10060/10061/30390 (and plain ::obj,
        // which defaults to type 10) was invisible. So: spawn a pair of booth WALLS (type 0)
        // on the fountain footprint's south edge, exactly like the GE's own south-side desks
        // (which use rot 1 with players approaching from the south).
        // The fountain removal (its own slot, type 10) still runs first in onWorldInit so
        // the stand isn't buried in the fountain model. NOTE: the NORTH fountain (3221,3226)
        // is the teleport portal — do NOT target it.
        onWorldInit {
            world.getObject(Tile(BOOTH_X, BOOTH_Z, 0), type = FOUNTAIN_TYPE)?.let { world.remove(it) }
            world.spawn(DynamicObject(getRSCM(BOOTH), type = BOOTH_TYPE, rot = BOOTH_ROT, Tile(BOOTH_X, BOOTH_Z, 0)))
            world.spawn(DynamicObject(getRSCM(BOOTH), type = BOOTH_TYPE, rot = BOOTH_ROT, Tile(BOOTH_X + 1, BOOTH_Z, 0)))
        }
        bindBooth(BOOTH)

        // New offer: the reliable native flow (Buy/Sell dialog → the ::item search → two number
        // entries), then create + re-stream the board. (A drawn in-window setup box is a v2 polish.)
        onCommand("genewclick", description = "GE window: create a new offer in a slot") {
            val box = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            if (box !in 0 until GrandExchange.SLOTS) return@onCommand
            player.queue {
                val mode = options(player, "Buy", "Sell", title = "New Grand Exchange offer")
                val buy = mode == 1
                if (mode != 1 && mode != 2) return@queue
                val item = searchItemInput(player, "Search for an item")
                if (item <= 0) return@queue
                val guide = GrandExchange.guidePrice(item)
                val qty = inputInt(player, "Quantity")
                if (qty <= 0) return@queue
                val price = inputInt(player, "Price per item (guide $guide gp)")
                if (price <= 0) return@queue
                if (buy) GrandExchange.createBuy(player, box, item, price, qty)
                else GrandExchange.createSell(player, box, item, price, qty)
                GrandExchangeWindow.stream(player)
            }
        }

        // Buy/Sell chosen on the card → open the drawn setup box for the chosen item.
        //  - Sell: the client passes the item it picked from the in-window inventory grid (arg 2), so we
        //    skip the search and open setup straight away (validate it's listable + owned first).
        //  - Buy: no item yet — the client keeps its window up and we run the native chat search; the
        //    result opens setup. On cancel we re-stream the board so the window returns to the grid.
        onCommand("gesetupclick", description = "GE window: pick an item and open the drawn setup box") {
            val a = player.getCommandArgs()
            val box = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val buy = (a.getOrNull(1)?.toIntOrNull() ?: 1) != 0
            val presetItem = a.getOrNull(2)?.toIntOrNull()
            if (box !in 0 until GrandExchange.SLOTS) return@onCommand

            if (presetItem != null && presetItem > 0) {
                if (!GrandExchange.isListable(presetItem) || player.inventory.getItemCount(presetItem) <= 0) {
                    player.message("You can't sell that on the Grand Exchange.")
                    GrandExchangeWindow.stream(player)
                    return@onCommand
                }
                GrandExchangeWindow.sendSetup(player, box, buy, presetItem)
                return@onCommand
            }

            player.queue {
                val item = searchItemInput(player, "Search for an item")
                if (item <= 0) {
                    GrandExchangeWindow.stream(player) // search cancelled → bring the board back
                    return@queue
                }
                GrandExchangeWindow.sendSetup(player, box, buy, item)
            }
        }

        // Confirm from the drawn setup box: buy/sell + item + price + qty supplied by the client.
        onCommand("geconfirmclick", description = "GE window: place an offer with supplied values") {
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
                GrandExchange.collectAll(player, toBank)
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

        // The History tab (also reachable via the clerk's "History" option, bound in bindClerk).
        onCommand("gehistoryclick", description = "GE window: show the History tab") {
            GrandExchangeWindow.streamHistory(player)
        }

        onCommand("gecloseclick", description = "GE window: close") { GrandExchangeWindow.close(player) }
    }

    /** Bind the clerk's click options: the main open (Exchange/Talk-to/…) and, when the cache def carries
     *  it, a separate "History" option that opens straight to the History tab. */
    private fun bindClerk(npc: String) {
        val acts = try {
            getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        // Prefer a real open option; don't let "history" be picked as the primary open.
        val opt = listOf("exchange", "trade", "talk-to", "view-offers").firstNotNullOfOrNull { want ->
            acts.firstOrNull { it.equals(want, ignoreCase = true) }
        } ?: acts.firstOrNull { !it.equals("history", ignoreCase = true) }
        if (opt != null) {
            onNpcOption(npc, option = opt) { GrandExchangeWindow.stream(player) }
        } else {
            logger.warn { "grand-exchange: '$npc' has no open option; use ::ge." }
        }
        // Bind "History" only if the def actually has it — onNpcOption throws on a missing option.
        acts.firstOrNull { it.equals("history", ignoreCase = true) }?.let { hist ->
            onNpcOption(npc, option = hist) { GrandExchangeWindow.streamHistory(player) }
        }
    }

    /** Bind the booth's click options the same defensive way as [bindClerk]: probe the cache def
     *  first (onObjOption throws on a missing option), prefer a real open verb, and bind the
     *  History/Collect extras only when the def carries them. */
    private fun bindBooth(obj: String) {
        val acts = try {
            getObject(getRSCM(obj)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val opt = listOf("exchange", "trade", "use", "bank").firstNotNullOfOrNull { want ->
            acts.firstOrNull { it.equals(want, ignoreCase = true) }
        } ?: acts.firstOrNull { !it.equals("history", ignoreCase = true) && !it.equals("collect", ignoreCase = true) }
        if (opt != null) {
            onObjOption(obj, option = opt) { GrandExchangeWindow.stream(player) }
        } else {
            logger.warn { "grand-exchange: '$obj' has no open option; use ::ge." }
        }
        acts.firstOrNull { it.equals("history", ignoreCase = true) }?.let { hist ->
            onObjOption(obj, option = hist) { GrandExchangeWindow.streamHistory(player) }
        }
        // Collection lives in the window itself, so "Collect" just opens the board too.
        acts.firstOrNull { it.equals("collect", ignoreCase = true) }?.let { coll ->
            onObjOption(obj, option = coll) { GrandExchangeWindow.stream(player) }
        }
    }

    private companion object {
        const val CLERK = "npc.grand_exchange_clerk"

        /** Object 10061 — the id the GE's own player-facing desks use (map-verified). */
        const val BOOTH = "object.grand_exchange_booth_10061"

        // SW corner of the old SOUTH courtyard fountain (879, 2x2) — the booth pair sits on
        // its south edge. (The north fountain @ 3221,3226 is the teleport portal — leave it
        // alone.)
        const val BOOTH_X = 3221
        const val BOOTH_Z = 3210
        const val FOUNTAIN_TYPE = 10 // the fountain's own loc slot, removed before spawning
        const val BOOTH_TYPE = 0    // WALL_STRAIGHT — the shape the GE map places 10061 as;
                                    // any other slot renders nothing (see init comment)
        const val BOOTH_ROT = 1     // matches the GE's south-side desks (3164,3487 rot 1);
                                    // players approach from the south — tune via ::obj 10061 0 <rot>
    }
}
