package org.alter.plugins.content.economy.store

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.rscm.RSCM.getRSCM

/**
 * **Donor Store** (shop-economy-redesign §4) — the first sink for Donor Points, which were
 * previously earn-only (bond redemption 450/bond, campaign donor bonus) with nowhere to spend.
 *
 * Stock policy: **cosmetic rares only, never power** (the monetization rule). The classics —
 * partyhats, santa, h'ween masks — are stat-less AND tradeable, so a donor's flex can also be
 * sold on to players for gp: cash flows in as cosmetics, spreads through the market as items.
 * Opened with `::donorstore` (vendor placement TUNE — candidates: beside the Bond Merchant).
 * Prices in Donor Points (450 = one bond) — a partyhat is ~2 bonds. TUNE.
 */
class DonorStorePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val wares = listOf(
        "item.red_partyhat" to 900,
        "item.blue_partyhat" to 900,
        "item.green_partyhat" to 900,
        "item.yellow_partyhat" to 900,
        "item.white_partyhat" to 1_350,
        "item.santa_hat" to 1_350,
        "item.red_halloween_mask" to 700,
        "item.blue_halloween_mask" to 700,
        "item.green_halloween_mask" to 700,
    )

    init {
        val stock = wares.mapNotNull { (key, price) ->
            runCatching { getRSCM(key) }.getOrNull()?.let { ShopItem(it, STOCK, price) }
        }
        createShop(SHOP_NAME, PointsCurrency(PointKind.DONOR),
            purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }

        onCommand("donorstore", description = "Open the Donor Store (Donor Points)") {
            player.openShop(SHOP_NAME)
        }
    }

    private companion object {
        const val SHOP_NAME = "Donor Store"
        const val STOCK = 100
    }
}
