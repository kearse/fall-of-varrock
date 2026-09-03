package org.alter.plugins.content.core

import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.teleport.TransportRoutes

/**
 * **Shared unlock / state** — persistent, per-player boolean facts with a stable string key
 * (milestones, story beats, one-time unlocks) and transport-route state. The one store every
 * team uses instead of inventing a new attribute per fact; keys are lower-cased.
 *
 * Reserved prefixes: `quest.<key>.done` (quest completion — [QuestApi] writes it), `route.<key>`
 * (route unlocks — [unlockRoute] writes it), `veteran_of_varrock` ([VeteranApi]). Name your own
 * facts with a team prefix (`story.`, `region.kandarin.`, `pvp.`) so they never collide.
 */
object StateApi {

    fun flag(p: Player, key: String): Boolean = Flags.has(p, key)

    /** Set [key]; true if it was newly set. */
    fun setFlag(p: Player, key: String): Boolean = Flags.set(p, key)

    /** Clear [key]; true if it was set. */
    fun clearFlag(p: Player, key: String): Boolean = Flags.clear(p, key)

    fun allFlags(p: Player): Set<String> = Flags.all(p)

    // ---- transport routes (design authority §13: story restores / authorises routes; rarely removes) ----

    /** Register a lockable route; unknown keys are always OPEN so nothing is locked by default. */
    fun registerRoute(routeKey: String, lockedMessage: String, gate: TransportRoutes.Gate? = null) =
        TransportRoutes.register(routeKey, lockedMessage, gate)

    fun routeOpen(p: Player, routeKey: String): Boolean = TransportRoutes.isUnlocked(p, routeKey)

    /** Permanently open [routeKey] for [p]; true if newly unlocked. */
    fun unlockRoute(p: Player, routeKey: String): Boolean = TransportRoutes.unlock(p, routeKey)
}
