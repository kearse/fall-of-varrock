package org.alter.plugins.content.areas.lumbridge.npcs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.war.ApprenticeArmoury
import org.alter.plugins.content.war.ArmourTier
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **Smithing Apprentice** — a metal armour vendor (bronze → rune) in the
 * Lumbridge cellar (beside the mine: mine ore, buy gear). He stocks only the lesser
 * pieces (med helm, chainbody, platelegs, square shield) — players must smith the
 * platebody / full helm / kiteshield themselves. What he'll sell you is
 * gated by your **feudal rank** ([title]) — he only shows metals your station may
 * wear (a Peasant sees bronze/iron, a Lord sees up to rune), matching the armour gate
 * in [org.alter.plugins.content.war.TitlePlugin].
 *
 * Implemented as one static shop per tier cap; talking to him opens the one that
 * fits the player's rank.
 */
class SmithingApprenticePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        ApprenticeArmoury.TIER_SHOPS.forEach { (cap, shopName) ->
            val stock = buildStock(cap)
            createShop(shopName, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_STOCK, stockSize = maxOf(stock.size, 1)) {
                stock.forEachIndexed { i, id -> items[i] = ShopItem(item = id, amount = SHOP_AMOUNT) }
            }
        }

        spawnNpc("npc.smithing_apprentice", x = 3213, z = 9620, height = 0, walkRadius = 0, direction = Direction.SOUTH)

        // Bind EVERY vendor option (Talk-to AND Trade) so neither is a dead click. Resolved
        // defensively so plugin load can't crash on an unexpected action set.
        if (!bindVendorOptions("npc.smithing_apprentice") { ApprenticeArmoury.open(player) }) {
            logger.warn { "smithing apprentice has no usable option to bind the shop to." }
        }
    }

    /** Every metal item from bronze up to [cap] that exists in the cache. */
    private fun buildStock(cap: ArmourTier): List<Int> {
        val ids = mutableListOf<Int>()
        for ((prefix, tier) in METALS) {
            if (tier.ordinal > cap.ordinal) continue
            for (piece in PIECES) {
                val id = try { getRSCM("item.${prefix}_$piece") } catch (e: Exception) { null }
                if (id != null) ids += id
            }
        }
        return ids
    }

    private companion object {
        const val SHOP_AMOUNT = 1000 // plenty of stock per item

        val METALS = listOf(
            "bronze" to ArmourTier.BRONZE,
            "iron" to ArmourTier.IRON,
            "steel" to ArmourTier.STEEL,
            "black" to ArmourTier.BLACK,
            "mithril" to ArmourTier.MITHRIL,
            "adamant" to ArmourTier.ADAMANT,
            "rune" to ArmourTier.RUNE,
        )

        // Armour only, and deliberately the *lesser* pieces — players must smith the
        // platebody / full helm / kiteshield themselves.
        val PIECES = listOf("med_helm", "chainbody", "platelegs", "sq_shield")
    }
}
