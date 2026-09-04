package org.alter.plugins.content.teleport

import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Slayer-level gates on the Slayer-boss portal rows (player request 2026-09-03: "add the
 * Slayer requirement to the teleport"). The attack gate already exists on each boss's combat
 * def (`slayerData`); this is the same level checked one step earlier, at the portal, through
 * the existing [TransportRoutes] seam — `TeleportService.teleport` refuses a locked route with
 * the message below. Server-side only: the client mirror never sees route keys.
 */
class SlayerTeleportGatesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        gate(ROUTE_KRAKEN, 87, "the Kraken")
        gate(ROUTE_CERBERUS, 91, "Cerberus")
        gate(ROUTE_THERMY, 93, "the Thermonuclear Smoke Devil")
        gate(ROUTE_HYDRA, 95, "the Alchemical Hydra")
    }

    private fun gate(route: String, level: Int, who: String) {
        TransportRoutes.register(route, "You need a Slayer level of $level to travel to $who.") { p ->
            if (p.getSkills().getBaseLevel(Skills.SLAYER) >= level) TransportRoutes.State.OPEN else TransportRoutes.State.LOCKED
        }
    }

    companion object {
        const val ROUTE_KRAKEN = "slayer.kraken"
        const val ROUTE_CERBERUS = "slayer.cerberus"
        const val ROUTE_THERMY = "slayer.thermy"
        const val ROUTE_HYDRA = "slayer.hydra"
    }
}
