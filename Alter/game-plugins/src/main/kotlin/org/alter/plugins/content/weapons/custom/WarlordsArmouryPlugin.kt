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
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.SupplyDepot
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Warlord's Armoury — the end-game vendor + chase-gear sink. A quartermaster at Lumbridge
 * sells every chase-tier weapon, armour set, accessory, Barrows/Revenant/Crystal/Charged
 * item in the cache (plus our custom gear and cosmetics). Sell-only ([PurchasePolicy.BUY_NONE])
 * so it never buys gear back (no inflation leak).
 *
 * Currency is tiered so the best gear is *earned*, not bought with raw GP:
 *   - **Weapons + Revenant** -> **Blood Money** (the PK item-currency; killing players earns it).
 *   - **Armour + Crystal**   -> **Boss points** (the PvM counter).
 *   - **Barrows / Charged / Accessories** -> **GP** (the affordable mid-tier).
 *
 * Prices are tuned per-currency (Blood Money/points are small grindable numbers; GP is large).
 * Stock is split across wings so each stays under the ~40-slot display cap; the quartermaster
 * opens a category menu (Weapons / Armour / Accessories / More...). An admin `::armoury`
 * command opens the same menu for testing. Missing cache keys are skipped defensively.
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
        const val REVENANT = "Warlord's Armoury - Revenant Weapons"
        const val CRYSTAL = "Warlord's Armoury - Crystal Gear"
        const val CHARGED = "Warlord's Armoury - Charged & Degradable"
    }

    // ============================ stock — Blood Money (PK) ============================
    // Earned ~25 + 3*combat per player kill; priced as a real-but-achievable PK grind.

    private val weaponStock = listOf(
        Ware("item.holy_scythe_of_vitur", 50_000),        // Bloodforged Scythe (lifesteal)
        Ware("item.sanguine_scythe_of_vitur", 50_000),
        Ware("item.scythe_of_vitur", 40_000),
        Ware("item.twisted_bow", 50_000),                 // Plaguebringer Bow (venom)
        Ware("item.tumekens_shadow", 50_000),             // Staff of the Storm (chain)
        Ware("item.holy_sanguinesti_staff", 35_000),
        Ware("item.sanguinesti_staff", 25_000),
        Ware("item.elder_maul", 20_000),                  // Executioner's Maul (spec)
        Ware("item.elder_maul_or", 25_000),
        Ware("item.soulreaper_axe", 35_000),
        Ware("item.voidwaker", 30_000),
        Ware("item.ghrazi_rapier", 22_000),
        Ware("item.zaryte_crossbow", 25_000),
        Ware("item.venator_bow", 25_000),
        Ware("item.dragon_hunter_crossbow", 18_000),
        Ware("item.dragon_hunter_lance", 18_000),
        Ware("item.osmumtens_fang", 15_000),
        Ware("item.harmonised_nightmare_staff", 18_000),
        Ware("item.abyssal_whip", 3_000),
        Ware("item.armadyl_godsword", 15_000),
        Ware("item.ancient_godsword", 20_000),
        Ware("item.keris_partisan", 8_000),
        Ware("item.tonalztics_of_ralos", 18_000),
        Ware("item.burning_claws", 14_000),
        Ware("item.dragon_claws", 12_000),
        Ware("item.dragon_claws_or", 16_000),
        Ware("item.dragon_warhammer", 10_000),
    )

    private val revenantStock = listOf(
        Ware("item.craws_bow", 6_000),
        Ware("item.viggoras_chainmace", 6_000),
        Ware("item.thammarons_sceptre", 6_000),
        Ware("item.webweaver_bow", 12_000),       // craw's bow upgrade
        Ware("item.ursine_chainmace", 12_000),    // viggora's upgrade
        Ware("item.accursed_sceptre", 10_000),    // thammaron's upgrade
    )

    // ============================ stock — Boss points (PvM) ============================

    private val armourStock = listOf(
        Ware("item.torva_full_helm", 1_500),
        Ware("item.torva_platebody", 2_000),
        Ware("item.torva_platelegs", 2_000),
        Ware("item.masori_mask", 1_200),
        Ware("item.masori_body", 1_800),
        Ware("item.masori_chaps", 1_500),
        Ware("item.virtus_mask", 1_200),
        Ware("item.virtus_robe_top", 1_800),
        Ware("item.virtus_robe_bottom", 1_500),
        Ware("item.ancestral_hat", 1_000),
        Ware("item.ancestral_robe_top", 1_500),
        Ware("item.ancestral_robe_bottom", 1_200),
        Ware("item.justiciar_faceguard", 900),
        Ware("item.justiciar_chestguard", 1_200),
        Ware("item.justiciar_legguards", 1_100),
        Ware("item.inquisitors_great_helm", 900),
        Ware("item.inquisitors_hauberk", 1_200),
        Ware("item.inquisitors_plateskirt", 1_100),
        Ware("item.bandos_chestplate", 600),
        Ware("item.bandos_tassets", 800),
        Ware("item.elite_void_top", 400),
        Ware("item.elite_void_robe", 400),
        Ware("item.void_knight_top", 250),
        Ware("item.void_knight_gloves", 250),
        // Warlord's Regalia — prestige cosmetic set (stat-less recolor).
        Ware("item.sanguine_torva_full_helm", 2_500),
        Ware("item.sanguine_torva_platebody", 2_500),
        Ware("item.sanguine_torva_platelegs", 2_500),
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

    // ============================ stock — GP (mid-tier) ============================

    private val accessoryStock = listOf(
        // Amulets
        Ware("item.occult_necklace", 300_000_000),
        Ware("item.amulet_of_torture", 400_000_000),
        Ware("item.necklace_of_anguish", 400_000_000),
        Ware("item.amulet_of_blood_fury", 350_000_000),
        Ware("item.amulet_of_fury", 200_000_000),
        // Boots
        Ware("item.primordial_boots", 300_000_000),
        Ware("item.pegasian_boots", 300_000_000),
        Ware("item.eternal_boots", 300_000_000),
        // Rings
        Ware("item.ultor_ring", 800_000_000),
        Ware("item.magus_ring", 800_000_000),
        Ware("item.bellator_ring", 800_000_000),
        Ware("item.venator_ring", 800_000_000),
        Ware("item.lightbearer", 500_000_000),
        Ware("item.ring_of_suffering", 400_000_000),
        Ware("item.brimstone_ring", 300_000_000),
        Ware("item.berserker_ring_i", 250_000_000),
        // Shields & defenders
        Ware("item.elysian_spirit_shield", 1_500_000_000),
        Ware("item.arcane_spirit_shield", 800_000_000),
        Ware("item.spectral_spirit_shield", 700_000_000),
        Ware("item.dinhs_bulwark", 500_000_000),
        Ware("item.ancient_wyvern_shield", 300_000_000),
        Ware("item.dragonfire_shield", 250_000_000),
        Ware("item.avernic_defender", 700_000_000),
        // Gloves / vambraces
        Ware("item.zaryte_vambraces", 400_000_000),
        Ware("item.ferocious_gloves", 400_000_000),
        Ware("item.barrows_gloves", 200_000_000),
        // Capes
        Ware("item.infernal_cape", 800_000_000),
        Ware("item.infernal_max_cape", 1_200_000_000),
        Ware("item.fire_cape", 100_000_000),
    )

    /** The six Barrows brothers — helm, body, legs, weapon each. */
    private val barrowsStock = listOf(
        Ware("item.dharoks_helm", 120_000_000), Ware("item.dharoks_platebody", 120_000_000),
        Ware("item.dharoks_platelegs", 120_000_000), Ware("item.dharoks_greataxe", 180_000_000),
        Ware("item.ahrims_hood", 120_000_000), Ware("item.ahrims_robetop", 120_000_000),
        Ware("item.ahrims_robeskirt", 120_000_000), Ware("item.ahrims_staff", 180_000_000),
        Ware("item.karils_coif", 120_000_000), Ware("item.karils_leathertop", 120_000_000),
        Ware("item.karils_leatherskirt", 120_000_000), Ware("item.karils_crossbow", 180_000_000),
        Ware("item.guthans_helm", 120_000_000), Ware("item.guthans_platebody", 120_000_000),
        Ware("item.guthans_chainskirt", 120_000_000), Ware("item.guthans_warspear", 180_000_000),
        Ware("item.torags_helm", 120_000_000), Ware("item.torags_platebody", 120_000_000),
        Ware("item.torags_platelegs", 120_000_000), Ware("item.torags_hammers", 180_000_000),
        Ware("item.veracs_helm", 120_000_000), Ware("item.veracs_brassard", 120_000_000),
        Ware("item.veracs_plateskirt", 120_000_000), Ware("item.veracs_flail", 180_000_000),
    )

    /** Charged / degradable gear — sold at the base id; players charge them to use. */
    private val chargedStock = listOf(
        Ware("item.trident_of_the_swamp", 250_000_000),
        Ware("item.trident_of_the_seas", 200_000_000),
        Ware("item.toxic_staff_of_the_dead", 300_000_000),
        Ware("item.toxic_blowpipe", 500_000_000),
        Ware("item.abyssal_tentacle", 250_000_000),
        Ware("item.dragon_hunter_wand", 600_000_000),
        Ware("item.scorching_bow", 500_000_000),
        Ware("item.serpentine_helm", 250_000_000),
        Ware("item.magma_helm", 300_000_000),
        Ware("item.tanzanite_helm", 300_000_000),
    )

    // ----------------------------------- wiring -----------------------------------

    init {
        // Chase wings: earned currencies.
        shopWith(WEAPONS, bloodMoney(), weaponStock)
        shopWith(REVENANT, bloodMoney(), revenantStock)
        shopWith(ARMOUR, PointsCurrency(PointKind.BOSS), armourStock)
        shopWith(CRYSTAL, PointsCurrency(PointKind.BOSS), crystalStock)
        // Mid-tier wings: GP.
        shopWith(ACCESSORIES, CoinCurrency(), accessoryStock)
        shopWith(BARROWS, CoinCurrency(), barrowsStock)
        shopWith(CHARGED, CoinCurrency(), chargedStock)

        // Quartermaster vendor — end-game cluster just south of the shop hub's west column.
        // Gear OUT (the armoury), supplies IN (the §3B war-supply sink).
        spawnNpc("npc.quartermaster", 3219, 3216, 0, 0, Direction.EAST)
        bindVendor("npc.quartermaster")

        onCommand("armoury", Privilege.ADMIN_POWER, description = "Open the Warlord's Armoury") {
            player.queue { quartermasterMenu(player) }
        }
    }

    // ----------------------------- §3B war-supply sink -----------------------------

    /** Top-level Quartermaster menu: take supplies in, or sell chase gear out. */
    private suspend fun QueueTask.quartermasterMenu(player: Player) {
        // Intro-quest: the recruit's SUPPLY drop-off (the bronze dagger they forged in The Mire) runs
        // before the normal menu — the teaching moment for the gather→process→supply loop.
        if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
            recruitSupplyHandIn(player)
            return
        }
        when (options(player, "Hand in war supplies", "Browse the armoury", "Nevermind", title = "Quartermaster")) {
            1 -> depositMenu(player)
            2 -> armouryMenu(player)
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

    /** Choose a category of finished goods to hand over to the war effort. */
    private suspend fun QueueTask.depositMenu(player: Player) {
        chatNpc(player, "An army marches on its supplies, ${player.address}. Bring me cooked food, finished potions, smithed bars — anything to keep the front fed and armed. I'll log it as War Effort.", npc = quartermasterId, title = "Quartermaster")
        when (options(player, "Cooked food", "Potions", "Smithed bars", "Everything I've got", "Nothing", title = "Hand in supplies")) {
            1 -> doDeposit(player, SupplyDepot.FOOD, "cooked food")
            2 -> doDeposit(player, SupplyDepot.POTIONS, "potions")
            3 -> doDeposit(player, SupplyDepot.BARS, "smithed bars")
            4 -> doDeposit(player, SupplyDepot.ALL, "supplies")
        }
    }

    /** Run the deposit and give the player chat feedback on what the war took. */
    private suspend fun QueueTask.doDeposit(player: Player, wares: Map<String, Int>, label: String) {
        val (items, we) = SupplyDepot.deposit(player, wares)
        if (items <= 0) {
            chatNpc(player, "You've no $label on you, ${player.address}. Come back when your packs are full.", npc = quartermasterId, title = "Quartermaster")
            return
        }
        chatNpc(player, "$items $label for the front — that's <col=801700>$we War Effort</col>. The realm thanks you, ${player.address}.", npc = quartermasterId, title = "Quartermaster")
    }

    private val quartermasterId = runCatching { getRSCM("npc.quartermaster") }.getOrDefault(-1)

    private fun bloodMoney(): ShopCurrency =
        ItemCurrency(getRSCM("item.blood_money"), "Blood Money", "Blood Money")

    private fun shopWith(name: String, currency: ShopCurrency, wares: List<Ware>) {
        val stock = wares.mapNotNull { w -> resolveOrNull(w.key)?.let { ShopItem(it, STOCK, w.price) } }
        createShop(name, currency, purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    private suspend fun QueueTask.armouryMenu(player: Player) {
        when (options(player, "Weapons", "Armour", "Accessories", "More gear...", "Nevermind", title = "Warlord's Armoury")) {
            1 -> player.openShop(WEAPONS)
            2 -> player.openShop(ARMOUR)
            3 -> player.openShop(ACCESSORIES)
            4 -> moreMenu(player)
        }
    }

    private suspend fun QueueTask.moreMenu(player: Player) {
        when (options(player, "Barrows sets", "Revenant weapons", "Crystal gear", "Charged & degradable", "Nevermind", title = "More Gear")) {
            1 -> player.openShop(BARROWS)
            2 -> player.openShop(REVENANT)
            3 -> player.openShop(CRYSTAL)
            4 -> player.openShop(CHARGED)
        }
    }

    /**
     * Bind EVERY vendor option the quartermaster has (Talk-to AND Trade) to the category menu, so
     * neither is a dead click. Binding a missing option throws, so [bindVendorOptions] resolves
     * defensively from the cache def and skips options the npc doesn't carry.
     */
    private fun bindVendor(npc: String) {
        if (!bindVendorOptions(npc) { player.queue { quartermasterMenu(player) } }) {
            logger.warn { "Warlord's Armoury: '$npc' has no click options; use ::armoury to reach it." }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }
}
