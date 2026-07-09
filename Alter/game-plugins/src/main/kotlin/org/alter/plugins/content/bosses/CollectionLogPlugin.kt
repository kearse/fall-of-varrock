package org.alter.plugins.content.bosses

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.panel.TabbedPanel

/**
 * Collection Log access. Two views off [CollectionLogRegistry]:
 *  - `::cl`   — a categorized chatbox readout (always works; the reliable fallback + data check).
 *  - `::clog` — opens the native cache interface 621 (the real OSRS Collection Log frame). This
 *    is the in-progress visual path; it's verified/tuned against a live client.
 *
 * Recording into the log happens in each boss's loot code (KBD/wilderness `DropTable` `log=true`,
 * Corp Beast `BossLoot.grantUnique`) via [CollectionLog.record].
 */
class CollectionLogPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("cl", description = "View your Collection Log (text)") {
            val p = player
            val have = CollectionLog.ids(p)
            p.message("<col=801700>--- Collection Log --- ${CollectionLogRegistry.overallObtained(p)}/${CollectionLogRegistry.overallTotal()} unlocked ---</col>")
            CollectionLogRegistry.categories.forEach { cat ->
                val ids = CollectionLogRegistry.itemIds(cat)
                if (ids.isEmpty()) return@forEach
                val got = ids.count { it in have }
                p.message("<col=ffae00>${cat.displayName}</col>  <col=ffffff>($got/${ids.size})</col>")
                ids.forEach { id ->
                    val name = runCatching { getItem(id).name }.getOrNull() ?: "item $id"
                    if (id in have) {
                        p.message("&nbsp;&nbsp;<col=00ff00>$name</col>")
                    } else {
                        p.message("&nbsp;&nbsp;<col=666666>$name</col>")
                    }
                }
            }
        }

        onCommand("clog", description = "Open the Collection Log panel") {
            val p = player
            // One tab per boss category; rows = its loggable items (obtained green / missing grey).
            val tabs = CollectionLogRegistry.categories.map { cat ->
                TabbedPanel.PanelTab(cat.displayName) { viewer ->
                    val have = CollectionLog.ids(viewer)
                    CollectionLogRegistry.itemIds(cat).map { id ->
                        val name = runCatching { getItem(id).name }.getOrNull() ?: "item $id"
                        val got = id in have
                        TabbedPanel.PanelRow(
                            columns = listOf(
                                if (got) "<col=00ff00>$name</col>" else "<col=666666>$name</col>",
                                if (got) "<col=00ff00>have</col>" else "<col=666666>-</col>",
                                "",
                            ),
                            iconItem = null, // DIAGNOSTIC: icons off — testing if item-icon draw is the crash
                        )
                    }
                }
            }
            val title = "Collection Log  ${CollectionLogRegistry.overallObtained(p)}/${CollectionLogRegistry.overallTotal()}"
            TabbedPanel.open(p, TabbedPanel.PanelSpec(title, tabs, headers = listOf("Item", "Status", "")))
        }
    }
}
