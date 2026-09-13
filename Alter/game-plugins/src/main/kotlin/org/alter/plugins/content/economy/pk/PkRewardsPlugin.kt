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
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.economy.grandexchange.GeCurrencyPrices
import org.alter.plugins.content.economy.grandexchange.currencyBuyShop
import org.alter.plugins.content.mechanics.shops.ItemCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Blood Money — the PK currency and the PvP gear chase** (shop-economy-redesign §3a).
 *
 * Killing another REAL player awards Blood Money scaled by the victim's combat level; it's an
 * inventory item (`item.blood_money`) so it can itself be risked/dropped on death. [PkBot]
 * fake-players are paid on a SEPARATE path: `bots/RogueBounty` pays the killer half of
 * [bloodMoneyFor] (named ladder knights double) from `BotCombatPlugin`'s death hook, outside
 * [PkKillGuard]. This hook's `BOT_VICTIM` denial is what keeps a bot kill from paying twice; bots
 * never EARN Blood Money as killers.
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

    /**
     * Spec weapons — the PKer's chase. Claws/DWH priced above the captains route (R3).
     *
     * **Repriced ×3 on 2026-09-13** (operator decision; community report "blood money so easy to
     * make now, will prices be updated?"). The 2026-09-12 bot-bounty change is what moved: a slain
     * Rogue Knight now pays [org.alter.plugins.content.bots.RogueBounty] with no cap and no guard
     * (named knights double), so an elite deep-wilderness camp yields ~200 BM a kill — on the order
     * of 15–25k BM an hour of sustained farming, where before a bot paid in gear rather than
     * currency. At the old shelf that bought an AGS in well under an hour.
     *
     * A FLAT ×3 across all three gear wings, deliberately: the relative pricing between wings was
     * already tuned (AGS over claws over DWH; the captains route stays the cheaper path), so a
     * multiplier preserves that tuning and is trivial to revert or re-scale if kill data says
     * otherwise. Target grind at the new numbers: whip ~30 min, AGS/claws 2–3 h, voidwaker and the
     * Vesta's longsword 4–6 h. Supplies are NOT repriced — they're the consumption loop that keeps
     * Blood Money flowing back out, and taxing food punishes the fighting, not the farming.
     */
    private val specWares = listOf(
        Ware("item.voidwaker", 90_000),
        Ware("item.elder_maul", 60_000),
        Ware("item.elder_maul_or", 75_000),
        Ware("item.ancient_godsword", 60_000),
        Ware("item.armadyl_godsword", 45_000),
        Ware("item.dragon_claws_or", 48_000),
        Ware("item.burning_claws", 42_000),
        Ware("item.dragon_claws", 36_000),
        Ware("item.dragon_warhammer", 30_000),
        Ware("item.abyssal_whip", 9_000),
        Ware("item.granite_maul", 4_500),
    )

    /** The wilderness prestige sets — rank-gated in Title.kt but previously sourceless. (×3, see [specWares].) */
    private val wildySetWares = listOf(
        Ware("item.vestas_longsword", 75_000),
        Ware("item.vestas_chainbody", 45_000),
        Ware("item.vestas_plateskirt", 36_000),
        Ware("item.statiuss_warhammer", 60_000),
        Ware("item.statiuss_full_helm", 30_000),
        Ware("item.statiuss_platebody", 45_000),
        Ware("item.statiuss_platelegs", 36_000),
        Ware("item.morrigans_coif", 24_000),
        Ware("item.morrigans_leather_body", 36_000),
        Ware("item.morrigans_leather_chaps", 30_000),
        Ware("item.zuriels_hood", 24_000),
        Ware("item.zuriels_robe_top", 36_000),
        Ware("item.zuriels_robe_bottom", 30_000),
    )

    /** Revenant weapons + their wilderness upgrades (moved from the Warlord's Armoury). (×3, see [specWares].) */
    private val revenantWares = listOf(
        Ware("item.craws_bow", 18_000),
        Ware("item.viggoras_chainmace", 18_000),
        Ware("item.thammarons_sceptre", 18_000),
        Ware("item.webweaver_bow", 36_000),
        Ware("item.ursine_chainmace", 36_000),
        Ware("item.accursed_sceptre", 30_000),
    )

    /**
     * **Untradeables** — the account-bound PvP staples, added 2026-09-13 alongside the community
     * suggestion that PK bots stop dropping untradeables ([org.alter.plugins.content.bots.PkLootPools]).
     *
     * Each of these is a minigame or quest reward in OSRS whose source does NOT exist on this
     * server — defenders are the Warriors' Guild, the fighter torso is Barbarian Assault, barrows
     * gloves are Recipe for Disaster — so pulling them out of the bot pools without a replacement
     * would have made them unobtainable outright. They sit here instead: bought at a KNOWN Blood
     * Money price, which is the point of the suggestion (a predictable earn, not a random flood off
     * a respawning bot). Void is deliberately absent — Pest Control already sells it, and that
     * shelf stays the only source.
     *
     * Priced on the same scale as the rest of the shelf: the defender ladder tops out below a spec
     * weapon, since a defender is a slot-filler rather than a fight-winner. The **avernic defender
     * is NOT sold** — its tradeable hilt drops from elite bots and combines with the dragon
     * defender bought here, so the best-in-slot defender stays a chase with a shop floor under it.
     */
    private val untradeableWares = listOf(
        Ware("item.barrows_gloves", 12_000),
        Ware("item.fighter_torso", 10_000),
        Ware("item.dragon_defender", 9_000),
        Ware("item.rune_defender", 3_000),
        Ware("item.adamant_defender", 1_200),
        Ware("item.mithril_defender", 600),
        Ware("item.black_defender", 400),
        Ware("item.steel_defender", 250),
        Ware("item.iron_defender", 150),
        Ware("item.bronze_defender", 100),
    )

    init {
        shopOf(SUPPLIES, supplyWares)
        shopOf(SPEC_WEAPONS, specWares)
        shopOf(WILDY_SETS, wildySetWares)
        shopOf(REVENANT, revenantWares)
        shopOf(UNTRADEABLES, untradeableWares)
        // PvP gear sold for Blood Money may never be NPC-converted to gp (alch / Trading Post / General
        // Store): the shelf is the pity route, the player market is where it changes hands. Supplies
        // stay vendorable. (2026-09 arbitrage audit: this vendor never registered with the guard.)
        SpecialShopGuard.register(
            (specWares + wildySetWares + revenantWares + untradeableWares).mapNotNull { resolveOrNull(it.key) },
        )
        // (The "Buy Blood Money" coin tab was removed 2026-09-02 at the operator's request:
        // blood money is earned from kills, not bought.)

        // GE hub desk ring, east column — one tile south of the Quartermaster, facing his east
        // desks. Shown as "PK Shop" (store-type name, like the shop hub's vendors) via
        // extended-info — no cache edit; re-applied on respawn by the npc-spawn hook.
        spawnNpc(TRADER, 3223, 3210, 0, 0, Direction.EAST)
        onNpcSpawn(TRADER) { WarNpcNames.rename(npc, "PK Shop") }
        bindTrader(TRADER)
        onCommand("pkshop", description = "Open the PK Rewards (Blood Money) shops") {
            ShopTabs.open(player, traderTabs)
        }

        onPlayerPreDeath {
            val victim = player
            // One legitimacy verdict per death ([PkKillGuard]): self/bot kills, safe-zone deaths,
            // same-address alts, repeat victims, daily caps, no-risk and fresh-account victims all
            // pay nothing HERE. A slain bot is paid by `bots/RogueBounty` instead (half rate, no
            // guard, no cap — operator decision 2026-09-12); the BOT_VICTIM denial below is what
            // stops that kill from paying a second time through this hook.
            val verdict = PkKillGuard.verdictFor(world, victim) ?: return@onPlayerPreDeath
            val killer = victim.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerPreDeath
            if (!verdict.ok) {
                if (verdict.rule.audited()) PkKillGuard.audit(killer, victim, verdict, reward = 0)
                return@onPlayerPreDeath
            }

            val reward = bloodMoneyFor(victim.combatLevel)
            val added = killer.inventory.add(item = bm, amount = reward, assureFullInsertion = false)
            val leftover = reward - added.completed
            if (leftover > 0) world.spawn(GroundItem(bm, leftover, killer.tile, killer))
            killer.message("<col=990000>Blood money:</col> +${"%,d".format(reward)} for slaying ${victim.username}.")
            PkKillGuard.audit(killer, victim, verdict, reward)
        }
    }

    /** The five Blood-Money wings as one tabbed storefront (see ShopTabs) — no dialogue hop. */
    private val traderTabs = listOf(
        ShopTabs.Tab("Supplies", SUPPLIES, icon = "item.shark"),
        ShopTabs.Tab("Spec weapons", SPEC_WEAPONS, icon = "item.armadyl_godsword"),
        ShopTabs.Tab("Wildy sets", WILDY_SETS, icon = "item.vestas_longsword"),
        ShopTabs.Tab("Revenant", REVENANT, icon = "item.craws_bow"),
        ShopTabs.Tab("Untradeables", UNTRADEABLES, icon = "item.dragon_defender"),
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

    /** Denials worth a `pk-audit` line: a human-vs-human kill that failed a fair-play rule (not the
     *  routine self/bot/NPC cases, which would flood the log on every bot death). */
    private fun PkKillGuard.Rule.audited(): Boolean = when (this) {
        PkKillGuard.Rule.SELF, PkKillGuard.Rule.BOT_VICTIM, PkKillGuard.Rule.NOT_HUMAN -> false
        else -> true
    }

    companion object {
        /** Blood Money for a real-player kill: [BM_BASE] + [BM_PER_LEVEL] × the victim's combat
         *  level (a level-126 kill pays 403). THE one formula — `bots/RogueBounty` scales it for
         *  slain Rogue Knights, so the two can never drift. TUNE. */
        const val BM_BASE = 25
        const val BM_PER_LEVEL = 3
        fun bloodMoneyFor(combatLevel: Int): Int = BM_BASE + BM_PER_LEVEL * combatLevel

        private const val SUPPLIES = "PK Rewards"
        private const val SPEC_WEAPONS = "PK Rewards - Spec Weapons"
        private const val WILDY_SETS = "PK Rewards - Wilderness Sets"
        private const val REVENANT = "PK Rewards - Revenant Weapons"
        private const val UNTRADEABLES = "PK Rewards - Untradeables"
        private const val TRADER = "npc.emblem_trader"
        private const val STOCK = 100
    }
}
