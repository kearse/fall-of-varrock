package org.alter.plugins.content.teleport

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Teleport portal — Phase 1**: the physical portal at the Lumbridge home.
 *
 * Replaces the courtyard **Fountain** (cache obj 879, type 10, 2x2, base tile
 * 3221,3226) with a teleport portal. Spawning a dynamic object on the SAME
 * tile + type-10 slot overrides the static map loc, so the fountain visually
 * becomes the portal with no cache edit.
 *
 * Click → opens the teleport menu ([TeleportMenu.open]); if the portal model has a
 * second right-click option, it repeats the player's last teleport (the right-click
 * "Previous Teleport" preview). Until the if3 interface lands (Phase 2) the menu is
 * the chat-text view.
 *
 * Options are bound **defensively** — only actions the model actually exposes are
 * wired (a hard-coded missing option would throw in init and the plugin loader would
 * silently drop this plugin). The object id is easy to swap if a different portal
 * model is preferred; tune in-game with `::aboutobj`.
 */
class TeleportPortalObjectPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        // Object literally named "Portal" (id 31961), 2x2 — exact footprint match for the
        // Fountain it replaces — with a single "Enter" action. (Aliased in object.rscm.) TUNE.
        const val PORTAL = "object.portal_31961"
        const val PORTAL_X = 3221
        const val PORTAL_Z = 3226
        const val PORTAL_TYPE = 10 // same slot as the Fountain it replaces
    }

    init {
        spawnObj(obj = PORTAL, x = PORTAL_X, z = PORTAL_Z, height = 0, type = PORTAL_TYPE, rot = 0)

        // Open the themed client overlay directly (testing). The old TabbedPanel cache UI is
        // still reachable via ::portalpanel for comparison.
        onCommand("portalui", description = "Open the teleport portal overlay") { TeleportClientMenu.open(player) }
        onCommand("portalpanel", description = "Open the old TabbedPanel teleport UI") { TeleportPanel.open(player) }

        val actions = getObject(getRSCM(PORTAL)).actions
        val present = actions.filterNotNull().filter { it.isNotBlank() }
        if (present.isEmpty()) {
            logger.warn { "Teleport portal '$PORTAL' has no click options; use ::portalui until a clickable model is set." }
        } else {
            // First option opens the themed client overlay (net.runelite.client.plugins.lofteleports)
            // by pulsing varp 4607 — the ember/gold Fall of Varrock window. Row clicks come back as
            // "::tp <cat> <row>" (see TeleportClickPlugin). The old TabbedPanel cache UI is kept on
            // ::portalpanel as a fallback. Extra object options repeat the last teleport.
            onObjOption(PORTAL, present.first()) { TeleportClientMenu.open(player) }
            present.drop(1).forEach { opt -> onObjOption(PORTAL, opt) { previousTeleport(player) } }
        }
    }

    private fun previousTeleport(p: Player) {
        val last = TeleportService.lastUsed(p)
        if (last == null) {
            p.message("You have no previous teleport yet.")
            TeleportMenu.open(p)
        } else {
            TeleportService.teleport(p, last)
        }
    }
}
