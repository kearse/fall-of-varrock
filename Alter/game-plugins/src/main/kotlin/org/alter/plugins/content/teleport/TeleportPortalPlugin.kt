package org.alter.plugins.content.teleport

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Teleport portal — Phase 0** (temporary command driver; see `docs/teleport-portal.md`).
 *
 * The portal's real entry point is a world object opening a custom cache interface
 * (Phase 1–3). Until that exists, these commands exercise the whole pure-Kotlin
 * core — the [TeleportRegistry] catalog and [TeleportService] (teleporting, the
 * persistent "Previous Teleports" list, and the server-wide "Popular Now" counts):
 *
 *   ::portal                 — list categories + your recent + popular
 *   ::portal <category>      — list a category's destinations (key · danger · state)
 *   ::portalto <key>         — teleport to a destination by key
 *   ::portalback             — repeat your last teleport (right-click preview)
 *
 * Once the interface lands, the portal object's left-click calls the same
 * [TeleportService.teleport] and right-click calls [TeleportService.lastUsed];
 * these commands can then be retired (or kept as a debug aid).
 */
class TeleportPortalPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("portal", description = "Browse the teleport portal (Phase 0 tester)") {
            val args = player.getCommandArgs()
            if (args.isEmpty()) TeleportMenu.overview(player)
            else TeleportMenu.listCategory(player, args.joinToString(" "))
        }

        onCommand("portalto", description = "Teleport via the portal by key") {
            val args = player.getCommandArgs()
            if (args.isEmpty()) {
                player.message("Usage: ::portalto <key>  (see ::portal <category>)")
            } else {
                val key = args[0].lowercase()
                val dest = TeleportRegistry.byKey(key)
                if (dest == null) player.message("Unknown teleport key: <col=801700>$key</col>.")
                else TeleportService.teleport(player, dest)
            }
        }

        onCommand("portalback", description = "Repeat your last portal teleport") {
            val last = TeleportService.lastUsed(player)
            if (last == null) player.message("You have no previous teleport yet.")
            else TeleportService.teleport(player, last)
        }
    }
}
