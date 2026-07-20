package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.rscm.RSCM.getRSCM

/**
 * The Quartermaster's **Supply Depot** as an ordinary storefront — same window, tab rail and item
 * grid as every other shop (interface 300 + the client's `lofshoptabs` renderer), one tab per
 * [SupplyDepot.CATEGORIES] category. It replaces the old bespoke `lofsupply` overlay, whose
 * hand-rolled rows drew items at half size and looked nothing like the rest of the game's stores.
 *
 * The one difference from a normal shop is the direction of trade: the depot is **sell-only**
 * ([WarEffortCurrency]). Nothing on the shelf is for sale — the shelf is the catalogue of what the
 * war takes and the price on each cell is the War Effort it pays. Hand goods in either way:
 *  - right-click an inventory item ("Sell 1/5/10/50") — the native shop path, or
 *  - right-click a shelf cell ("Hand in 1 / 10 / All") — the window path (`::lofshopsell`).
 */
object SupplyDepotShop {

    /** Shop name per category — also the window title, so it reads as one store per tab. */
    fun shopName(label: String) = "Supply Depot — $label"

    /** Tab icons, index-aligned with [SupplyDepot.CATEGORIES]. */
    private val ICONS = listOf(
        "item.shark", "item.prayer_potion4", "item.runite_bar",
        "item.coal", "item.yew_logs", "item.raw_shark",
    )

    private val TABS: List<ShopTabs.Tab> by lazy {
        SupplyDepot.CATEGORIES.mapIndexed { i, cat ->
            ShopTabs.Tab(cat.label, shopName(cat.label), icon = ICONS.getOrNull(i))
        }
    }

    /**
     * Open the depot on the tab that pays best right now (the active Supply Drive category), so the
     * rotating bonus is the first thing the player lands on — the old window's drive banner in the
     * only form a plain storefront has.
     */
    fun open(p: Player) {
        if (!SupplyDepot.canHandIn(p)) return
        val drive = SupplyDrive.active
        if (drive != null) {
            p.message(
                "<col=ffae00>Supply Drive:</col> ${drive.display} pay ${SupplyDrive.MULTIPLIER}x War Effort at the depot today.",
            )
        }
        ShopTabs.open(p, TABS, index = drive?.ordinal ?: 0, guard = { SupplyDepot.canHandIn(it) })
    }
}

class SupplyDepotPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        SupplyDepot.CATEGORIES.forEach { cat ->
            // Stock amount 0: the shelf is a price list, not inventory the player can draw down —
            // nothing is ever bought from here, so a quantity would only be noise on the cell.
            // sellPrice is left null so the price streams from WarEffortCurrency.getSellPrice and
            // therefore always reflects the CURRENT Supply Drive multiplier.
            val stock = cat.wares.keys.mapNotNull { key ->
                runCatching { getRSCM(key) }.getOrNull()?.let { ShopItem(it, amount = 0) }
            }
            createShop(
                SupplyDepotShop.shopName(cat.label),
                WarEffortCurrency(),
                // BUY_STOCK is the honest description of the depot: it takes what's on the shelf.
                // WarEffortCurrency re-checks acceptance itself, so this is belt-and-braces.
                purchasePolicy = PurchasePolicy.BUY_STOCK,
                stockSize = maxOf(stock.size, 1),
            ) {
                stock.forEachIndexed { i, item -> items[i] = item }
            }
        }

        // NB: named ::depot, NOT ::supply — CampaignCommandPlugin already owns ::supply (the
        // realm-stores readout) and PluginRepository throws on duplicate command binds.
        onCommand("depot", description = "Open the Quartermaster's Supply Depot") {
            SupplyDepotShop.open(player)
        }
    }
}
