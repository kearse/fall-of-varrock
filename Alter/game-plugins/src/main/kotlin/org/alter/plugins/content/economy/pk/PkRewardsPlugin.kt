package org.alter.plugins.content.economy.pk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Blood Money — the PK currency** (Phase 6 economy: texture + a reason to fight).
 *
 * Killing another player (including the [PkBot] fake-players, which are the PK targets on this
 * server) awards Blood Money scaled by the victim's combat level. It's an inventory item
 * (`item.blood_money`) so it can itself be risked/dropped on death. Spent at the **PK Rewards**
 * shop (the emblem trader) — a Blood-Money sink stocked with PK *supplies* (food/potions), which
 * feeds the consumption loop rather than injecting tradeable end-game gear.
 *
 * Earning is wired on [onPlayerPreDeath] (additive — runs alongside the bot kit-drop hook); only
 * real human killers earn (bots don't), and self/bot-on-player kills award nothing.
 */
class PkRewardsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val bm = getRSCM("item.blood_money")

    /** PK Rewards shop wares: PK supplies only (Blood-Money sink). price = Blood Money cost. */
    private val wares = listOf(
        "item.shark" to 5,
        "item.prayer_potion4" to 20,
        "item.super_restore4" to 30,
        "item.ranging_potion4" to 25,
        "item.super_combat_potion4" to 40,
        "item.saradomin_brew4" to 35,
    )

    init {
        val stock = wares.mapNotNull { (key, price) -> resolveOrNull(key)?.let { ShopItem(it, Int.MAX_VALUE, price) } }
        createShop(SHOP_NAME, ItemCurrency(bm, "Blood Money", "Blood Money"),
            purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }

        spawnNpc(TRADER, 3224, 3216, 0, 0, Direction.WEST) // end-game/PK cluster, south of the hub's east column
        bindTrader(TRADER)
        onCommand("pkshop", description = "Open the PK Rewards (Blood Money) shop") { player.openShop(SHOP_NAME) }

        onPlayerPreDeath {
            val victim = player
            val killer = victim.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerPreDeath
            if (killer === victim || killer is PkBot) return@onPlayerPreDeath // bots/self don't earn

            val reward = BM_BASE + victim.combatLevel * BM_PER_LEVEL
            val added = killer.inventory.add(item = bm, amount = reward, assureFullInsertion = false)
            val leftover = reward - added.completed
            if (leftover > 0) world.spawn(GroundItem(bm, leftover, killer.tile, killer))
            killer.message("<col=990000>Blood money:</col> +${"%,d".format(reward)} for slaying ${victim.username}.")
            logger.info { "PK ${killer.username} killed ${victim.username} (cb ${victim.combatLevel}) -> $reward blood money" }
        }
    }

    /** Bind EVERY vendor option (Talk-to AND Trade) so neither is a dead click on the trader. */
    private fun bindTrader(npc: String) {
        if (!bindVendorOptions(npc) { player.openShop(SHOP_NAME) }) {
            logger.warn { "pk-rewards: '$npc' has no click options; use ::pkshop." }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val SHOP_NAME = "PK Rewards"
        const val TRADER = "npc.emblem_trader"
        const val BM_BASE = 25
        const val BM_PER_LEVEL = 3
    }
}
