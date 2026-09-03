package org.alter.plugins.content.weapons.custom

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.SupplyDepotShop
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorTalkAndTrade
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The Quartermaster's desk: the **war-supply hand-in** ([SupplyDepotShop], the §3B item sink) and the
 * coins-only **Barrows pity wing** — the mid-game gp sink (shop-economy-redesign §3d), priced far
 * above the crypt run so the minigame stays the smart path.
 *
 * The Boss-Ticket catalogue that used to hang here (megarares, GWD bases, Justiciar/Inquisitor/Void,
 * crystal gear, charged weapons, BIS accessories, the 3rd age relics wing) was **retired in 2026-09**
 * with the Boss Ticket itself (design doc 04 §13: a universal PvM token bypasses recognisable boss
 * progression; bosses already produce drops). Those items are boss drops and war rewards only now,
 * and they stay NPC-unsellable — see [org.alter.plugins.content.economy.ChaseGearGuardPlugin].
 */
class WarlordsArmouryPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Ware(val key: String, val price: Int)

    private companion object {
        const val STOCK = 100 // plenty so the display never shows "out of stock"
        const val BARROWS = "Warlord's Armoury - Barrows"
    }

    /**
     * The six Barrows brothers — helm, body, legs, weapon each. Stays GP on purpose:
     * the mid-game coin sink (redesign §3d), priced above the minigame's expected grind
     * so the chest stays the smart path. TUNE against the live crypt run (PR #315).
     */
    private val barrowsStock = listOf(
        Ware("item.dharoks_helm", 12_000_000), Ware("item.dharoks_platebody", 12_000_000),
        Ware("item.dharoks_platelegs", 12_000_000), Ware("item.dharoks_greataxe", 20_000_000),
        Ware("item.ahrims_hood", 12_000_000), Ware("item.ahrims_robetop", 12_000_000),
        Ware("item.ahrims_robeskirt", 12_000_000), Ware("item.ahrims_staff", 20_000_000),
        Ware("item.karils_coif", 12_000_000), Ware("item.karils_leathertop", 12_000_000),
        Ware("item.karils_leatherskirt", 12_000_000), Ware("item.karils_crossbow", 20_000_000),
        Ware("item.guthans_helm", 12_000_000), Ware("item.guthans_platebody", 12_000_000),
        Ware("item.guthans_chainskirt", 12_000_000), Ware("item.guthans_warspear", 20_000_000),
        Ware("item.torags_helm", 12_000_000), Ware("item.torags_platebody", 12_000_000),
        Ware("item.torags_platelegs", 12_000_000), Ware("item.torags_hammers", 20_000_000),
        Ware("item.veracs_helm", 12_000_000), Ware("item.veracs_brassard", 12_000_000),
        Ware("item.veracs_plateskirt", 12_000_000), Ware("item.veracs_flail", 20_000_000),
    )

    // ----------------------------------- wiring -----------------------------------

    init {
        // The mid-game coin sink — sell-only, no buy-back.
        val stock = barrowsStock.mapNotNull { w -> resolveOrNull(w.key)?.let { ShopItem(it, STOCK, sellPrice = w.price) } }
        createShop(BARROWS, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }

        // Quartermaster vendor — inside the GE hub's desk ring, east of the pillar, facing his
        // east desks. Barrows OUT, supplies IN (the §3B war-supply sink).
        spawnNpc("npc.quartermaster", 3223, 3211, 0, 0, Direction.EAST)
        bindVendor("npc.quartermaster")

        onCommand("armoury", Privilege.ADMIN_POWER, description = "Open the Warlord's Armoury (Barrows wing)") {
            openArmoury(player)
        }
    }

    /** The armoury storefront (see ShopTabs) — Trade lands here directly. The recruit-trials SUPPLY
     *  hand-in still intercepts first so the teaching beat can't be skipped by clicking Trade
     *  instead of Talk-to. */
    private fun openArmoury(player: Player) {
        if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
            player.queue { recruitSupplyHandIn(player) }
            return
        }
        ShopTabs.open(player, ARMOURY_TABS)
    }

    private val ARMOURY_TABS = listOf(
        ShopTabs.Tab("Barrows", BARROWS, icon = "item.dharoks_greataxe"),
    )

    // ----------------------------- §3B war-supply sink -----------------------------

    /** Top-level Quartermaster menu: take supplies in, or browse the Barrows wing. */
    private suspend fun QueueTask.quartermasterMenu(player: Player) {
        // Intro-quest: the recruit's SUPPLY drop-off (the bronze dagger they forged in The Mire) runs
        // before the normal menu — the teaching moment for the gather→process→supply loop.
        if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
            recruitSupplyHandIn(player)
            return
        }
        when (options(player, "Hand in war supplies", "Browse the armoury", "Nevermind", title = "Quartermaster")) {
            // The hand-in is the Supply Depot storefront: an ordinary tabbed shop window, sell-only —
            // the accepted catalogue priced in the War Effort it pays (see SupplyDepotPlugin).
            1 -> SupplyDepotShop.open(player)
            2 -> openArmoury(player)
        }
    }

    /** Intro-quest hand-in: the recruit gives the Quartermaster the bronze dagger they forged. Consumes
     *  it + logs War Effort (via [RecruitTrials.onSupplyDelivered]) and points them back to Vannaka. */
    private suspend fun QueueTask.recruitSupplyHandIn(player: Player) {
        if (RecruitTrials.onSupplyDelivered(player)) {
            chatNpc(player, "A finished blade for the war, not just raw rock — THIS is how you supply an army. I've logged it as War Effort.", npc = quartermasterId, title = "Quartermaster")
            chatNpc(player, "Now report back to Vannaka, ${player.address} — he'll square you up. Follow your marker.", npc = quartermasterId, title = "Quartermaster")
        } else {
            chatNpc(player, "You've nothing finished for me yet, ${player.address}. Forge a bronze dagger at the anvil in The Mire and bring it back — follow your marker.", npc = quartermasterId, title = "Quartermaster")
        }
    }

    private val quartermasterId = runCatching { getRSCM("npc.quartermaster") }.getOrDefault(-1)

    /**
     * Talk-to keeps the war-supply dialogue (hand-ins + the recruit hand-in beat); Trade — and any
     * other vendor option — opens the armoury storefront directly. Options are resolved
     * defensively from the cache def so a missing option can't crash plugin init.
     */
    private fun bindVendor(npc: String) {
        val bound = bindVendorTalkAndTrade(
            npc,
            talk = { player.queue { quartermasterMenu(player) } },
            trade = { openArmoury(player) },
        )
        if (!bound) {
            logger.warn { "Warlord's Armoury: '$npc' has no click options; use ::armoury to reach it." }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }
}
