package org.alter.plugins.content.teleport

import org.alter.api.ext.message
import org.alter.game.model.entity.Player

/**
 * Shared presentation for the teleport portal, used by both the portal world
 * object ([TeleportPortalObjectPlugin]) and the `::portal` debug command
 * ([TeleportPortalPlugin]).
 *
 * **Phase 1 (current):** there is no cache interface yet, so [open] renders the
 * catalog as chat text and points the player at the keys. Once the if3 interface
 * is authored (Phase 2), [open] becomes "open the interface" and this text view is
 * kept only as a fallback / debug aid.
 */
object TeleportMenu {

    /** Portal opened — Phase 1 text overview. */
    fun open(p: Player) {
        p.message("<col=801700>The portal hums with energy.</col> Where to?")
        overview(p)
    }

    fun overview(p: Player) {
        p.message("<col=801700>== Teleport Portal ==</col>")
        p.message("Categories: " + TeleportCategory.values().joinToString(", ") { it.display })
        p.message("Use <col=801700>::portal <category></col> to list, <col=801700>::portalto <key></col> to go.")

        val recent = TeleportService.recent(p)
        if (recent.isNotEmpty()) {
            p.message("Recent: " + recent.joinToString(", ") { "${it.displayName} (${it.key})" })
        }
        val popular = TeleportService.popular()
        if (popular.isNotEmpty()) {
            p.message("Popular: " + popular.joinToString(", ") { it.displayName })
        }
    }

    fun listCategory(p: Player, query: String) {
        val cat = TeleportCategory.values().firstOrNull {
            it.name.equals(query, true) || it.display.equals(query, true) ||
                it.display.replace(" ", "").equals(query.replace(" ", ""), true)
        }
        if (cat == null) {
            p.message("Unknown category '<col=801700>$query</col>'. Try: " +
                TeleportCategory.values().joinToString(", ") { it.display })
            return
        }
        p.message("<col=801700>== ${cat.display} ==</col>")
        for (d in TeleportRegistry.inCategory(cat)) {
            val tag = if (d.isBuilt) "" else " <col=7f7f7f>[Coming Soon]</col>"
            p.message("${d.displayName} — <col=801700>${d.key}</col> — ${d.dangerText}$tag")
        }
    }
}
