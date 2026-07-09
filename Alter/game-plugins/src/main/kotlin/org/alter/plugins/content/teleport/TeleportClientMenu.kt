package org.alter.plugins.content.teleport

import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player

/**
 * Server side of the **client-overlay** teleport menu (Phase 2, take 2).
 *
 * The Roak-style tabbed window is drawn by the custom RuneLite client's `lofteleports`
 * overlay (cache interfaces don't render on our client — see `docs/teleport-portal.md`).
 * This is the thin server half:
 *  - [open] pulses [OPEN_VARP] so the client opens the overlay on the rising edge
 *    (same pattern as the alert/HUD varps; pulse-to-0 so it isn't persisted/re-fired on login).
 *  - the actual teleport is requested back by the client as a public-chat token
 *    `::tp <cat> <row>`, intercepted in `MessagePublicHandler` → `tpclick` command
 *    ([TeleportClickPlugin]) → [TeleportService].
 *
 * Client/server must agree on category order ([TeleportCategory] ordinal) and per-category
 * row order ([TeleportRegistry.inCategory]); the client mirrors this list.
 */
object TeleportClientMenu {
    /** Free varp (see the varp map in rsps-roak-overlays): 4600 alert, 4601 war bar, 4602-4605 pk, 5000 siege. */
    const val OPEN_VARP = 4607

    /** Pulse the open signal so the client overlay opens on the rising edge. */
    fun open(p: Player) {
        p.setVarp(OPEN_VARP, 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}
