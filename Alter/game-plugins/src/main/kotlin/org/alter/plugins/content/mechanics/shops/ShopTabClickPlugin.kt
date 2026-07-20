package org.alter.plugins.content.mechanics.shops

import org.alter.game.model.ExamineEntityType
import org.alter.api.ext.closeInterface
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CURRENT_SHOP_ATTR
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles the custom shop window's client channel (lofshop). The client sends public-chat tokens
 * that `MessagePublicHandler` routes here:
 *   - `::shoptab <index>`         → switch storefront tab ([ShopTabs.switch])
 *   - `::shopbuy <slot> <amount>` → buy from the open shop (reuses the native buy path)
 *   - `::shopsell <slot> <amt>`   → hand an item in at a SELL-ONLY shop (the Supply Depot)
 *   - `::shopclose`               → close the shop window
 *
 * Buy is validated exactly like the native interface: it goes through the open shop's currency,
 * which checks affordability, stock and inventory space. A stale/forged slot is a harmless no-op.
 * Each is also testable directly by typing the `...click` command in chat.
 */
class ShopTabClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("shoptabclick", description = "Switch storefront tab (client overlay channel)") {
            val index = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            ShopTabs.switch(player, index)
        }

        onCommand("shopbuyclick", description = "Buy from the open shop (client overlay channel)") {
            val a = player.getCommandArgs()
            val slot = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val amount = a.getOrNull(1)?.toIntOrNull() ?: 1
            val shop = player.attr[CURRENT_SHOP_ATTR] ?: return@onCommand
            if (slot !in shop.items.indices) return@onCommand
            if (shop.items[slot] == null) return@onCommand
            shop.currency.sellToPlayer(player, shop, slot, amount)
        }

        // Sell-only storefronts (the Quartermaster's Supply Depot): hand in from a shelf cell.
        // The amount is "all" or a positive count; the currency does the acceptance/gating checks.
        onCommand("shopsellclick", description = "Hand an item in at a sell-only shop (client overlay channel)") {
            val a = player.getCommandArgs()
            val slot = a.getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val raw = a.getOrNull(1) ?: "1"
            val amount = if (raw.equals("all", true)) 0 else raw.toIntOrNull()?.takeIf { it > 0 } ?: return@onCommand
            val shop = player.attr[CURRENT_SHOP_ATTR] ?: return@onCommand
            if (!shop.currency.sellOnly()) return@onCommand
            if (slot !in shop.items.indices) return@onCommand
            val shopItem = shop.items[slot] ?: return@onCommand
            shop.currency.handIn(player, shop, shopItem.item, amount)
        }

        onCommand("shopvalclick", description = "Value an item in the open shop (client overlay channel)") {
            val slot = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val shop = player.attr[CURRENT_SHOP_ATTR] ?: return@onCommand
            if (slot !in shop.items.indices) return@onCommand
            val shopItem = shop.items[slot] ?: return@onCommand
            shop.currency.onSellValueMessage(player, shopItem)
        }

        onCommand("shopexamineclick", description = "Examine an item in the open shop (client overlay channel)") {
            val slot = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: return@onCommand
            val shop = player.attr[CURRENT_SHOP_ATTR] ?: return@onCommand
            if (slot !in shop.items.indices) return@onCommand
            val shopItem = shop.items[slot] ?: return@onCommand
            world.sendExamine(player, shopItem.item, ExamineEntityType.ITEM)
        }

        onCommand("shopcloseclick", description = "Close the shop window (client overlay channel)") {
            player.closeInterface(interfaceId = 300)
        }
    }
}
