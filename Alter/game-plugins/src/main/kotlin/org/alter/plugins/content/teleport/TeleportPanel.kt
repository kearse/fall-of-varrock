package org.alter.plugins.content.teleport

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.plugins.content.interfaces.panel.TabbedPanel

/**
 * The teleport portal rendered on the **reusable [TabbedPanel]** (the same server-driven panel
 * the Collection Log uses) — so the portal and the clog share one navigable UI: a column of
 * **category tabs**, and per-category **clickable destination rows** (name · danger · go).
 *
 * Clicking a built destination teleports + closes; a coming-soon row just messages. Categories
 * that overflow the panel's [TabbedPanel.ROWS] (the Bosses tab) **paginate**: the last row turns
 * the page (per-player, per-category page state), re-rendering the tab in place.
 */
object TeleportPanel {

    private const val TITLE = "Teleport Portal"
    private val headers = listOf("Destination", "Danger", "")

    /** Per-(player, category) page index for overflowing tabs. */
    private val pages = HashMap<Int, Int>()

    private fun pageKey(p: Player, cat: TeleportCategory) = p.index * 16 + cat.ordinal

    fun open(p: Player) {
        // Reset paging each open.
        TeleportCategory.values().forEach { pages.remove(pageKey(p, it)) }

        val tabs = TeleportCategory.values().map { cat ->
            TabbedPanel.PanelTab(cat.display) { viewer -> rowsFor(viewer, cat) }
        }
        TabbedPanel.open(p, TabbedPanel.PanelSpec(TITLE, tabs, headers))
    }

    private fun rowsFor(viewer: Player, cat: TeleportCategory): List<TabbedPanel.PanelRow> {
        val dests = TeleportRegistry.inCategory(cat)
        if (dests.isEmpty()) {
            return listOf(TabbedPanel.PanelRow(listOf("<col=666666>Nothing here yet.</col>", "", "")))
        }
        // Fits in one page → no pager.
        if (dests.size <= TabbedPanel.ROWS) {
            return dests.map { destRow(it) }
        }

        // Overflow → paginate, reserving the last row for the page turner.
        val perPage = TabbedPanel.ROWS - 1
        val maxPage = (dests.size - 1) / perPage
        val page = pages.getOrDefault(pageKey(viewer, cat), 0).coerceIn(0, maxPage)
        val rows = dests.drop(page * perPage).take(perPage).map { destRow(it) }.toMutableList()
        rows += TabbedPanel.PanelRow(
            listOf("<col=ffff00>▼ Page ${page + 1}/${maxPage + 1} — click to turn</col>", "", ""),
        ) { vp ->
            pages[pageKey(vp, cat)] = if (page >= maxPage) 0 else page + 1
            TabbedPanel.selectTab(vp, cat.ordinal) // re-render this tab with the new page
        }
        return rows
    }

    private fun destRow(d: TeleportDestination): TabbedPanel.PanelRow {
        val name = if (d.isBuilt) d.displayName else "<col=7f7f7f>${d.displayName}</col>"
        val dangerCol = if (d.danger.safe) "00ff00" else "ff5050"
        val danger = "<col=$dangerCol>${d.dangerText}</col>"
        val action = if (d.isBuilt) "<col=00ff00>Teleport</col>" else "<col=7f7f7f>Soon</col>"
        return TabbedPanel.PanelRow(listOf(name, danger, action)) { vp ->
            if (d.isBuilt) {
                TabbedPanel.close(vp)
                TeleportService.teleport(vp, d)
            } else {
                vp.message("<col=7f7f7f>${d.displayName} is coming soon.</col>")
            }
        }
    }
}
