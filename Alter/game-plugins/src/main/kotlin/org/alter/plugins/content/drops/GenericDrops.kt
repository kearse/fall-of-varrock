package org.alter.plugins.content.drops

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * The generic real-OSRS drop roll, extracted so content that owns a per-id death handler (which
 * suppresses [NpcDropPlugin]'s handler via `hasNpcDeathHandler`) can still hand out the normal
 * table when it decides not to keep the loot. The [WarEffortPlugin] uses this for goblins/hobgoblins
 * killed OUTSIDE a war raid — without it those ids dropped nothing anywhere, because registering
 * `onNpcDeath("npc.goblin")` marks the id as handler-owned everywhere.
 */
object GenericDrops {
    private const val COINS = 995

    /** Roll [npc]'s OSRS drop table and spawn the loot for [killer]. No-op if the tables aren't
     *  loaded, the npc has no table, or the amount scales to zero. */
    fun rollAndDrop(world: World, npc: Npc, killer: Player) {
        if (!NpcDropConfig.enabled || !NpcDropTables.loaded) return
        val name = runCatching { getNpc(npc.id).name }.getOrNull()
        val table = NpcDropTables.tableFor(npc.id) { name }
        // Config-added rows ride on top of the osrsbox table (and stand alone for a monster that
        // has no table at all), so an item OSRS hands out through content this server lacks can be
        // put back without regenerating the drop JSON. Keyed by name, so every variant id matches.
        val extra = name?.lowercase()?.trim()?.let { NpcDropConfig.extraDrops[it] }.orEmpty()
        if (table == null && extra.isEmpty()) return
        val rows = table.orEmpty() + extra
        val tile = npc.tile
        for (row in rows) {
            // Data-tunable item veto (clue scrolls, caskets, bogus 100% rows) — config.yml.
            if (row.itemId in NpcDropConfig.excludeItemIds) continue
            // OSRS: a looting bag never drops for someone who already owns one.
            if (row.itemId in LOOTING_BAGS && ownsLootingBag(killer)) continue
            repeat(row.rolls) {
                if (world.randomDouble() >= row.rarity) return@repeat
                if (runCatching { getItem(row.itemId) }.isFailure) return@repeat
                var amount = if (row.max <= row.min) row.min else row.min + world.random(row.max - row.min)
                amount = scaleAmount(row.itemId, amount)
                if (amount <= 0) return@repeat
                world.spawn(GroundItem(row.itemId, amount, tile, killer))
            }
        }
    }

    /** Open + closed looting bag ids (11941 / 22586). */
    private val LOOTING_BAGS: Set<Int> by lazy {
        listOf("item.looting_bag", "item.looting_bag_22586")
            .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
    }

    /** One bag per player: inventory, bank or worn (player report 2026-09-02). */
    fun ownsLootingBag(player: Player): Boolean =
        LOOTING_BAGS.any { id ->
            player.inventory.contains(id) || player.bank.contains(id) || player.equipment.contains(id)
        }

    fun scaleAmount(itemId: Int, amount: Int): Int {
        val mult = if (itemId == COINS) NpcDropConfig.coinMultiplier else NpcDropConfig.quantityMultiplier
        if (mult == 1.0) return amount
        return (amount * mult).toInt().coerceAtLeast(1)
    }
}
