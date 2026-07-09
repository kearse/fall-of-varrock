package org.alter.plugins.content.interfaces.panel

import org.alter.api.InterfaceDestination
import org.alter.api.ext.closeInterface
import org.alter.api.ext.openInterface
import org.alter.api.ext.setComponentHidden
import org.alter.api.ext.setComponentItem
import org.alter.api.ext.setComponentText
import org.alter.api.ext.setInterfaceUnderlay
import org.alter.game.model.entity.Player

/**
 * A **reusable tabbed-panel framework** — one custom cache interface (group [IFACE], authored by
 * `org.alter.tools.panel.PanelCacheTool`) any feature drives with a [PanelSpec]: a title, a column
 * of bordered tab buttons, and per-tab rows of an optional item icon + up to [COLS] text columns.
 *
 * Each tab button = a border rect + fill rect + a hidden selected-highlight rect + label + a hit
 * layer. Each row = an item-icon cell (type-5, filled by setComponentItem) + text cells + a hit
 * layer (the clickable type-0+op1 shape). State is in-memory per player. Ids MUST match PanelCacheTool.
 */
object TabbedPanel {
    const val IFACE = 1011
    const val CLOSE_BTN = 7         // close button (clicks handled via CLOSE_HIT layer)
    const val TITLE_TXT = 6
    const val HDR_BASE = 9          // 9,10,11 column headers
    const val TAB_LIGHT_BASE = 12   // 12..20 bevel light edge
    const val TAB_DARK_BASE = 21    // 21..29 bevel dark edge
    const val TAB_FILL_BASE = 30    // 30..38 button fill
    const val TAB_SEL_BASE = 39     // 39..47 selected fill (highlight)
    const val TAB_TXT_BASE = 48     // 48..56
    const val TAB_COUNT = 9
    const val ROW_ICON_BASE = 57    // 57..68 item-icon cells
    const val ROW_TXT_BASE = 69     // 69..104 row cells
    const val ROWS = 12
    const val COLS = 3
    const val CLOSE_HIT = 105
    const val TAB_HIT_BASE = 106    // 106..114
    const val ROW_HIT_BASE = 115    // 115..126
    private const val POPULATE_DELAY = 2 // ticks to wait after open before filling (JS5 load race)

    class PanelRow(val columns: List<String>, val iconItem: Int? = null, val onClick: ((Player) -> Unit)? = null)
    class PanelTab(val label: String, val rows: (Player) -> List<PanelRow>)
    class PanelSpec(val title: String, val tabs: List<PanelTab>, val headers: List<String>? = null)

    private class Session(val spec: PanelSpec, var tab: Int = 0, var visibleRows: List<PanelRow> = emptyList())

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    fun open(p: Player, spec: PanelSpec) {
        if (spec.tabs.isEmpty()) return
        sessions[p.index] = Session(spec)
        p.setInterfaceUnderlay(color = -1, transparency = -1)
        p.openInterface(IFACE, InterfaceDestination.MAIN_SCREEN)
        // Custom interface loads ASYNC over JS5; populating same-tick races the load → NPE/logout.
        // Wait a few ticks on the WORLD queue (player queue is paused while a modal is open).
        p.world.queue {
            wait(POPULATE_DELAY)
            if (p.index < 0 || sessions[p.index]?.spec !== spec) return@queue
            populate(p, spec)
        }
    }

    private fun populate(p: Player, spec: PanelSpec) {
        p.setComponentText(IFACE, TITLE_TXT, spec.title)
        for (c in 0 until COLS) p.setComponentText(IFACE, HDR_BASE + c, spec.headers?.getOrNull(c) ?: "")
        selectTab(p, 0)
    }

    fun selectTab(p: Player, tabIndex: Int) {
        val s = sessions[p.index] ?: return
        val tab = tabIndex.coerceIn(0, s.spec.tabs.size - 1)
        s.tab = tab

        for (i in 0 until TAB_COUNT) {
            val t = s.spec.tabs.getOrNull(i)
            val defined = t != null
            p.setComponentHidden(IFACE, TAB_LIGHT_BASE + i, !defined)
            p.setComponentHidden(IFACE, TAB_DARK_BASE + i, !defined)
            p.setComponentHidden(IFACE, TAB_FILL_BASE + i, !defined)
            p.setComponentHidden(IFACE, TAB_HIT_BASE + i, !defined)
            p.setComponentHidden(IFACE, TAB_SEL_BASE + i, !(defined && i == tab))
            if (defined) {
                val col = if (i == tab) "ffff64" else "d8d0c0"
                p.setComponentText(IFACE, TAB_TXT_BASE + i, "<col=$col>${t!!.label}</col>")
            } else {
                p.setComponentText(IFACE, TAB_TXT_BASE + i, "")
            }
        }

        val rows = s.spec.tabs[tab].rows(p)
        s.visibleRows = rows
        for (i in 0 until ROWS) {
            val row = rows.getOrNull(i)
            val txtBase = ROW_TXT_BASE + i * COLS
            val iconComp = ROW_ICON_BASE + i
            if (row == null) {
                p.setComponentHidden(IFACE, iconComp, true)
                for (c in 0 until COLS) p.setComponentHidden(IFACE, txtBase + c, true)
                p.setComponentHidden(IFACE, ROW_HIT_BASE + i, true)
            } else {
                if (row.iconItem != null) {
                    p.setComponentItem(IFACE, iconComp, row.iconItem, 1)
                    p.setComponentHidden(IFACE, iconComp, false)
                } else {
                    p.setComponentHidden(IFACE, iconComp, true)
                }
                for (c in 0 until COLS) {
                    p.setComponentHidden(IFACE, txtBase + c, false)
                    p.setComponentText(IFACE, txtBase + c, row.columns.getOrElse(c) { "" })
                }
                p.setComponentHidden(IFACE, ROW_HIT_BASE + i, false)
            }
        }
    }

    fun clickRow(p: Player, rowIndex: Int) {
        val s = sessions[p.index] ?: return
        s.visibleRows.getOrNull(rowIndex)?.onClick?.invoke(p)
    }

    fun close(p: Player) {
        sessions.remove(p.index)
        p.closeInterface(IFACE)
    }

    fun onClosed(p: Player) {
        sessions.remove(p.index)
    }
}
