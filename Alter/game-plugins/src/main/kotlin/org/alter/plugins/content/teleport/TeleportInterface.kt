package org.alter.plugins.content.teleport

import org.alter.api.InterfaceDestination
import org.alter.api.ext.InterfaceEvent
import org.alter.api.ext.closeInterface
import org.alter.api.ext.openInterface
import org.alter.api.ext.setComponentHidden
import org.alter.api.ext.setComponentText
import org.alter.api.ext.setInterfaceEvents
import org.alter.api.ext.setInterfaceUnderlay
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/** Transient (session-only) — which tab the player currently has open. */
private val TELE_TAB_ATTR = AttributeKey<Int>()

/**
 * Server-side driver for the custom tabbed teleport interface (cache group
 * [IFACE], authored by `org.alter.tools.teleport.TeleportInterfaceCacheTool`).
 *
 * Component ids MUST match that tool. The interface itself is static geometry; the
 * server fills tab labels + rows via `IfSetText`/`IfSetHide` and handles clicks
 * (wired in [TeleportInterfacePlugin]). Phase 2a = the nine category tabs + clickable
 * rows that teleport; dynamics (Popular/Recent panels, timers, icons) come next.
 */
object TeleportInterface {
    const val IFACE = 1101 // must match TeleportInterfaceCacheTool.TELE_IFACE
    const val CLOSE_BTN = 4
    const val TAB_BASE = 20
    const val TAB_COUNT = 9
    const val ROW_BASE = 100
    const val ROWS = 22

    private val categories = TeleportCategory.values()

    /** name component for visible row [i] (price = +1, danger = +2). */
    fun rowName(i: Int) = ROW_BASE + i * 3

    fun open(p: Player) {
        // ISOLATION TEST (Phase 2a debug): open the authored window ONLY — no IfSetEvents,
        // no IfSetText/Hide — to learn whether the client crash is the interface itself or a
        // follow-up packet. Restore the events + selectTab() block once the window renders.
        p.setInterfaceUnderlay(color = -1, transparency = -1)
        p.openInterface(IFACE, InterfaceDestination.MAIN_SCREEN)
    }

    fun selectTab(p: Player, tabIndex: Int) {
        val tab = tabIndex.coerceIn(0, TAB_COUNT - 1)
        p.attr[TELE_TAB_ATTR] = tab

        // Tab labels — highlight the active one.
        categories.forEachIndexed { i, c ->
            val col = if (i == tab) "ffffff" else "c8c8c8"
            p.setComponentText(IFACE, TAB_BASE + i, "<col=$col>${c.display}</col>")
        }

        // Rows for the selected category.
        val list = TeleportRegistry.inCategory(categories[tab])
        for (i in 0 until ROWS) {
            val name = rowName(i)
            val d = list.getOrNull(i)
            if (d == null) {
                p.setComponentHidden(IFACE, name, true)
                p.setComponentHidden(IFACE, name + 1, true)
                p.setComponentHidden(IFACE, name + 2, true)
            } else {
                p.setComponentHidden(IFACE, name, false)
                p.setComponentHidden(IFACE, name + 1, false)
                p.setComponentHidden(IFACE, name + 2, false)
                val label = if (d.isBuilt) d.displayName else "<col=7f7f7f>${d.displayName}</col>"
                p.setComponentText(IFACE, name, label)
                p.setComponentText(IFACE, name + 1, if (d.isBuilt) d.priceText else "<col=7f7f7f>Soon</col>")
                p.setComponentText(IFACE, name + 2, dangerText(d))
            }
        }
    }

    fun clickRow(p: Player, i: Int) {
        val tab = p.attr[TELE_TAB_ATTR] ?: 0
        val d = TeleportRegistry.inCategory(categories[tab]).getOrNull(i) ?: return
        if (TeleportService.teleport(p, d)) p.closeInterface(IFACE) // close only on a real teleport
    }

    fun close(p: Player) = p.closeInterface(IFACE)

    private fun dangerText(d: TeleportDestination): String {
        val col = when {
            d.danger.safe -> "00c000"
            d.danger == DangerTag.WILD -> "ff4040"
            else -> "ff8000" // Hostile
        }
        return "<col=$col>${d.dangerText}</col>"
    }
}
