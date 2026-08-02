package org.alter.plugins.content.hunt

import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.setNpcHintArrow
import org.alter.api.ext.setPlayerHintArrow
import org.alter.api.ext.setTileHintArrow
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.quests.QuestJournal

/**
 * **The hunt-target marker** — the shared "arrow to your assigned target" helper. Any system that
 * assigns a player something to hunt registers a [Provider]; every sweep ([TargetMarkerPlugin]'s
 * world tick) the highest-priority claim per player is resolved into the native hint arrow:
 *
 *  - a **PLAYER/NPC arrow** locked over the target's head while it's live and in scene
 *    (~[IN_SCENE_RADIUS] tiles, same plane — entity arrows only render locally), else
 *  - a **TILE arrow** toward the mark's [Mark.fallback] (a camp, a last-known spot) — or, when no
 *    fallback is given, the live target's CURRENT tile, so a moving target is chased from any
 *    distance (off-screen the client renders it as the directional edge/minimap arrow).
 *
 * Arrows are deduped per player (packets only go out on change), cleared the sweep a claim ends,
 * and respect the Quest Journal guidance mute ([QuestJournal.muted]).
 *
 * **Who plugs in:**
 *  - The **Rogue Knight ladder** ([PRIORITY_LADDER]) marks the player's assigned (or farm) knight —
 *    the live bound [PkBot] instance when it's up, the camp center otherwise.
 *  - **Bounty hunter** (future, [PRIORITY_BOUNTY]) marks the player you're assigned to kill: just
 *    register `Mark(entity = target)` with no fallback and the marker chases their live tile from
 *    afar, flipping to the over-head lock in scene. The knight path already exercises exactly this —
 *    the rogues are bot PLAYER accounts, so a real bounty target is the same arrow end-to-end.
 */
object TargetMarker {

    /** Claim priorities — when several systems mark the same player, the highest wins. */
    const val PRIORITY_BOUNTY = 100 // bounty hunter (future): an assigned player kill outranks all
    const val PRIORITY_LADDER = 50 // the Rogue Knight ladder's assigned / farm knight

    /** Within this range the arrow locks onto the target itself (entity arrows render in scene). */
    const val IN_SCENE_RADIUS = 15

    /**
     * One system's claim on a player's arrow: the live [entity] to lock onto (checked for liveness
     * centrally — pass it unconditionally), and/or the [fallback] tile to point at while the entity
     * is absent, dead or out of scene. No fallback → a live entity's current tile is used instead.
     */
    data class Mark(val entity: Pawn? = null, val fallback: Tile? = null)

    /** Yields [p]'s current mark for one system, or null when the system has no claim on them. */
    fun interface Provider {
        fun mark(p: Player): Mark?
    }

    private val providers = ArrayList<Pair<Int, Provider>>()

    /** Register a marker source (once, at plugin init). Higher [priority] claims win. */
    fun register(priority: Int, provider: Provider) {
        providers += priority to provider
        providers.sortByDescending { it.first }
    }

    /** Last arrow sent per player, so the sweep only writes packets on change. */
    private sealed class Arrow {
        data object None : Arrow()
        data class Spot(val x: Int, val z: Int, val height: Int) : Arrow()
        data class OnPlayer(val index: Int) : Arrow()
        data class OnNpc(val index: Int) : Arrow()
    }

    private val lastArrow = HashMap<PlayerUID, Arrow>()

    /** The sweep ([TargetMarkerPlugin]): resolve and (re)draw every real player's arrow. */
    fun tick(world: World) {
        val online = HashSet<PlayerUID>()
        world.players.forEach { p ->
            if (p is PkBot || !p.isOnline || p.invisible) return@forEach
            online += p.uid
            update(p)
        }
        lastArrow.keys.retainAll(online) // logged-off holders — nobody left to clear
    }

    private fun update(p: Player) {
        val desired = resolve(p)
        if ((lastArrow[p.uid] ?: Arrow.None) == desired) return
        if (desired == Arrow.None) lastArrow.remove(p.uid) else lastArrow[p.uid] = desired
        when (desired) {
            is Arrow.None -> p.clearHintArrow()
            is Arrow.Spot -> p.setTileHintArrow(desired.x, desired.z, desired.height)
            is Arrow.OnPlayer -> p.setPlayerHintArrow(desired.index)
            is Arrow.OnNpc -> p.setNpcHintArrow(desired.index)
        }
    }

    /** Highest-priority claim that resolves to a drawable arrow (dead claims fall through). */
    private fun resolve(p: Player): Arrow {
        if (QuestJournal.muted(p)) return Arrow.None
        for ((_, provider) in providers) {
            val mark = provider.mark(p) ?: continue
            val arrow = toArrow(p, mark)
            if (arrow != Arrow.None) return arrow
        }
        return Arrow.None
    }

    private fun toArrow(p: Player, mark: Mark): Arrow {
        val entity = mark.entity?.takeIf { it.index >= 0 && !it.isDead() }
        if (entity != null && entity.tile.height == p.tile.height &&
            entity.tile.isWithinRadius(p.tile, IN_SCENE_RADIUS)
        ) {
            when (entity) {
                is Player -> return Arrow.OnPlayer(entity.index)
                is Npc -> return Arrow.OnNpc(entity.index)
                else -> {} // unknown pawn kind — guide by tile below
            }
        }
        val tile = mark.fallback ?: entity?.tile ?: return Arrow.None
        return Arrow.Spot(tile.x, tile.z, tile.height)
    }
}
