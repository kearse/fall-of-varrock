package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Wires the [WarEffortBonus] daily system: re-applies the current day's bonus on login (xpRate and the
 * drop-bonus attr default to base on a fresh login, and the day may have rolled over), and exposes
 * `::warbonus` so players can see their daily progress toward the next tier.
 */
class WarEffortBonusPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { WarEffortBonus.applyOnLogin(player) }
        onCommand("warbonus", description = "Show your daily War Effort XP/drop bonus") {
            player.message(WarEffortBonus.status(player))
        }
    }
}
