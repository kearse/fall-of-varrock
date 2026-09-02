package org.alter.plugins.content.drops

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player

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
        val rows = NpcDropTables.tableFor(npc.id) {
            runCatching { getNpc(npc.id).name }.getOrNull()
        } ?: return
        val tile = npc.tile
        for (row in rows) {
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

    fun scaleAmount(itemId: Int, amount: Int): Int {
        val mult = if (itemId == COINS) NpcDropConfig.coinMultiplier else NpcDropConfig.quantityMultiplier
        if (mult == 1.0) return amount
        return (amount * mult).toInt().coerceAtLeast(1)
    }
}
