package org.alter.game.model.shop

import org.alter.game.model.World
import org.alter.game.model.entity.Player

/**
 * Represents the currency exchange when performing transactions in a [Shop].
 *
 * @author Tom <rspsmods@gmail.com>
 */
interface ShopCurrency {
    /**
     * Called when a player selects the "value" option a [ShopItem].
     */
    fun onSellValueMessage(
        p: Player,
        shopItem: ShopItem,
    )

    /**
     * Called when a player selects the "value" option on one of their own
     * inventory items.
     */
    fun onBuyValueMessage(
        p: Player,
        shop: Shop,
        item: Int,
    )

    /**
     * Get the price at which the shop will sell [item] for.
     */
    fun getSellPrice(
        world: World,
        item: Int,
    ): Int

    /**
     * Get the price at which the shop will buy [item] for.
     */
    fun getBuyPrice(
        world: World,
        item: Int,
    ): Int

    /**
     * Called when a player attempts to buy a [ShopItem].
     */
    fun sellToPlayer(
        p: Player,
        shop: Shop,
        slot: Int,
        amt: Int,
    )

    /**
     * Called when a player attempts to sell an inventory item to the shop.
     */
    fun buyFromPlayer(
        p: Player,
        shop: Shop,
        slot: Int,
        amt: Int,
    )

    /**
     * Short plural label for this currency ("coins", "Boss Tickets", "Blood Money", "LMS points").
     * Used by the custom client shop window (lofshop) to caption prices + the balance.
     */
    fun label(): String = "coins"

    /**
     * The player's current spendable balance in this currency, for the shop window header.
     */
    fun balance(p: Player): Int = 0
}
