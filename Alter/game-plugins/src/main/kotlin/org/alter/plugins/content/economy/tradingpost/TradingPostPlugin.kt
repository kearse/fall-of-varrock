package org.alter.plugins.content.economy.tradingpost

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Trading Post** (Phase 5 marketplace — see the growth roadmap).
 *
 * A single Lumbridge marketplace that buys and sells (almost) any tradeable at value-derived
 * prices via [TradingPostCurrency], acting as the **NPC liquidity backstop** so a market works
 * even at low population. It reuses the shop engine's [PurchasePolicy.BUY_TRADEABLES] mode:
 * the shelf carries NO house stock — everything listed is something a player sold, waiting for
 * another player to buy it back. The buy/sell margin is the gp **sink** (see the currency).
 *
 * Forward-compatible: a player-to-player offer book can later match *inside* the spread and
 * fall through to this backstop, with no new venue for players to learn.
 */
class TradingPostPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // No seeded stock: the post opens empty and fills from player sales only. Day-one
        // buying lives at the coin shops and the GE's commodity backstop instead.
        createShop(SHOP_NAME, TradingPostCurrency(), purchasePolicy = PurchasePolicy.BUY_TRADEABLES, stockSize = STOCK_SIZE) {}

        // Exchange Clerk not spawned in Lumbridge for now — the Trading Post will live at the
        // Grand Exchange instead. (Shop + command stay registered for when it's placed there.)
        // spawnNpc(CLERK, 3217, 3208, 0, 0, Direction.WEST)
        // bindClerk(CLERK)

        onCommand("market", description = "Open the Trading Post") { open(player) }
    }

    private fun open(player: Player) {
        player.message("<col=801700>Welcome to the Trading Post. Sell to me at market value (minus a small trade margin), or buy what others have sold.</col>")
        player.openShop(SHOP_NAME)
    }

    /** Bind whatever click option the clerk actually has (cache defs vary) to open the post. */
    private fun bindClerk(npc: String) {
        val acts = try {
            getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val opt = listOf("trade", "exchange", "talk-to", "view-offers").firstNotNullOfOrNull { want ->
            acts.firstOrNull { it.equals(want, true) }
        } ?: acts.firstOrNull()
        if (opt != null) {
            onNpcOption(npc, option = opt) { open(player) }
        } else {
            logger.warn { "trading-post: '$npc' has no click options; use ::market to reach it." }
        }
    }

    private companion object {
        const val SHOP_NAME = "Trading Post"
        const val CLERK = "npc.grand_exchange_clerk"
        const val STOCK_SIZE = 40 // shop display cap; fills entirely from player sales
    }
}
