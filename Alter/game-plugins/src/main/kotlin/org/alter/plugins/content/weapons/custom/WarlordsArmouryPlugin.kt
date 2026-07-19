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
import org.alter.game.model.shop.ShopCurrency
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.SupplyMenu
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorTalkAndTrade
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Warlord's Armoury — the **PvM** end-game vendor + war-supply sink (shop-economy-redesign
 * §3b). The Quartermaster sells the PvM chase catalogue for **Boss Tickets** (the tradeable
 * ticket item every boss and PvM minigame pays — NOT the vestigial boss-point counter this
 * shop once charged), plus the Barrows pity wing for gp (the mid-game coin sink). Every wing is
 * sell-only ([PurchasePolicy.BUY_NONE]) EXCEPT the Relics (3rd age) wing, which is a full
 * buy-and-sell storefront ([PurchasePolicy.BUY_STOCK]) — you can sell pieces straight back through
 * the shop window like an OSRS shop, no rank gate (see the Relics stock below).
 *
 * What it deliberately does NOT sell (redesign rules R1/R2/R4):
 *  - **PvP gear** (spec weapons, wilderness sets, revenant weapons) — that's the PK Rewards
 *    vendor's Blood-Money catalogue (`economy/pk/PkRewardsPlugin`).
 *  - **War-forged BIS** (Torva/Masori/Ancestral) — Royal Smith exclusive; Virtus drops from
 *    Nex (EliteBosses) like Torva does; the sanguine regalia moved to the Prestige shop.
 *  - **Untradeable prestige** (fire/infernal capes) and **Corp's sigil shields** — earned only.
 *
 * Megarares (scythe/tbow/shadow) are priced as CAREERS — the deterministic pity path until
 * raids ship gear. The Relics wing (3rd age) is now the exception: the war is the real source
 * (battlefield drops + a leadership gift — see [org.alter.plugins.content.war.ThirdAge]); the shelf
 * survives only as a tripled pity-path, and any player can sell pieces back through the storefront.
 * All prices TUNE.
 */
class WarlordsArmouryPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Ware(val key: String, val price: Int)

    private companion object {
        const val STOCK = 100 // plenty so the display never shows "out of stock"
        const val WEAPONS = "Warlord's Armoury - Weapons"
        const val ARMOUR = "Warlord's Armoury - Armour"
        const val ACCESSORIES = "Warlord's Armoury - Accessories"
        const val BARROWS = "Warlord's Armoury - Barrows"
        const val CRYSTAL = "Warlord's Armoury - Crystal Gear"
        const val CHARGED = "Warlord's Armoury - Charged & Degradable"
        const val RELICS = "Warlord's Armoury - Relics"

        /** Relics shelf markup over the old career base: buy = base × 3, buy-back = base (× 1). */
        const val RELIC_BUY_MULT = 3
    }

    // ======================== stock — Boss Tickets (PvM) ========================
    // Earn rates for scale: Zulrah 40/kill, Inferno 400/clear, Fight Cave 150/clear,
    // GWD 35/kill. Megarares are ~300 Zulrah kills / ~30 Inferno clears — a career.

    private val weaponStock = listOf(
        // Megarares — the pity path until raids drop gear (career prices).
        Ware("item.holy_scythe_of_vitur", 15_000),
        Ware("item.sanguine_scythe_of_vitur", 15_000),
        Ware("item.scythe_of_vitur", 12_000),
        Ware("item.twisted_bow", 12_000),
        Ware("item.tumekens_shadow", 12_000),
        // High PvM weapons.
        Ware("item.holy_sanguinesti_staff", 8_000),
        Ware("item.sanguinesti_staff", 6_000),
        Ware("item.soulreaper_axe", 8_000),
        Ware("item.zaryte_crossbow", 6_000),
        Ware("item.venator_bow", 5_000),
        Ware("item.harmonised_nightmare_staff", 4_500),
        Ware("item.ghrazi_rapier", 4_000),
        Ware("item.tonalztics_of_ralos", 4_000),
        Ware("item.dragon_hunter_crossbow", 3_500),
        Ware("item.dragon_hunter_lance", 3_500),
        Ware("item.osmumtens_fang", 2_500),
        Ware("item.keris_partisan", 1_500),
    )

    /** GWD bases (they feed the Royal Smith's forge — R3) + mid PvM armour. */
    private val armourStock = listOf(
        Ware("item.bandos_chestplate", 600),
        Ware("item.bandos_tassets", 800),
        Ware("item.armadyl_chestplate", 600),
        Ware("item.armadyl_chainskirt", 800),
        Ware("item.justiciar_faceguard", 900),
        Ware("item.justiciar_chestguard", 1_200),
        Ware("item.justiciar_legguards", 1_100),
        Ware("item.inquisitors_great_helm", 900),
        Ware("item.inquisitors_hauberk", 1_200),
        Ware("item.inquisitors_plateskirt", 1_100),
        Ware("item.elite_void_top", 400),
        Ware("item.elite_void_robe", 400),
        Ware("item.void_knight_top", 250),
        Ware("item.void_knight_gloves", 250),
    )

    private val crystalStock = listOf(
        Ware("item.crystal_bow", 600),
        Ware("item.crystal_halberd", 600),
        Ware("item.crystal_shield", 500),
        Ware("item.crystal_helm", 800),
        Ware("item.crystal_body", 1_000),
        Ware("item.crystal_legs", 900),
        Ware("item.blade_of_saeldor", 1_500),
    )

    /** BIS accessories — moved off raw GP (R4). Ely/Arcane/Spectral stay Corp-only. */
    private val accessoryStock = listOf(
        // Amulets & zenyte jewellery
        Ware("item.occult_necklace", 800),
        Ware("item.amulet_of_torture", 1_200),
        Ware("item.necklace_of_anguish", 1_200),
        Ware("item.tormented_bracelet", 1_200),
        Ware("item.amulet_of_blood_fury", 1_000),
        Ware("item.amulet_of_fury", 400),
        // Boots
        Ware("item.primordial_boots", 900),
        Ware("item.pegasian_boots", 900),
        Ware("item.eternal_boots", 900),
        // Rings
        Ware("item.ultor_ring", 2_500),
        Ware("item.magus_ring", 2_500),
        Ware("item.bellator_ring", 2_500),
        Ware("item.venator_ring", 2_500),
        Ware("item.lightbearer", 1_200),
        Ware("item.ring_of_suffering", 1_000),
        Ware("item.brimstone_ring", 600),
        Ware("item.berserker_ring_i", 500),
        // Shields & defenders (sigil shields deliberately absent — Corp's table only)
        Ware("item.dinhs_bulwark", 1_200),
        Ware("item.ancient_wyvern_shield", 600),
        Ware("item.dragonfire_shield", 500),
        Ware("item.avernic_defender", 2_000),
        // Gloves / vambraces
        Ware("item.zaryte_vambraces", 1_200),
        Ware("item.ferocious_gloves", 1_200),
        Ware("item.barrows_gloves", 300),
    )

    /**
     * The six Barrows brothers — helm, body, legs, weapon each. Stays GP on purpose:
     * the mid-game coin sink (redesign §3d), priced above the minigame's expected grind
     * so the chest stays the smart path.
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

    /**
     * Relics — 3rd age, the realm's antique prestige line. The REAL source is now the war: a piece
     * drops from enemies on a live campaign/conquest battlefield, and the victorious Minister/King may
     * be gifted one ([org.alter.plugins.content.war.ThirdAge]). This shelf survives only as a slow
     * pity-path, priced at 3× the old career prices so buying a set is the most conspicuous flex on the
     * realm — the drop is the way you're *meant* to get it. Any player can also sell pieces back through
     * the storefront (the wing is [PurchasePolicy.BUY_STOCK]) for Boss Tickets at a third of shelf value,
     * deliberately below what a duplicate fetches in trade. Prices are the pre-triple BASE;
     * [RELIC_BUY_MULT] scales the shelf (sell) price at build time, and the same divisor sets each
     * piece's buy-back (see the [RELICS] shop wiring). Druidic is the apex (OSRS-faithful).
     */
    private val relicStock = listOf(
        // Melee
        Ware("item._3rd_age_full_helmet", 6_000),
        Ware("item._3rd_age_platebody", 8_000),
        Ware("item._3rd_age_platelegs", 7_000),
        Ware("item._3rd_age_kiteshield", 6_000),
        // Range
        Ware("item._3rd_age_range_coif", 5_000),
        Ware("item._3rd_age_range_top", 7_000),
        Ware("item._3rd_age_range_legs", 6_000),
        Ware("item._3rd_age_vambraces", 4_000),
        // Mage
        Ware("item._3rd_age_mage_hat", 5_000),
        Ware("item._3rd_age_robe_top", 7_000),
        Ware("item._3rd_age_robe", 6_000),
        Ware("item._3rd_age_amulet", 4_000),
        // Weapons & cloak
        Ware("item._3rd_age_longsword", 10_000),
        Ware("item._3rd_age_bow", 10_000),
        Ware("item._3rd_age_wand", 10_000),
        Ware("item._3rd_age_cloak", 8_000),
        // Druidic — the apex flex
        Ware("item._3rd_age_druidic_robe_top", 12_000),
        Ware("item._3rd_age_druidic_robe_bottoms", 12_000),
        Ware("item._3rd_age_druidic_staff", 12_000),
        Ware("item._3rd_age_druidic_cloak", 12_000),
    ).map { Ware(it.key, it.price * RELIC_BUY_MULT) } // shelf price = 3× the base careers

    /** Charged / degradable gear — sold at the base id; players charge them to use. */
    private val chargedStock = listOf(
        Ware("item.trident_of_the_seas", 600),
        Ware("item.trident_of_the_swamp", 800),
        Ware("item.toxic_staff_of_the_dead", 1_000),
        Ware("item.toxic_blowpipe", 1_800),
        Ware("item.abyssal_tentacle", 900),
        Ware("item.dragon_hunter_wand", 2_000),
        Ware("item.scorching_bow", 1_800),
        Ware("item.serpentine_helm", 800),
        Ware("item.magma_helm", 900),
        Ware("item.tanzanite_helm", 900),
    )

    // ----------------------------------- wiring -----------------------------------

    init {
        // PvM wings: Boss Tickets (the ITEM — the old boss-point counter was vestigial
        // and nothing fills it; charging it made these wings unbuyable).
        shopWith(WEAPONS, bossTickets(), weaponStock)
        shopWith(ARMOUR, bossTickets(), armourStock)
        shopWith(CRYSTAL, bossTickets(), crystalStock)
        shopWith(ACCESSORIES, bossTickets(), accessoryStock)
        shopWith(CHARGED, bossTickets(), chargedStock)
        // Relics is the one buy-AND-sell wing: buy 3rd age with Boss Tickets, or sell pieces straight
        // back through the shop window (BUY_STOCK) for 1/3 shelf — an OSRS-style storefront, no rank gate.
        shopWith(RELICS, bossTickets(), relicStock, PurchasePolicy.BUY_STOCK) { (it / RELIC_BUY_MULT).coerceAtLeast(1) }
        // The mid-game coin sink.
        shopWith(BARROWS, CoinCurrency(), barrowsStock)

        // Quartermaster vendor — end-game cluster just south of the shop hub's west column.
        // Gear OUT (the armoury), supplies IN (the §3B war-supply sink).
        spawnNpc("npc.quartermaster", 3219, 3216, 0, 0, Direction.EAST)
        bindVendor("npc.quartermaster")

        onCommand("armoury", Privilege.ADMIN_POWER, description = "Open the Warlord's Armoury") {
            openArmoury(player)
        }
    }

    /** The armoury as a TABBED storefront (see ShopTabs) — Trade lands here directly. The
     *  recruit-trials SUPPLY hand-in still intercepts first so the teaching beat can't be skipped
     *  by clicking Trade instead of Talk-to. */
    private fun openArmoury(player: Player) {
        if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
            player.queue { recruitSupplyHandIn(player) }
            return
        }
        ShopTabs.open(player, ARMOURY_TABS)
    }

    private val ARMOURY_TABS = listOf(
        ShopTabs.Tab("Weapons", WEAPONS, icon = "item.ghrazi_rapier"),
        ShopTabs.Tab("Armour", ARMOUR, icon = "item.bandos_chestplate"),
        ShopTabs.Tab("Accessories", ACCESSORIES, icon = "item.berserker_ring"),
        ShopTabs.Tab("Barrows", BARROWS, icon = "item.dharoks_greataxe"),
        ShopTabs.Tab("Crystal", CRYSTAL, icon = "item.crystal_body"),
        ShopTabs.Tab("Charged", CHARGED, icon = "item.toxic_blowpipe"),
        ShopTabs.Tab("Relics", RELICS, icon = "item._3rd_age_platebody"),
    )

    // ----------------------------- §3B war-supply sink -----------------------------

    /** Top-level Quartermaster menu: take supplies in, or sell chase gear out. */
    private suspend fun QueueTask.quartermasterMenu(player: Player) {
        // Intro-quest: the recruit's SUPPLY drop-off (the bronze dagger they forged in The Mire) runs
        // before the normal menu — the teaching moment for the gather→process→supply loop.
        if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
            recruitSupplyHandIn(player)
            return
        }
        // Selling 3rd age back is done straight through the Relics storefront now (buy-and-sell, no rank
        // gate) — Talk-to stays focused on the war-supply hand-in and browsing the armoury.
        when (options(player, "Hand in war supplies", "Browse the armoury", "Nevermind", title = "Quartermaster")) {
            // The hand-in is the client-drawn Supply Depot window (lofsupply): the full accepted
            // catalogue with WE prices, per-item/per-category selling and the Supply Drive banner.
            1 -> SupplyMenu.open(player)
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

    private fun bossTickets(): ShopCurrency =
        ItemCurrency(getRSCM("item.boss_ticket"), "Boss Ticket", "Boss Tickets")

    /**
     * Build a shop wing. Defaults to sell-only ([PurchasePolicy.BUY_NONE]); pass a [purchasePolicy]
     * that buys (e.g. [PurchasePolicy.BUY_STOCK]) plus a [buyBackPrice] to make it buy-and-sell — the
     * engine ([org.alter.plugins.content.mechanics.shops.ItemCurrency.buyFromPlayer]) then pays the
     * player in this shop's currency at each item's buy-back price. [buyBackPrice] maps a ware's shelf
     * (sell) price to its buy-back; null leaves the item non-sellable even under a buying policy.
     */
    private fun shopWith(
        name: String,
        currency: ShopCurrency,
        wares: List<Ware>,
        purchasePolicy: PurchasePolicy = PurchasePolicy.BUY_NONE,
        buyBackPrice: ((Int) -> Int)? = null,
    ) {
        val stock = wares.mapNotNull { w ->
            resolveOrNull(w.key)?.let { ShopItem(it, STOCK, sellPrice = w.price, buyPrice = buyBackPrice?.invoke(w.price)) }
        }
        createShop(name, currency, purchasePolicy = purchasePolicy, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    /**
     * Talk-to keeps the war-supply dialogue (hand-ins + the recruit hand-in beat); Trade — and any
     * other vendor option — opens the tabbed armoury storefront directly. Options are resolved
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
