package org.alter.plugins.content.mechanics.shops

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.api.ext.refreshShopSlot
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.rscm.RSCM.getRSCM
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.Shop
import org.alter.game.model.shop.ShopCurrency
import org.alter.game.model.shop.ShopItem
import org.alter.plugins.content.war.warprep.WarPrepChain

/**
 * @author Tom <rspsmods@gmail.com>
 */
open class ItemCurrency(
    /** The inventory item this currency is paid in (coins, a ticket, blood money). Public so the
     *  offline economy auditor can identify a shop's currency node without parsing labels. */
    val currencyItem: Int,
    private val singularCurrency: String,
    private val pluralCurrency: String,
) : ShopCurrency {
    private data class AcceptItemState(val acceptable: Boolean, val errorMessage: String)

    private fun canAcceptItem(
        shop: Shop,
        world: World,
        item: Int,
    ): AcceptItemState {
        if (item == getRSCM("item.coins_995") || item == getRSCM("item.blood_money")) {
            return AcceptItemState(acceptable = false, errorMessage = "You can't sell this item to a shop.")
        }
        when {
            shop.purchasePolicy == PurchasePolicy.BUY_TRADEABLES -> {
                if (!Item(item).getDef().isTradeable) {
                    return AcceptItemState(acceptable = false, errorMessage = "You can't sell this item.")
                }
            }
            shop.purchasePolicy == PurchasePolicy.BUY_STOCK -> {
                if (shop.items.none { it?.item == item }) {
                    return AcceptItemState(acceptable = false, errorMessage = "You can't sell this item to this shop.")
                }
            }
            shop.purchasePolicy == PurchasePolicy.BUY_ALL -> return AcceptItemState(acceptable = true, errorMessage = "")
            shop.purchasePolicy == PurchasePolicy.BUY_NONE -> return AcceptItemState(
                acceptable = false,
                errorMessage = "You can't sell any items to this shop.",
            )
            else -> throw RuntimeException("Unhandled purchase policy. [shop=${shop.name}, policy=${shop.purchasePolicy}]")
        }
        return AcceptItemState(acceptable = true, errorMessage = "")
    }

    override fun onSellValueMessage(
        p: Player,
        shopItem: ShopItem,
    ) {
        val unnoted = Item(shopItem.item).toUnnoted()
        val value = shopItem.sellPrice ?: getSellPrice(p.world, unnoted.id)
        val name = unnoted.getName()
        val currency = if (value != 1) pluralCurrency else singularCurrency
        p.message("$name: currently costs $value $currency")
    }

    override fun onBuyValueMessage(
        p: Player,
        shop: Shop,
        item: Int,
    ) {
        val unnoted = Item(item).toUnnoted()
        val acceptance = canAcceptItem(shop, p.world, unnoted.id)
        if (acceptance.acceptable) {
            val shopItem = shop.items.filterNotNull().firstOrNull { it.item == item }
            val value = shopItem?.buyPrice ?: getBuyPrice(p.world, unnoted.id)
            val name = unnoted.getName()
            val currency = if (value != 1) pluralCurrency else singularCurrency
            p.message("$name: shop will buy for $value $currency")
        } else {
            p.message(acceptance.errorMessage)
        }
    }

    override fun label(): String = pluralCurrency

    /** Spendable = inventory + bank, so a player can shop straight out of the bank. */
    override fun balance(p: Player): Int = spendable(p).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Total currency the player can spend (inventory first, then bank). */
    private fun spendable(p: Player): Long =
        p.inventory.getItemCount(currencyItem).toLong() + p.bank.getItemCount(currencyItem).toLong()

    /**
     * Deduct [total] currency, taking from the inventory first and the bank for the remainder.
     * Returns false (and leaves the player untouched) if they can't cover it.
     */
    private fun spendCurrency(p: Player, total: Int): Boolean {
        if (spendable(p) < total) {
            return false
        }
        val fromInv = Math.min(p.inventory.getItemCount(currencyItem), total)
        if (fromInv > 0) {
            val r = p.inventory.remove(item = currencyItem, amount = fromInv, assureFullRemoval = true)
            if (r.hasFailed()) {
                return false
            }
        }
        val fromBank = total - fromInv
        if (fromBank > 0) {
            val r = p.bank.remove(item = currencyItem, amount = fromBank, assureFullRemoval = true)
            if (r.hasFailed()) {
                if (fromInv > 0) p.inventory.add(item = currencyItem, amount = fromInv) // refund the inv portion
                return false
            }
        }
        return true
    }

    override fun getSellPrice(
        world: World,
        item: Int,
    ): Int = Math.max(1, getItem(item).cost)

    override fun getBuyPrice(
        world: World,
        item: Int,
    ): Int = (getItem(item).cost * BUY_RATE).toInt().coerceAtLeast(1)

    override fun sellToPlayer(
        p: Player,
        shop: Shop,
        slot: Int,
        amt: Int,
    ) {
        val shopItem = shop.items[slot] ?: return

        val currencyCost = shopItem.sellPrice ?: getSellPrice(p.world, shopItem.item)
        // Affordability is inventory + bank (see [spendable]).
        val available = spendable(p)

        var amount = if (currencyCost <= 0) amt else Math.min(available / currencyCost, amt.toLong()).toInt()

        if (amount == 0) {
            p.message("You don't have enough $pluralCurrency.")
            return
        }

        val moreThanStock = amount > shopItem.currentAmount

        amount = Math.min(amount, shopItem.currentAmount)

        if (amount == 0) {
            p.message("The shop has run out of stock.")
            return
        }

        if (moreThanStock) {
            p.message("The shop has run out of stock.")
        }

        val totalCost = currencyCost.toLong() * amount.toLong()
        if (totalCost > Int.MAX_VALUE) {
            return
        }

        if (!spendCurrency(p, totalCost.toInt())) {
            p.message("You don't have enough $pluralCurrency.")
            return
        }

        val add = p.inventory.add(item = shopItem.item, amount = amount, assureFullInsertion = false)
        if (add.completed == 0) {
            p.message("You don't have enough inventory space.")
        }

        if (add.getLeftOver() > 0) {
            val refund = add.getLeftOver() * currencyCost
            p.inventory.add(item = currencyItem, amount = refund)
        }

        if (add.completed > 0 && shopItem.amount != Int.MAX_VALUE) {
            shop.items[slot]!!.currentAmount -= add.completed

            /*
             * Check if the item is temporary and should be removed from the shop.
             */
            if (shop.items[slot]?.amount == 0 && shop.items[slot]?.isTemporary == true) {
                shop.items[slot] = null
            }

            shop.refresh(p.world)
        }
        // Push the changed cell + balance to the custom shop window even for infinite-stock shops,
        // so a bank-funded buy visibly deducts coins (otherwise it looks free) and the stock count
        // stays current. See [Player.refreshShopSlot].
        if (add.completed > 0) p.refreshShopSlot(shop, slot)
    }

    override fun buyFromPlayer(
        p: Player,
        shop: Shop,
        slot: Int,
        amt: Int,
    ) {
        val item = p.inventory[slot] ?: return
        val unnoted = item.toUnnoted().id
        // Quest-locked items can't be vendored anywhere the shop engine reaches (general stores,
        // the Trading Post, coin shops). See [WarPrepChain.bonesLocked].
        if (WarPrepChain.bonesLocked(p, unnoted)) {
            WarPrepChain.warnBonesLocked(p)
            return
        }
        val acceptance = canAcceptItem(shop, p.world, unnoted)

        if (!acceptance.acceptable) {
            p.message(acceptance.errorMessage)
            return
        }

        val shopSlot = shop.items.indexOfFirst { it?.item == unnoted }
        val shopItem = if (shopSlot != -1) shop.items[shopSlot] else null
        val count = shopItem?.currentAmount ?: 0

        val amount = Math.min(Math.min(p.inventory.getItemCount(item.id), amt), Int.MAX_VALUE - count)

        if (count == 0 && shop.items.none { it == null } || amount == 0) {
            p.message("The shop has run out of space.")
            return
        }

        val remove = p.inventory.remove(item = item.id, amount = amount, assureFullRemoval = false)
        if (remove.completed == 0) {
            return
        }

        val price = shopItem?.buyPrice ?: getBuyPrice(p.world, unnoted)
        val compensation = Math.min(Int.MAX_VALUE.toLong(), price.toLong() * remove.completed.toLong()).toInt()
        val add = p.inventory.add(item = currencyItem, amount = compensation, assureFullInsertion = true)
        if (add.requested > 0 && add.completed > 0 || compensation == 0) {
            val changedSlot: Int
            if (shopSlot != -1) {
                shop.items[shopSlot]!!.currentAmount += amount
                changedSlot = shopSlot
            } else {
                val freeSlot = shop.items.indexOfFirst { it == null }
                check(freeSlot != -1)
                shop.items[freeSlot] = ShopItem(unnoted, amount = 0)
                shop.items[freeSlot]!!.currentAmount = amount
                changedSlot = freeSlot
            }
            shop.refresh(p.world)
            p.refreshShopSlot(shop, changedSlot) // update the custom shop window's cell + balance
        } else {
            p.inventory.add(item.id, amount = remove.completed, beginSlot = slot)
            p.message("You don't have enough inventory space.")
        }
    }

    companion object {
        /**
         * Fraction of an item's cache value an NPC pays when buying FROM a player — shared by
         * every coin shop, the Trading Post, and the GE's commodity floor. Kept above high alch
         * (60%) so vendoring always beats alching; better prices only come from other players
         * on the Grand Exchange (up to 100% of value).
         */
        const val BUY_RATE = 0.7
    }
}
