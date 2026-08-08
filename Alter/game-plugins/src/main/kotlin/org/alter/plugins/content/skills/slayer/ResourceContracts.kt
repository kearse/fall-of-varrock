package org.alter.plugins.content.skills.slayer

import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.game.model.attr.FISHING_PREFERENCE_ATTR
import org.alter.game.model.attr.RESOURCE_CONTRACT_ITEM_ATTR
import org.alter.game.model.attr.RESOURCE_CONTRACT_LAST_ATTR
import org.alter.game.model.attr.RESOURCE_CONTRACT_LEFT_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * **Resource contracts** — Vannaka's gathering work (master design brief §2/§3: skilling supplies the
 * war). Vannaka assigns a contract to gather N of a resource (ore, logs, fish...); the relevant
 * gathering plugin calls [onGather] as the player produces it, and on completion the player is paid
 * in **coin + War Effort** (the resource side; the combat side pays War Effort scaled by streak).
 *
 * Auto-completes as the resource is gathered (no turn-in), mirroring the Slayer kill counter — the
 * gathered items stay with the player. Held independently of the Slayer (combat) contract, so a
 * player can run one of each at once.
 *
 * **The rank-tiered faucet** (story-and-grind-design §3): richer work orders unlock with feudal
 * rank, making contracts the mid-game coin faucet that bridges the Squire→Lord coin chasm — a
 * Knight clearing top-tier contracts earns rank money in weeks of play, not months of goblins.
 * Rank gates the CONTRACT, not the resource: anyone may gather anything their skills allow, but
 * the Quartermaster's rich commissions go to players of standing.
 */
object ResourceContracts {

    /**
     * Launch generosity curve (story-and-grind-design §7): coin + War Effort payouts are
     * multiplied by this. Start hot so the young economy fills; step toward 1 as it matures.
     */
    const val GENEROSITY_MULT: Int = 2

    /** One kind of gathering contract. [skillId] gates assignment to a suitable level;
     *  [minTitle] gates it to a feudal rank (see the class doc). */
    data class ResourceTask(
        val itemKey: String,
        val display: String,
        val skill: String,
        val skillId: Int,
        val minLevel: Int,
        val amount: IntRange,
        val coinReward: Int,
        val weReward: Int,
        val minTitle: Title = Title.PEASANT,
    )

    /** The roster, tiered by rank. Coin values are per-contract BASE pay (see [GENEROSITY_MULT]);
     *  amounts shrink as resources get slower to gather. TUNE freely. */
    val ALL: List<ResourceTask> = listOf(
        // ── Peasant — the three home gathering skills around Lumbridge ──
        ResourceTask("item.copper_ore", "copper ore", "Mining", Skills.MINING, 1, 15..25, 1_500, 4),
        ResourceTask("item.tin_ore", "tin ore", "Mining", Skills.MINING, 1, 15..25, 1_500, 4),
        ResourceTask("item.iron_ore", "iron ore", "Mining", Skills.MINING, 15, 20..35, 2_500, 6),
        ResourceTask("item.logs", "logs", "Woodcutting", Skills.WOODCUTTING, 1, 15..25, 1_500, 4),
        ResourceTask("item.oak_logs", "oak logs", "Woodcutting", Skills.WOODCUTTING, 15, 20..35, 2_500, 6),
        ResourceTask("item.raw_shrimps", "raw shrimps", "Fishing", Skills.FISHING, 1, 15..25, 1_500, 4),
        ResourceTask("item.raw_trout", "raw trout", "Fishing", Skills.FISHING, 20, 20..35, 2_500, 6),
        // ── Commoner (10k) ──
        ResourceTask("item.coal", "coal", "Mining", Skills.MINING, 30, 20..35, 4_000, 8, Title.COMMONER),
        ResourceTask("item.willow_logs", "willow logs", "Woodcutting", Skills.WOODCUTTING, 30, 25..40, 4_000, 8, Title.COMMONER),
        ResourceTask("item.raw_pike", "raw pike", "Fishing", Skills.FISHING, 25, 20..35, 4_000, 8, Title.COMMONER),
        // ── Squire (50k) ──
        ResourceTask("item.mithril_ore", "mithril ore", "Mining", Skills.MINING, 55, 12..20, 8_000, 12, Title.SQUIRE),
        ResourceTask("item.maple_logs", "maple logs", "Woodcutting", Skills.WOODCUTTING, 45, 25..40, 8_000, 12, Title.SQUIRE),
        ResourceTask("item.raw_lobster", "raw lobsters", "Fishing", Skills.FISHING, 40, 20..30, 8_000, 12, Title.SQUIRE),
        // ── Soldier (150k) ──
        ResourceTask("item.adamantite_ore", "adamantite ore", "Mining", Skills.MINING, 70, 10..16, 18_000, 20, Title.SOLDIER),
        ResourceTask("item.yew_logs", "yew logs", "Woodcutting", Skills.WOODCUTTING, 60, 20..30, 18_000, 20, Title.SOLDIER),
        ResourceTask("item.raw_swordfish", "raw swordfish", "Fishing", Skills.FISHING, 50, 20..30, 15_000, 18, Title.SOLDIER),
        ResourceTask("item.raw_monkfish", "raw monkfish", "Fishing", Skills.FISHING, 62, 20..30, 20_000, 22, Title.SOLDIER),
        // ── Knight (500k) — top of the ladder; steady work toward Lord's 2M ──
        ResourceTask("item.runite_ore", "runite ore", "Mining", Skills.MINING, 85, 6..10, 45_000, 35, Title.KNIGHT),
        ResourceTask("item.magic_logs", "magic logs", "Woodcutting", Skills.WOODCUTTING, 75, 15..25, 40_000, 32, Title.KNIGHT),
        ResourceTask("item.raw_shark", "raw sharks", "Fishing", Skills.FISHING, 76, 18..28, 40_000, 32, Title.KNIGHT),
    )

    /** Tasks whose item actually resolves in this cache, keyed by item key. */
    private val byKey: Map<String, ResourceTask> = ALL.filter { resolves(it.itemKey) }.associateBy { it.itemKey }

    fun hasContract(p: Player): Boolean =
        p.attr[RESOURCE_CONTRACT_ITEM_ATTR] != null && (p.attr[RESOURCE_CONTRACT_LEFT_ATTR] ?: 0) > 0

    /** The active (task, remaining), or null if none. */
    fun current(p: Player): Pair<ResourceTask, Int>? {
        val key = p.attr[RESOURCE_CONTRACT_ITEM_ATTR] ?: return null
        val left = p.attr[RESOURCE_CONTRACT_LEFT_ATTR] ?: 0
        val task = byKey[key] ?: return null
        return if (left > 0) task to left else null
    }

    /** Assign a fresh eligible contract; returns (task, size), or null if one is active / none fit.
     *  Prefers the player's highest unlocked rank tier so ranking up visibly upgrades the work. */
    fun assign(p: Player): Pair<ResourceTask, Int>? {
        current(p)?.let { return it } // already has one — report it instead
        val eligible = byKey.values.filter {
            p.getSkills().getCurrentLevel(it.skillId) >= it.minLevel &&
                p.title.ordinal >= it.minTitle.ordinal
        }
        if (eligible.isEmpty()) return null
        // Weight toward the best tier the player has unlocked: draw from the top two tiers present.
        val topTier = eligible.maxOf { it.minTitle.ordinal }
        var pool = eligible.filter { it.minTitle.ordinal >= topTier - 1 }
        // Never hand out the same work twice running — unless it's the only work they qualify for.
        val last = p.attr[RESOURCE_CONTRACT_LAST_ATTR]
        if (last != null && pool.size > 1) {
            pool.filter { it.itemKey != last }.let { if (it.isNotEmpty()) pool = it }
        }
        val task = pool[p.world.random(pool.size - 1)]
        val amount = p.world.random(task.amount)
        p.attr[RESOURCE_CONTRACT_ITEM_ATTR] = task.itemKey
        p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = amount
        // A stale fish pick would otherwise outrank the new contract forever (the pref persists).
        // Point it at the contracted fish; ::fish still overrides it afterwards.
        if (task.skillId == Skills.FISHING) p.attr[FISHING_PREFERENCE_ATTR] = task.itemKey
        return task to amount
    }

    /** True if a rank above the player's gates richer contracts their skills could already
     *  work — Vannaka uses it to point at the Duke ("rank up and I'll have richer work"). */
    fun richerWorkAwaits(p: Player): Boolean =
        byKey.values.any {
            it.minTitle.ordinal > p.title.ordinal &&
                p.getSkills().getCurrentLevel(it.skillId) >= it.minLevel
        }

    /** The RSCM item key the player is currently contracted to gather, or null. Gathering plugins
     *  use it to steer what they produce (fishing picks the contracted fish over the best one). */
    fun contractedKey(p: Player): String? = current(p)?.first?.itemKey

    /** Called by the gathering plugins as a resource item is produced. */
    fun onGather(p: Player, itemId: Int) {
        val key = p.attr[RESOURCE_CONTRACT_ITEM_ATTR] ?: return
        val left = p.attr[RESOURCE_CONTRACT_LEFT_ATTR] ?: 0
        if (left <= 0) return
        if (resolveId(key) != itemId) return // not the contracted resource
        val remaining = left - 1
        if (remaining > 0) {
            p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = remaining
            ContractMenu.push(p) // keep the client's board/Task Helper count live as it drops
            return
        }
        // Complete: clear the contract and pay out coin + War Effort (launch-generosity scaled).
        val task = byKey[key]
        p.attr.remove(RESOURCE_CONTRACT_ITEM_ATTR)
        p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = 0
        p.attr[RESOURCE_CONTRACT_LAST_ATTR] = key // so the next contract isn't the same work again
        if (task != null) {
            val coins = task.coinReward * GENEROSITY_MULT
            val we = task.weReward * GENEROSITY_MULT
            giveCoins(p, coins)
            p.addPoints(PointKind.WAR_EFFORT, we)
            p.message("<col=801700>Resource contract complete!</col> +${"%,d".format(coins)} coins and +$we War Effort. See Vannaka for another.")
        }
        ContractMenu.push(p) // the client drops its resource-task card the moment the work is done
    }

    /** Pay coins into the bag, overflowing to the bank (gathering tends to fill the inventory). */
    private fun giveCoins(p: Player, amount: Int) {
        runCatching {
            val id = getRSCM("item.coins_995")
            val tx = p.inventory.add(id, amount, assureFullInsertion = false)
            val left = amount - tx.completed
            if (left > 0) p.bank.add(id, left)
        }
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }
    private fun resolveId(key: String): Int = try { getRSCM(key) } catch (e: Exception) { -1 }
}
