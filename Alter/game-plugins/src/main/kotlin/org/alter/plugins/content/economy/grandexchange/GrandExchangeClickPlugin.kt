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
 * Opened by the Grand Exchange clerk (npc.grand_exchange_clerk), the GE hub desks on the old
 * south fountain site (ring at 3221-3226 x 3208-3213), or `::ge`. Offer creation reuses the
 * native item search (`searchItemInput`, the `::item` box); quantity/price are set in the overlay and
 * arrive on the confirm token. All engine calls are the dupe-safe escrow paths.
 */
class GrandExchangeClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The GE clerk inside the hub's desk ring — west slot of the north face, behind the
        // north desks, facing the players outside.
        runCatching { spawnNpc(CLERK, 3221, 3212, 0, 0, Direction.NORTH) }
            .onFailure { logger.warn { "grand-exchange: could not spawn $CLERK; use ::ge." } }
        bindClerk(CLERK)
        onCommand("ge", description = "Open the Grand Exchange") { GrandExchangeWindow.stream(player) }

        // The full Grand Exchange hub — the central 2x2 pillar ringed by clerk desks — rebuilt
        // tile-for-tile on the old south courtyard fountain's footprint (fountain 879, type 10,
        // 2x2 @ 3221,3210 — the tile the Occult Altar stood on before it moved to 3215,3211).
        // HUB_PARTS is a verbatim copy of the real GE's central structure (dump-ge-locs,
        // region 12598 pre-ruins cache, level-1 bridge tiles at 3162-3167 x 3487-3492): booth
        // walls 10061/10060/30389 (loc shape 0), desk corner pieces 10059/10062 (shapes 1/9),
        // the 10063 pillar (shape 10, 2x2) and two shape-22 floor decors. Each part MUST keep
        // its dumped shape — a loc only draws in the shape slot its models are keyed to, which
        // is why earlier type-10 spawns of the booth ids (and plain ::obj) were invisible.
        // The old fountain (anchor 3221,3210) is removed first regardless of where the hub
        // anchor sits — the ring overlaps its footprint. NOTE: the NORTH fountain (3221,3226)
        // is the teleport portal — do NOT target it.
        onWorldInit {
            world.getObject(Tile(FOUNTAIN_X, FOUNTAIN_Z, 0), type = FOUNTAIN_TYPE)?.let { world.remove(it) }
            HUB_PARTS.forEach { p ->
                world.spawn(DynamicObject(getRSCM(p.obj), type = p.type, rot = p.rot, Tile(HUB_X + p.dx, HUB_Z + p.dz, 0)))
            }
        }
        bindBooth(BOOTH_10061)
        bindBooth(BOOTH_10060)
        bindBooth(BOOTH_30389)

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

        // The clickable desk ids in the hub (all bound to open the GE, def-probed first).
        const val BOOTH_10061 = "object.grand_exchange_booth_10061"
        const val BOOTH_10060 = "object.grand_exchange_booth"
        const val BOOTH_30389 = "object.null_30389"

        // Ring SW corner: the 6x6 hub spans 3221-3226 x 3208-3213. Originally anchored two
        // tiles further west (pillar on the fountain tile), but the courtyard hill starts
        // around x<=3220 and the ring's west wall floated at the slope's height — so the
        // whole hub sits shifted east on the flat ground by the path. The old SOUTH
        // fountain's 2x2 footprint (anchor 3221,3210) is inside the ring and removed below.
        // (The north fountain @ 3221,3226 is the teleport portal — leave it alone.)
        const val HUB_X = 3221
        const val HUB_Z = 3208
        const val FOUNTAIN_X = 3221
        const val FOUNTAIN_Z = 3210
        const val FOUNTAIN_TYPE = 10 // the fountain's own loc slot, removed before spawning

        /** One loc of the hub: rscm key + offset from (HUB_X, HUB_Z) + its dumped shape/rot. */
        data class HubPart(val obj: String, val dx: Int, val dz: Int, val type: Int, val rot: Int)

        /** Verbatim from dump-ge-locs (GE ring at 3162,3487 → offsets), row by row south→north. */
        val HUB_PARTS = listOf(
            // south face: corners + the two south desks (players approach from here)
            HubPart("object.null_10059", 1, 0, 1, 1),
            HubPart(BOOTH_10061, 2, 0, 0, 1),
            HubPart(BOOTH_10061, 3, 0, 0, 1),
            HubPart("object.null_10059", 4, 0, 1, 0),
            HubPart("object.null_16458", 5, 0, 22, 0),
            HubPart("object.null_10059", 0, 1, 1, 1),
            HubPart("object.null_10059", 1, 1, 9, 1),
            HubPart("object.null_10062", 4, 1, 9, 0),
            HubPart("object.null_10059", 5, 1, 1, 0),
            // middle rows: west/east desks + the central 2x2 pillar
            HubPart(BOOTH_10060, 0, 2, 0, 2),
            HubPart("object.null_10063", 2, 2, 10, 0),
            HubPart(BOOTH_10060, 5, 2, 0, 0),
            HubPart(BOOTH_30389, 0, 3, 0, 2),
            HubPart(BOOTH_10060, 5, 3, 0, 0),
            // north face: corners + the two north desks
            HubPart("object.null_10059", 0, 4, 1, 2),
            HubPart("object.null_10059", 1, 4, 9, 2),
            HubPart("object.null_10059", 4, 4, 9, 3),
            HubPart("object.null_10059", 5, 4, 1, 3),
            HubPart("object.null_2738", 0, 5, 22, 0),
            HubPart("object.null_10059", 1, 5, 1, 2),
            HubPart(BOOTH_10061, 2, 5, 0, 3),
            HubPart(BOOTH_10061, 3, 5, 0, 3),
            HubPart("object.null_10059", 4, 5, 1, 3),
        )
    }
}
