package org.alter.plugins.content.teleport

import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags

/**
 * **Transport route state** — the one seam for "can this player use this route yet?" across
 * every transport system (the teleport portal today; spirit trees / fairy rings / gliders when
 * they land). A route is OPEN unless it has been [register]ed with a lock; a registered route
 * opens for a player once its [Gate] says so OR the `route.<key>` flag is set ([unlock] — the
 * `Reward.UnlockRoute` quest reward). Unknown keys are always OPEN, so nothing is locked by
 * default and a typo can never strand a player.
 *
 * Wire a route by giving a `TeleportDestination` a `routeKey`; `TeleportService.teleport`
 * refuses locked routes with the route's [lockedMessage]. Block 1 registers no routes.
 */
object TransportRoutes {

    enum class State { OPEN, LOCKED }

    fun interface Gate {
        fun state(p: Player): State
    }

    private class Route(val key: String, val lockedMessage: String, val gate: Gate?)

    private val routes = HashMap<String, Route>()

    /** Register a lockable route. With no [gate] it is locked until [unlock]ed per player. */
    fun register(routeKey: String, lockedMessage: String, gate: Gate? = null) {
        routes[norm(routeKey)] = Route(norm(routeKey), lockedMessage, gate)
    }

    fun state(p: Player, routeKey: String): State {
        val r = routes[norm(routeKey)] ?: return State.OPEN
        if (Flags.has(p, Flags.Known.ROUTE_PREFIX + r.key)) return State.OPEN
        return r.gate?.state(p) ?: State.LOCKED
    }

    fun isUnlocked(p: Player, routeKey: String): Boolean = state(p, routeKey) == State.OPEN

    /** Permanently open [routeKey] for [p]. Returns true if it was newly unlocked. */
    fun unlock(p: Player, routeKey: String): Boolean = Flags.set(p, Flags.Known.ROUTE_PREFIX + norm(routeKey))

    fun lockedMessage(routeKey: String): String =
        routes[norm(routeKey)]?.lockedMessage ?: "You cannot travel that way yet."

    fun keys(): Set<String> = routes.keys

    private fun norm(key: String): String = key.trim().lowercase()
}
