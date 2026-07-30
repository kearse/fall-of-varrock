package org.alter.plugins.content.economy.pk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
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
import org.alter.plugins.content.economy.grandexchange.GeCurrencyPrices
import org.alter.plugins.content.economy.grandexchange.currencyBuyShop
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Blood Money — the PK currency and the PvP gear chase** (shop-economy-redesign §3a).
 *
 * Killing another REAL player awards Blood Money scaled by the victim's combat level; it's an
 * inventory item (`item.blood_money`) so it can itself be risked/dropped on death. [PkBot]
 * fake-players pay NO Blood Money in either direction (they're loot + practice, per the wiki) —
 * their worth is the kit in their loot keys, never minted currency.
 *
 * Spent at the **PK Rewards** vendor (the emblem trader), now a full PvP catalogue:
 *  - **Supplies** — food/potions (the consumption loop).
 *  - **Spec weapons** — AGS, claws, DWH, voidwaker... (the captains of Fallen Varrock stay the
 *    cheaper path — shop prices are the pity route, redesign R3).
 *  - **Wilderness sets** — Vesta's (incl. the longsword), Statius's, Morrigan's, Zuriel's: the
 *    rank-gated wildy prestige sets that previously had NO source in the game.
 *  - **Revenant weapons** — craw's/viggora's/thammaron's and their upgrades.
 *
 * All wings sell-only (no buy-back). PvM chase gear deliberately lives elsewhere (the Warlord's
 * Armoury, Boss Tickets) — currency matches playstyle (R1). Prices TUNE.
 */
class PkRewardsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val bm = getRSCM("item.blood_money")

    private data class Ware(val key: String, val price: Int)

    /** PK supplies (the consumption loop). */
    private val supplyWares = listOf(
        Ware("item.shark", 5),
        Ware("item.prayer_potion4", 20),
        Ware("item.super_restore4", 30),
        Ware("item.ranging_potion4", 25),
        Ware("item.super_combat_potion4", 40),
        Ware("item.saradomin_brew4", 35),
    )

    /** Spec weapons — the PKer's chase. Claws/DWH priced above the captains route (R3). */
    private val specWares = listOf(
        Ware("item.voidwaker", 30_000),
        Ware("item.elder_maul", 20_000),
        Ware("item.elder_maul_or", 25_000),
        Ware("item.ancient_godsword", 20_000),
        Ware("item.armadyl_godsword", 15_000),
        Ware("item.dragon_claws_or", 16_000),
        Ware("item.burning_claws", 14_000),
        Ware("item.dragon_claws", 12_000),
        Ware("item.dragon_warhammer", 10_000),
        Ware("item.abyssal_whip", 3_000),
        Ware("item.granite_maul", 1_500),
    )

    /** The wilderness prestige sets — rank-gated in Title.kt but previously sourceless. */
    private val wildySetWares = listOf(
        Ware("item.vestas_longsword", 25_000),
        Ware("item.vestas_chainbody", 15_000),
        Ware("item.vestas_plateskirt", 12_000),
        Ware("item.statiuss_warhammer", 20_000),
        Ware("item.statiuss_full_helm", 10_000),
        Ware("item.statiuss_platebody", 15_000),
        Ware("item.statiuss_platelegs", 12_000),
        Ware("item.morrigans_coif", 8_000),
        Ware("item.morrigans_leather_body", 12_000),
        Ware("item.morrigans_leather_chaps", 10_000),
        Ware("item.zuriels_hood", 8_000),
        Ware("item.zuriels_robe_top", 12_000),
        Ware("item.zuriels_robe_bottom", 10_000),
    )

    /** Revenant weapons + their wilderness upgrades (moved from the Warlord's Armoury). */
    private val revenantWares = listOf(
        Ware("item.craws_bow", 6_000),
        Ware("item.viggoras_chainmace", 6_000),
        Ware("item.thammarons_sceptre", 6_000),
        Ware("item.webweaver_bow", 12_000),
        Ware("item.ursine_chainmace", 12_000),
        Ware("item.accursed_sceptre", 10_000),
    )

    init {
        shopOf(SUPPLIES, supplyWares)
        shopOf(SPEC_WEAPONS, specWares)
        shopOf(WILDY_SETS, wildySetWares)
        shopOf(REVENANT, revenantWares)
        // Buy Blood Money for coins — sets the coin ceiling on the PvP gear this vendor sells.
        currencyBuyShop(BUY_BM, "item.blood_money", GeCurrencyPrices.BLOOD_MONEY)

        spawnNpc(TRADER, 3214, 3218, 0, 0, Direction.SOUTH) // station 2 (Commerce & Crown) 2x2 block, SE tile
        bindTrader(TRADER)
        onCommand("pkshop", description = "Open the PK Rewards (Blood Money) shops") {
            ShopTabs.open(player, traderTabs)
        }

        onPlayerPreDeath {
            val victim = player
            val killer = victim.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerPreDeath
            if (killer === victim || killer is PkBot) return@onPlayerPreDeath // bots/self don't earn
            // Bots don't PAY either (wiki: "loot and practice, not ladder points") — minting
            // currency from farmable bots would flood the Blood-Money gear economy.
            if (victim is PkBot) return@onPlayerPreDeath

            val reward = BM_BASE + victim.combatLevel * BM_PER_LEVEL
            val added = killer.inventory.add(item = bm, amount = reward, assureFullInsertion = false)
            val leftover = reward - added.completed
            if (leftover > 0) world.spawn(GroundItem(bm, leftover, killer.tile, killer))
            killer.message("<col=990000>Blood money:</col> +${"%,d".format(reward)} for slaying ${victim.username}.")
            logger.info { "PK ${killer.username} killed ${victim.username} (cb ${victim.combatLevel}) -> $reward blood money" }
        }
    }

    /** The four Blood-Money wings as one tabbed storefront (see ShopTabs) — no dialogue hop. */
    private val traderTabs = listOf(
        ShopTabs.Tab("Supplies", SUPPLIES, icon = "item.shark"),
        ShopTabs.Tab("Spec weapons", SPEC_WEAPONS, icon = "item.armadyl_godsword"),
        ShopTabs.Tab("Wildy sets", WILDY_SETS, icon = "item.vestas_longsword"),
        ShopTabs.Tab("Revenant", REVENANT, icon = "item.craws_bow"),
        ShopTabs.Tab("Buy Blood Money", BUY_BM, icon = "item.blood_money"),
    )

    private fun shopOf(name: String, wares: List<Ware>) {
        val stock = wares.mapNotNull { (key, price) -> resolveOrNull(key)?.let { ShopItem(it, STOCK, price) } }
        createShop(name, ItemCurrency(bm, "Blood Money", "Blood Money"),
            purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    /** Bind EVERY vendor option (Talk-to AND Trade) straight to the tabbed storefront. */
    private fun bindTrader(npc: String) {
        if (!bindVendorOptions(npc) { ShopTabs.open(player, traderTabs) }) {
            logger.warn { "pk-rewards: '$npc' has no click options; use ::pkshop." }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val SUPPLIES = "PK Rewards"
        const val SPEC_WEAPONS = "PK Rewards - Spec Weapons"
        const val WILDY_SETS = "PK Rewards - Wilderness Sets"
        const val REVENANT = "PK Rewards - Revenant Weapons"
        const val BUY_BM = "PK Rewards - Buy Blood Money"
        const val TRADER = "npc.emblem_trader"
        const val STOCK = 100
        const val BM_BASE = 25
        const val BM_PER_LEVEL = 3
    }
}
