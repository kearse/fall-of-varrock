package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.rscm.RSCM.getRSCM

/**
 * **Prestige Shop** (shop-economy-redesign §4) — the first sink for Prestige, which only
 * commanders earn (sponsoring boss summons 25-40, winning campaigns 25 / conquests 60) and
 * which previously bought nothing.
 *
 * Stock: the **Warlord's Regalia** — the sanguine (stat-less recolor) Torva set, moved here
 * from the old Armoury boss-point shelf. A commander's flex, priced at several won campaigns
 * per piece, cosmetic only (the real war-forged set stays the Royal Smith's). Opened with
 * `::prestigeshop` (vendor placement TUNE — the natural host is General Zo). Prices TUNE.
 */
class PrestigeShopPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val wares = listOf(
        "item.sanguine_torva_full_helm" to 75,
        "item.sanguine_torva_platebody" to 100,
        "item.sanguine_torva_platelegs" to 100,
    )

    init {
        val stock = wares.mapNotNull { (key, price) ->
            runCatching { getRSCM(key) }.getOrNull()?.let { ShopItem(it, STOCK, price) }
        }
        createShop(SHOP_NAME, PointsCurrency(PointKind.PRESTIGE),
            purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
        // Point-shop wares never NPC-convert to gp (alch / Trading Post / General Store) — the audit's guard rule.
        SpecialShopGuard.register(stock.map { it.item })

        onCommand("prestigeshop", description = "Open the Prestige Shop (commanders' regalia)") {
            player.openShop(SHOP_NAME)
        }
    }

    private companion object {
        const val SHOP_NAME = "Commander's Regalia"
        const val STOCK = 100
    }
}
