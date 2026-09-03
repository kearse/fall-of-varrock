package org.alter.plugins.content.mechanics.shops

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.Shop
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The two shelf-price rules every COIN shop must satisfy, checked once at world init and logged as
 * WARN so a reprice that reopens a loop is visible in the boot log the same day:
 *
 *  1. a shelf price is never below 70% of the item's cache value — the General Store and the Trading
 *     Post pay 70% of cache value, so a cheaper shelf is a buy-here-sell-there loop;
 *  2. a shop never pays more to buy a ware back than it charges for it.
 *
 * Found by the 2026-09 arbitrage audit (`gradlew :game-plugins:economyAudit`), which checks the same
 * two rules across every currency (this boot check covers coin shops, where no peg is needed).
 */
object ShopPriceSanity {

    data class Warning(val shop: String, val item: Int, val rule: String, val detail: String) {
        override fun toString() = "$shop / item $item: $rule ($detail)"
    }

    fun check(shops: Collection<Shop>, world: World): List<Warning> {
        val coins = runCatching { getRSCM("item.coins_995") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Warning>()
        for (shop in shops) {
            val currency = shop.currency as? ItemCurrency ?: continue
            if (currency.currencyItem != coins) continue
            for (si in shop.items) {
                si ?: continue
                if (si.amount <= 0) continue
                val cost = runCatching { getItem(si.item).cost }.getOrDefault(0)
                val sell = si.sellPrice ?: currency.getSellPrice(world, si.item)
                if (cost > 0 && sell < (cost * ItemCurrency.BUY_RATE).toInt()) {
                    out += Warning(shop.name, si.item, "shelf below 70% of cache value", "sell $sell, cost $cost, NPC buyback ${(cost * ItemCurrency.BUY_RATE).toInt()}")
                }
                val buysBack = shop.purchasePolicy != PurchasePolicy.BUY_NONE && si.amount != Int.MAX_VALUE
                if (buysBack) {
                    val buy = si.buyPrice ?: currency.getBuyPrice(world, si.item)
                    if (buy >= sell) out += Warning(shop.name, si.item, "buyback >= shelf price", "sell $sell, buyback $buy")
                }
            }
        }
        return out
    }
}

class ShopPriceSanityPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        onWorldInit {
            val warnings = ShopPriceSanity.check(world.plugins.shops.values.toList(), world)
            warnings.forEach { logger.warn { "shop-price-sanity: $it" } }
            if (warnings.isEmpty()) {
                logger.info { "shop-price-sanity: every coin shelf is at or above 70% of cache value and no shop pays more than it charges." }
            } else {
                logger.warn { "shop-price-sanity: ${warnings.size} shelf line(s) can be bought from one NPC and sold to another for a profit — run :game-plugins:economyAudit." }
            }
        }
    }
}
