package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

/**
 * **Quartermaster supply sink** (master design brief §3B — skilling supplies the war). The Quartermaster
 * accepts the *finished* goods that skilling produces — cooked food, finished potions, smithed bars —
 * and pays **War Effort per unit**, consuming the items. This is the economy's deliberate item sink and
 * the end of the "gather → produce → supply the war" loop (the resource contracts are the gather step).
 *
 * Per-unit values are modest and TUNABLE — the point is to give surplus production a war purpose (and
 * feed the daily War Effort bonus), not to be a gp faucet.
 */
object SupplyDepot {

    /** Cooked food (fishing→cooking, farming, etc.). */
    val FOOD: Map<String, Int> = mapOf(
        "item.shrimps" to 1, "item.anchovies" to 1, "item.bread" to 1, "item.cooked_chicken" to 1,
        "item.cooked_meat" to 1, "item.sardine" to 1, "item.herring" to 2, "item.trout" to 2,
        "item.pike" to 3, "item.salmon" to 3, "item.tuna" to 3, "item.lobster" to 4,
        "item.bass" to 5, "item.swordfish" to 5, "item.monkfish" to 6, "item.shark" to 8,
    )

    /** Finished potions (herblore), all doses. */
    val POTIONS: Map<String, Int> = buildMap {
        putAll(doses("item.attack_potion", 3)); putAll(doses("item.strength_potion", 3))
        putAll(doses("item.defence_potion", 3)); putAll(doses("item.super_attack", 5))
        putAll(doses("item.super_strength", 5)); putAll(doses("item.super_defence", 5))
        putAll(doses("item.prayer_potion", 5)); putAll(doses("item.super_combat_potion", 10))
        putAll(doses("item.ranging_potion", 5)); putAll(doses("item.saradomin_brew", 8))
    }

    /** Smithed bars (mining→smithing). */
    val BARS: Map<String, Int> = mapOf(
        "item.bronze_bar" to 1, "item.iron_bar" to 2, "item.steel_bar" to 3,
        "item.mithril_bar" to 5, "item.adamantite_bar" to 8, "item.runite_bar" to 12,
    )

    // Raw-material categories (the depot-as-store expansion): every gathering skill can sell
    // its output for War Effort. BALANCE RULE (TUNE): a raw material is always worth LESS WE
    // than its processed form (ore < bar, raw fish < cooked) so processing stays rewarded,
    // and every WE value sits below the item's general-store coin value so the depot is a
    // war-contribution choice, not a gp-arbitrage faucet.

    /** Ores + coal (mining). Kept below the smithed-bar values — smelting pays. */
    val ORES: Map<String, Int> = mapOf(
        "item.copper_ore" to 1, "item.tin_ore" to 1, "item.iron_ore" to 1,
        "item.silver_ore" to 2, "item.coal" to 2, "item.gold_ore" to 3,
        "item.mithril_ore" to 3, "item.adamantite_ore" to 5, "item.runite_ore" to 8,
    )

    /** Logs (woodcutting). */
    val LOGS: Map<String, Int> = mapOf(
        "item.logs" to 1, "item.oak_logs" to 1, "item.willow_logs" to 2,
        "item.teak_logs" to 2, "item.maple_logs" to 3, "item.mahogany_logs" to 4,
        "item.yew_logs" to 5, "item.magic_logs" to 8,
    )

    /** Raw fish (fishing). Roughly half the cooked value — the kitchens still have work to do. */
    val RAW_FISH: Map<String, Int> = mapOf(
        "item.raw_shrimps" to 1, "item.raw_sardine" to 1, "item.raw_herring" to 1,
        "item.raw_trout" to 1, "item.raw_pike" to 1, "item.raw_salmon" to 1,
        "item.raw_tuna" to 2, "item.raw_lobster" to 2, "item.raw_bass" to 2,
        "item.raw_swordfish" to 3, "item.raw_monkfish" to 3, "item.raw_shark" to 4,
    )

    /** The depot's category shelf, in display order (drives the client window's tabs). */
    data class Category(val label: String, val wares: Map<String, Int>)

    val CATEGORIES: List<Category> by lazy {
        listOf(
            Category("Food", FOOD),
            Category("Potions", POTIONS),
            Category("Bars", BARS),
            Category("Ores", ORES),
            Category("Logs", LOGS),
            Category("Raw fish", RAW_FISH),
        )
    }

    /** Everything the Quartermaster takes. */
    val ALL: Map<String, Int> by lazy { FOOD + POTIONS + BARS + ORES + LOGS + RAW_FISH }

    /** [ALL] resolved once by item id → (rscm key, base WE). The storefront prices every cell and
     *  values every hand-in through this, so neither path re-resolves rscm keys per click. */
    val BY_ID: Map<Int, Pair<String, Int>> by lazy {
        ALL.entries.mapNotNull { (key, we) ->
            runCatching { getRSCM(key) }.getOrNull()?.let { it to (key to we) }
        }.toMap()
    }

    /** War Effort paid per unit of [itemId] right now — base value × today's [SupplyDrive]. 0 = not accepted.
     *  Noted items resolve to their unnoted form for the lookup, so a noted stack is valued the same as
     *  the item it represents (unnoting an already-unnoted id is a no-op — shelf callers are unaffected). */
    fun valueOf(itemId: Int): Int {
        val (key, we) = BY_ID[Item(itemId).toUnnoted().id] ?: return 0
        return we * SupplyDrive.multiplierFor(key)
    }

    /** The Quartermaster's two posts: the shop hub and the tutorial post in The Mire crypt. */
    val POSTS = listOf(Tile(3223, 3211, 0), Tile(3248, 3193, 0))
    private const val POST_RADIUS = 10

    /**
     * Can [p] hand supplies in right now? Deposits happen AT a Quartermaster post, and never before
     * the intro-quest dagger hand-in (the teaching beat). Messages the player on refusal.
     */
    fun canHandIn(p: Player): Boolean {
        if (POSTS.none { p.tile.isWithinRadius(it, POST_RADIUS) }) {
            p.message("The Quartermaster takes supplies at his post — find him at the shop hub or in The Mire.")
            return false
        }
        if (RecruitTrials.step(p) == RecruitTrials.Step.DELIVER) {
            p.message("Hand the Quartermaster your forged bronze dagger first — that's the lesson.")
            return false
        }
        return true
    }

    /** Build dose-1..4 entries for a potion base, scaling the value down with the dose. */
    private fun doses(base: String, v4: Int): Map<String, Int> = mapOf(
        "${base}4" to v4,
        "${base}3" to (v4 * 3 / 4).coerceAtLeast(1),
        "${base}2" to (v4 / 2).coerceAtLeast(1),
        "${base}1" to (v4 / 4).coerceAtLeast(1),
    )

    /** Consume every matching item in the player's inventory and pay War Effort. Returns (items, WE).
     *  Items in the active [SupplyDrive] category pay the drive multiplier (Phase C — high demand). */
    fun deposit(p: Player, wares: Map<String, Int>): Pair<Int, Int> {
        var items = 0
        var we = 0
        var boosted = false
        for ((key, value) in wares) {
            val id = runCatching { getRSCM(key) }.getOrNull() ?: continue
            val count = p.inventory.getItemCount(id)
            if (count <= 0) continue
            p.inventory.remove(id, count)
            val mult = SupplyDrive.multiplierFor(key)
            if (mult > 1) boosted = true
            items += count
            we += count * value * mult
        }
        if (we > 0) {
            p.addPoints(PointKind.WAR_EFFORT, we)
            // Supplies also feed the realm war-supply meter that gates campaigns (the Mire loop).
            org.alter.plugins.content.war.RealmSupply.contribute(p.world, we)
            if (boosted) {
                p.message("<col=ffae00>Supply Drive bonus!</col> Your ${SupplyDrive.active?.display?.lowercase()} paid ${SupplyDrive.MULTIPLIER}x War Effort.")
            }
            // UX: show the supplier what their hand-in did to the realm's stores — the meter
            // only used to speak when it crossed a campaign threshold.
            p.message("The realm's war-stores rise to <col=4f9b4f>${org.alter.plugins.content.war.RealmSupply.meter()}/${org.alter.plugins.content.war.RealmSupply.max()}</col> — marches and campaigns feed on them.")
        }
        return items to we
    }

    /**
     * Sell up to [qty] of ONE accepted item (the client window's per-item hand-in; qty <= 0 means
     * "all carried"). Returns (items, WE) — (0, 0) if the item isn't accepted or none carried.
     */
    fun depositItem(p: Player, itemId: Int, qty: Int): Pair<Int, Int> {
        // Price/accept by the unnoted form, but count and remove the ACTUAL carried id (which may be
        // noted) — mirrors ItemCurrency.buyFromPlayer, so noted stacks hand in like unnoted ones.
        val (key, value) = BY_ID[Item(itemId).toUnnoted().id] ?: return 0 to 0
        val carried = p.inventory.getItemCount(itemId)
        if (carried <= 0) return 0 to 0
        val count = if (qty <= 0) carried else minOf(qty, carried)
        p.inventory.remove(itemId, count)
        val mult = SupplyDrive.multiplierFor(key)
        val we = count * value * mult
        if (we > 0) {
            p.addPoints(PointKind.WAR_EFFORT, we)
            org.alter.plugins.content.war.RealmSupply.contribute(p.world, we)
            if (mult > 1) {
                p.message("<col=ffae00>Supply Drive bonus!</col> That paid ${SupplyDrive.MULTIPLIER}x War Effort.")
            }
        }
        return count to we
    }
}
