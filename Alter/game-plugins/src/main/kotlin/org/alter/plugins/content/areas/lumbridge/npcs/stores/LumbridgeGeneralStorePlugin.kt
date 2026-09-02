package org.alter.plugins.content.areas.lumbridge.npcs.stores

import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.Shop
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.shops.GeneralStoreCurrency
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.rscm.RSCM.getRSCM

/**
 * Classic Lumbridge **general store** — Shop keeper + Shop assistant behind the counter in the
 * store building by the bridge (where the general-store minimap icon sits). Sells the basic
 * starter supplies and buys players' junk at 70% ([PurchasePolicy.BUY_TRADEABLES]) — the low-end
 * junk sink the courtyard specialist shops in [LumbridgeShopHubPlugin] deliberately don't cover.
 */
class LumbridgeGeneralStorePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val stock = STARTER_STOCK.mapNotNull { (key, amt) -> resolve(key)?.let { ShopItem(it, amt) } }
        // BUY_TRADEABLES needs spare slots for sold-in items to land in, else selling anything
        // not already stocked reports "the shop has run out of space".
        createShop(SHOP, GeneralStoreCurrency(), purchasePolicy = PurchasePolicy.BUY_TRADEABLES,
            stockSize = stock.size + Shop.DEFAULT_STOCK_SIZE) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }

        // Behind the counter, pinned (walkRadius 0) so they stay at the store. Tiles reused from
        // the old standalone general store (verified walkable).
        spawnNpc("npc.shop_keeper", 3211, 3246, 0, 0, Direction.EAST)
        spawnNpc("npc.shop_assistant", 3211, 3247, 0, 0, Direction.EAST)
        // Bind EVERY vendor option (Talk-to AND Trade) so neither is a dead click.
        bindVendorOptions("npc.shop_keeper") { open(player) }
        bindVendorOptions("npc.shop_assistant") { open(player) }
    }

    private fun open(player: Player) {
        player.openShop(SHOP)
    }

    private fun resolve(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val SHOP = "Lumbridge General Store"

        /** Classic general-store basics (resolved defensively — a missing key is skipped). */
        val STARTER_STOCK = listOf(
            "item.pot" to 10, "item.jug" to 10, "item.bucket" to 10, "item.bowl" to 10,
            "item.cake_tin" to 5, "item.tinderbox" to 10, "item.shears" to 10, "item.knife" to 10,
            "item.chisel" to 10, "item.hammer" to 10, "item.spade" to 10,
        )
    }
}
