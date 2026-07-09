package org.alter.plugins.content.bosses

import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * The **structure** of the Collection Log: which sources (bosses) contribute which loggable
 * items. [CollectionLog] stores the flat set of obtained item ids per player; this registry
 * groups them into categories so we can show per-source progress (obtained / total) and which
 * slots are still missing — the data backbone any view (chatbox command OR cache interface)
 * consumes.
 *
 * **Single source of truth:** the [Category.items] here must mirror the `log = true` drops in
 * each boss's loot table ([KbdBossPlugin], [org.alter.plugins.content.npcs.wilderness.WildernessBosses],
 * and the Corporeal Beast's `uniqueTable` in
 * [org.alter.plugins.content.war.boss.BossRegistry] / [org.alter.plugins.content.war.boss.BossLoot]).
 *
 * Item/npc keys stay as RSCM strings and are resolved lazily + guarded, so an id not yet in the
 * cache is skipped rather than crashing.
 */
object CollectionLogRegistry {

    /**
     * @param npcKey npc RSCM key for the page's head icon (null = no head). Best-effort.
     * @param items the loggable drops for this source, in display order (RSCM item keys).
     */
    data class Category(
        val key: String,
        val displayName: String,
        val npcKey: String?,
        val items: List<String>,
    )

    val categories: List<Category> = listOf(
        Category(
            "corp_beast", "Corporeal Beast", "npc.corporeal_beast",
            listOf(
                "item.arcane_sigil", "item.spectral_sigil", "item.elysian_sigil",
                "item.blessed_spirit_shield", "item.draconic_visage", "item.pet_corporeal_critter",
            ),
        ),
        Category(
            "kbd", "King Black Dragon", "npc.king_black_dragon",
            listOf("item.dragon_med_helm", "item.kbd_heads", "item.draconic_visage", "item.prince_black_dragon"),
        ),
        Category(
            "callisto", "Callisto", "npc.callisto",
            listOf("item.dragon_pickaxe", "item.dragon_2h_sword"),
        ),
        Category(
            "vetion", "Vet'ion", "npc.vetion",
            listOf("item.dragon_pickaxe", "item.ring_of_the_gods"),
        ),
        Category(
            "venenatis", "Venenatis", "npc.venenatis",
            listOf("item.dragon_pickaxe", "item.treasonous_ring"),
        ),
        Category(
            "scorpia", "Scorpia", "npc.scorpia",
            listOf("item.malediction_shard_3", "item.odium_shard_3"),
        ),
        Category(
            "chaos_elemental", "Chaos Elemental", "npc.chaos_elemental_2054",
            listOf("item.dragon_pickaxe", "item.dragon_2h_sword"),
        ),
        Category(
            "chaos_fanatic", "Chaos Fanatic", "npc.chaos_fanatic",
            listOf("item.odium_shard_1", "item.malediction_shard_1"),
        ),
        Category(
            "crazy_arch", "Crazy Archaeologist", "npc.crazy_archaeologist",
            listOf("item.odium_shard_2", "item.malediction_shard_2"),
        ),
    )

    fun byKey(key: String): Category? = categories.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** Resolved (guarded) item ids for [cat], in display order; unknown cache ids dropped. */
    fun itemIds(cat: Category): List<Int> = cat.items.mapNotNull { resolve(it) }

    /** All distinct loggable item ids across every category (the union the log can hold). */
    fun allItemIds(): Set<Int> = categories.flatMap { itemIds(it) }.toSet()

    /** How many of [cat]'s items [player] has obtained. */
    fun obtainedCount(player: Player, cat: Category): Int {
        val have = CollectionLog.ids(player)
        return itemIds(cat).count { it in have }
    }

    /** Total loggable items in [cat] (resolved count). */
    fun total(cat: Category): Int = itemIds(cat).size

    /** Overall obtained / total across all categories (distinct items). */
    fun overallObtained(player: Player): Int {
        val have = CollectionLog.ids(player)
        return allItemIds().count { it in have }
    }

    fun overallTotal(): Int = allItemIds().size

    private fun resolve(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }
}
