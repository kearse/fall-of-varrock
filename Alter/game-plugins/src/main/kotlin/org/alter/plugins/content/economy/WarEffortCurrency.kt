package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.api.ext.refreshShopBalance
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.shop.Shop
import org.alter.game.model.shop.ShopCurrency
import org.alter.game.model.shop.ShopItem

/**
 * The Quartermaster's **Supply Depot** currency: a SELL-ONLY storefront. Nothing on the shelf is for
 * sale — the shelf is the catalogue of what the war takes, priced in the War Effort it pays. Players
 * hand goods in (right-click an inventory item, or the window's Hand-in options) and the items are
 * consumed by [SupplyDepot] — this is the economy's item sink, not a trade counter.
 *
 * Shelf prices come from [SupplyDepot.valueOf], so today's [SupplyDrive] multiplier is already baked
 * into every number the window draws.
 */
class WarEffortCurrency : ShopCurrency {

    override fun label(): String = PointKind.WAR_EFFORT.display

    override fun balance(p: Player): Int = p.points(PointKind.WAR_EFFORT)

    /** Tells the client window to draw hand-in options instead of buy options. */
    override fun sellOnly(): Boolean = true

    // Both directions are the same number here: the shelf price IS what the depot pays.
    override fun getSellPrice(world: World, item: Int): Int = SupplyDepot.valueOf(item)

    override fun getBuyPrice(world: World, item: Int): Int = SupplyDepot.valueOf(item)

    override fun onSellValueMessage(p: Player, shopItem: ShopItem) = priceMessage(p, shopItem.item)

    override fun onBuyValueMessage(p: Player, shop: Shop, item: Int) = priceMessage(p, item)

    private fun priceMessage(p: Player, itemId: Int) {
        val we = SupplyDepot.valueOf(itemId)
        if (we <= 0) {
            p.message("The Quartermaster has no use for that.")
            return
        }
        p.message("${Item(itemId).getName()}: the war pays <col=801700>$we ${label()}</col> each.")
    }

    /** Buying is the whole point of what this shop ISN'T. */
    override fun sellToPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        p.message("The Quartermaster sells nothing — his depot only takes supplies for the front.")
    }

    /** Native path: right-click an inventory item while the depot is open ("Sell 1/5/10/50"). */
    override fun buyFromPlayer(p: Player, shop: Shop, slot: Int, amt: Int) {
        val item = p.inventory[slot] ?: return
        handIn(p, shop, item.id, amt)
    }

    /** Window path: the Hand-in options on a shelf cell (amt <= 0 means everything carried). */
    override fun handIn(p: Player, shop: Shop, itemId: Int, amt: Int) {
        if (SupplyDepot.valueOf(itemId) <= 0) {
            p.message("The Quartermaster has no use for that.")
            return
        }
        if (!SupplyDepot.canHandIn(p)) {
            return
        }
        val (items, we) = SupplyDepot.depositItem(p, itemId, amt)
        if (items <= 0) {
            p.message("You aren't carrying any ${Item(itemId).getName().lowercase()}.")
            return
        }
        p.message("$items ${Item(itemId).getName().lowercase()} for the front — that's <col=801700>$we ${label()}</col>.")
        // Balance only — a full re-stream would clear the storefront's tab rail client-side.
        p.refreshShopBalance()
    }
}
