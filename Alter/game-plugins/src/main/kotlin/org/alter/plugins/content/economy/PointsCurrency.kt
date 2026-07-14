package org.alter.plugins.content.economy

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.shop.Shop
import org.alter.game.model.shop.ShopCurrency
import org.alter.game.model.shop.ShopItem

/**
 * A reward-shop currency backed by a [PointKind] counter instead of an inventory item.
 * Reward shops are **sell-only** (`PurchasePolicy.BUY_NONE`) — players spend points to
 * buy, and can't sell back — so only the sell path is implemented. The per-item cost is
 * the shop entry's `sellPrice` (set when defining the shop); it falls back to item value.
 */
class PointsCurrency(private val kind: PointKind) : ShopCurrency {

    override fun label(): String = kind.display

    override fun balance(p: Player): Int = p.points(kind)

    override fun getSellPrice(world: World, item: Int): Int = maxOf(1, getItem(item).cost)

    override fun getBuyPrice(world: World, item: Int): Int = 0

    override fun onSellValueMessage(p: Player, shopItem: ShopItem) {
        val value = shopItem.sellPrice ?: getSellPrice(p.world, shopItem.item)
        p.message("${Item(shopItem.item).getName()}: costs $value ${kind.display}.")
    }

    override fun onBuyValueMessage(p: Player, shop: Shop, item: Int) {
        p.message("This shop only deals in ${kind.display}.")
    }

    override fun sellToPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val shopItem = shop.items[slot] ?: return
        val cost = shopItem.sellPrice ?: getSellPrice(p.world, shopItem.item)
        val affordable = if (cost <= 0) amt else p.points(kind) / cost
        var amount = minOf(affordable, amt)
        if (amount <= 0) {
            p.message("You don't have enough ${kind.display}.")
            return
        }
        amount = minOf(amount, shopItem.currentAmount)
        if (amount <= 0) {
            p.message("The shop has run out of stock.")
            return
        }
        val total = cost * amount
        if (!p.spendPoints(kind, total)) {
            p.message("You don't have enough ${kind.display}.")
            return
        }
        val add = p.inventory.add(item = shopItem.item, amount = amount, assureFullInsertion = false)
        if (add.completed == 0) {
            p.addPoints(kind, total) // refund — no room
            p.message("You don't have enough inventory space.")
            return
        }
        if (add.getLeftOver() > 0) p.addPoints(kind, add.getLeftOver() * cost) // partial refund
        if (shopItem.amount != Int.MAX_VALUE) {
            shop.items[slot]!!.currentAmount -= add.completed
            shop.refresh(p.world)
        }
    }

    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        p.message("This shop doesn't buy items.")
    }
}
