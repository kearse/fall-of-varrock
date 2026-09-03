package org.alter.plugins.content.bosses

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * `::kc` — prints the player's [BossKills] ledger, registered bosses first (zero shown), then
 * any unregistered keys the ledger happens to hold.
 */
class BossKillsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("kc", description = "Show your boss kill counts") {
            val counts = BossKills.all(player)
            val keys = BossKills.registeredKeys() + counts.keys.filter { it !in BossKills.registeredKeys() }
            if (keys.isEmpty()) {
                player.message("You haven't killed any bosses yet.")
                return@onCommand
            }
            player.message("<col=801700>Boss kill counts:</col>")
            keys.forEach { key ->
                val n = counts[key] ?: 0
                if (n > 0 || key in BossKills.registeredKeys()) {
                    player.message("${BossKills.displayName(key)}: <col=0000ff>$n</col>")
                }
            }
        }
    }
}
