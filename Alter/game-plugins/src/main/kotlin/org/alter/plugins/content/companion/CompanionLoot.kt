package org.alter.plugins.content.companion

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.EntityType
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.rscm.RSCM.getRSCM
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * **Companion auto-loot (donor feature).** When a companion has [Companion.autoLoot] enabled, it
 * sweeps the ground items it can see around itself each brain tick and banks them straight into the
 * owner's bank — no inventory juggling, no walking back to a banker. This is intentionally a
 * *donor* perk (toggled per companion, off by default).
 *
 * **Why a ground sweep (not a kill hook):** loot in this server is dropped to the floor by many
 * different systems (frontier mobs drop public coins/gear, bosses, future tables…). A scan over the
 * companion's tile picks all of them up regardless of source, with zero coupling to the loot code.
 *
 * **Safety — never snatch another player's loot:** only items the companion can legitimately *see*
 * are eligible (its own owned drops, or PUBLIC drops). A public drop is skipped if any **other human
 * player** (not the owner, not a bot/companion) stands within [HUMAN_GUARD] of it, so a companion
 * training next to a stranger can't hoover up the stranger's kills.
 */
object CompanionLoot {
    /** Tiles around each sweep centre searched for loot (a melee kill lands on/next to the companion). */
    private const val RADIUS = 2
    /** Brain ticks a kill location stays a sweep centre after the foe was last seen alive, so loot that
     *  appears the tick AFTER a ranged/mage kill (it drops on the TARGET, far from the companion) is still
     *  collected. ~6s. */
    private const val KILL_TILE_TTL = 5
    /** A PUBLIC drop is left alone if a non-owner human is within this many tiles of it. */
    private const val HUMAN_GUARD = 6
    /** Brain ticks between "auto-banked …" summary messages, so training doesn't spam the chatbox. */
    private const val FLUSH_TICKS = 8

    private val COINS by lazy { runCatching { getRSCM("item.coins_995") }.getOrDefault(-1) }

    /** Per-companion buffer of what's been banked since the last summary message. */
    private class Pending {
        var coins = 0L
        val items = LinkedHashMap<String, Int>()
        var ticks = 0
        var bankFullWarned = false
        /** Last tile a live foe was engaged on (where its loot will drop) + a TTL to outlive the kill. */
        var killTile: Tile? = null
        var killTtl = 0
        fun empty() = coins == 0L && items.isEmpty()
    }
    private val pending = IdentityHashMap<Companion, Pending>()

    /** Sweep + bank one companion's nearby loot (no-op unless auto-loot is on and the owner is online). */
    fun sweep(world: World, comp: Companion) {
        if (!comp.autoLoot || comp.index < 0) return
        val owner = CompanionRegistry.ownerOf(world, comp) ?: return
        val buf = pending.getOrPut(comp) { Pending() }

        // Remember where we're fighting: a ranged/mage kill's loot lands on the TARGET (up to ~10 tiles
        // away), not on the companion. Refresh the kill centre while a live foe is engaged, and keep it
        // for a few ticks so the drop that materialises the tick AFTER death is still swept.
        (comp.getCombatTarget() as? Npc)?.let { foe -> if (foe.index >= 0) { buf.killTile = foe.tile; buf.killTtl = KILL_TILE_TTL } }
        val centres = ArrayList<Tile>(2).apply {
            add(comp.tile)
            if (buf.killTtl > 0) { buf.killTile?.let { add(it) }; buf.killTtl-- }
        }

        for (gi in eligible(world, comp, owner, centres)) {
            val def = getItem(gi.item)
            // assureFullInsertion = false → take whatever the bank can hold; remainder stays on the floor.
            val txn = owner.bank.add(gi.item, gi.amount, assureFullInsertion = false)
            val banked = txn.completed
            if (banked <= 0) {
                if (!buf.bankFullWarned) {
                    owner.message("<col=801700>Your bank is full — Sir ${comp.username} can't bank any more loot.</col>")
                    buf.bankFullWarned = true
                }
                continue
            }
            buf.bankFullWarned = false
            // Eligible items are only ever PUBLIC or owned by this companion (the owner's own drops
            // aren't visible to it), so the remainder goes back to whichever of those two it was.
            val wasPublic = gi.isPublic()
            world.remove(gi)
            val remainder = gi.amount - banked
            if (remainder > 0) {
                world.spawn(if (wasPublic) GroundItem(gi.item, remainder, gi.tile)
                            else GroundItem(gi.item, remainder, gi.tile, comp))
            }
            if (gi.item == COINS) buf.coins += banked
            else buf.items[def.name ?: "an item"] = (buf.items[def.name ?: "an item"] ?: 0) + banked
        }

        // Flush a throttled summary so the owner sees what their companion gathered.
        if (!buf.empty() && ++buf.ticks >= FLUSH_TICKS) flush(comp, owner, buf)
    }

    /** Drop the per-companion buffer (call on logout / when a companion is removed). */
    fun forget(comp: Companion) {
        pending.remove(comp)
    }

    /** Send the accumulated summary line and reset the buffer. */
    private fun flush(comp: Companion, owner: Player, buf: Pending) {
        val parts = ArrayList<String>()
        if (buf.coins > 0) parts += "%,d coins".format(buf.coins)
        buf.items.forEach { (name, n) -> parts += if (n > 1) "${"%,d".format(n)}x $name" else name }
        if (parts.isNotEmpty()) {
            owner.message("<col=ffcf48>Sir ${comp.username} auto-banked: ${parts.joinToString(", ")}.</col>")
        }
        buf.coins = 0; buf.items.clear(); buf.ticks = 0
    }

    /** The ground items around any of [centres] [comp] may legitimately bank this tick (deduped by
     *  identity, since the companion's own box and the kill box can overlap). */
    private fun eligible(world: World, comp: Companion, owner: Player, centres: List<Tile>): Collection<GroundItem> {
        val out = LinkedHashSet<GroundItem>()
        for (base in centres) {
            for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
                val tile = Tile(base.x + dx, base.z + dz, base.height)
                val chunk = world.chunks.get(tile, createIfNeeded = false) ?: continue
                chunk.getEntities<GroundItem>(tile, EntityType.GROUND_ITEM).forEach { gi ->
                    if (!gi.canBeViewedBy(comp)) return@forEach           // someone else's owned drop — invisible to us
                    if (gi.isPublic() && humanNearby(world, gi.tile, owner)) return@forEach // don't steal a stranger's kill
                    out += gi
                }
            }
        }
        return out
    }

    /** True if a real player other than the owner is close enough to claim a public drop. */
    private fun humanNearby(world: World, tile: Tile, owner: Player): Boolean =
        world.players.any { p -> p !== owner && p !is PkBot && p.isOnline && !p.invisible && dist(p.tile, tile) <= HUMAN_GUARD }

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))
}
