package org.alter.plugins.content.skills.slayer

import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.game.model.attr.RESOURCE_CONTRACT_ITEM_ATTR
import org.alter.game.model.attr.RESOURCE_CONTRACT_LEFT_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
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
 */
object ResourceContracts {

    /** One kind of gathering contract. [skillId] gates assignment to a suitable level. */
    data class ResourceTask(
        val itemKey: String,
        val display: String,
        val skill: String,
        val skillId: Int,
        val minLevel: Int,
        val amount: IntRange,
        val coinReward: Int,
        val weReward: Int,
    )

    /** Starter roster — the three home gathering skills around Lumbridge. Expand as content grows. */
    val ALL: List<ResourceTask> = listOf(
        ResourceTask("item.copper_ore", "copper ore", "Mining", Skills.MINING, 1, 15..25, 1_500, 4),
        ResourceTask("item.tin_ore", "tin ore", "Mining", Skills.MINING, 1, 15..25, 1_500, 4),
        ResourceTask("item.iron_ore", "iron ore", "Mining", Skills.MINING, 15, 20..35, 2_500, 6),
        ResourceTask("item.logs", "logs", "Woodcutting", Skills.WOODCUTTING, 1, 15..25, 1_500, 4),
        ResourceTask("item.oak_logs", "oak logs", "Woodcutting", Skills.WOODCUTTING, 15, 20..35, 2_500, 6),
        ResourceTask("item.raw_shrimps", "raw shrimps", "Fishing", Skills.FISHING, 1, 15..25, 1_500, 4),
        ResourceTask("item.raw_trout", "raw trout", "Fishing", Skills.FISHING, 20, 20..35, 2_500, 6),
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

    /** Assign a fresh eligible contract; returns (task, size), or null if one is active / none fit. */
    fun assign(p: Player): Pair<ResourceTask, Int>? {
        current(p)?.let { return it } // already has one — report it instead
        val eligible = byKey.values.filter { p.getSkills().getCurrentLevel(it.skillId) >= it.minLevel }
        if (eligible.isEmpty()) return null
        val task = eligible[p.world.random(eligible.size - 1)]
        val amount = p.world.random(task.amount)
        p.attr[RESOURCE_CONTRACT_ITEM_ATTR] = task.itemKey
        p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = amount
        return task to amount
    }

    /** Called by the gathering plugins as a resource item is produced. */
    fun onGather(p: Player, itemId: Int) {
        val key = p.attr[RESOURCE_CONTRACT_ITEM_ATTR] ?: return
        val left = p.attr[RESOURCE_CONTRACT_LEFT_ATTR] ?: 0
        if (left <= 0) return
        if (resolveId(key) != itemId) return // not the contracted resource
        val remaining = left - 1
        if (remaining > 0) {
            p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = remaining
            return
        }
        // Complete: clear the contract and pay out coin + War Effort.
        val task = byKey[key]
        p.attr.remove(RESOURCE_CONTRACT_ITEM_ATTR)
        p.attr[RESOURCE_CONTRACT_LEFT_ATTR] = 0
        if (task != null) {
            giveCoins(p, task.coinReward)
            p.addPoints(PointKind.WAR_EFFORT, task.weReward)
            p.message("<col=801700>Resource contract complete!</col> +${"%,d".format(task.coinReward)} coins and +${task.weReward} War Effort. See Vannaka for another.")
        }
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
