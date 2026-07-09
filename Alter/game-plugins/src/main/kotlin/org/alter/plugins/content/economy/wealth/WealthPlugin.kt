package org.alter.plugins.content.economy.wealth

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Wealth hiscores** (Phase 6 economy: a flex/progression goal that gives gp a purpose).
 *
 * Total wealth = the cache value (`getItem().cost`, the same basis the rest of the economy uses)
 * of everything in a player's inventory, worn equipment, and bank. `::wealth` shows your own
 * breakdown; `::wealthtop` ranks everyone currently online. Computed live (no persistence needed
 * for the MVP). A persistent account-wide hiscore that ranks offline players too is a future
 * extension (it needs scanning the save files).
 */
class WealthPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("wealth", description = "Show your total wealth") {
            val inv = value(player.inventory)
            val equip = value(player.equipment)
            val bank = value(player.bank)
            player.message("--- Your wealth ---")
            player.message("Inventory: <col=801700>${fmt(inv)}</col>")
            player.message("Equipment: <col=801700>${fmt(equip)}</col>")
            player.message("Bank: <col=801700>${fmt(bank)}</col>")
            player.message("Total: <col=801700>${fmt(inv + equip + bank)}</col> coins")
        }

        onCommand("wealthtop", description = "Wealth hiscores (online players)") {
            val ranked = ArrayList<Pair<String, Long>>()
            world.players.forEach { p -> ranked.add(p.username to totalWealth(p)) }
            ranked.sortByDescending { it.second }
            player.message("--- Wealth hiscores (online) ---")
            ranked.take(TOP_N).forEachIndexed { i, (name, total) ->
                player.message("${i + 1}. $name: <col=801700>${fmt(total)}</col>")
            }
        }
    }

    private fun totalWealth(p: Player): Long = value(p.inventory) + value(p.equipment) + value(p.bank)

    private fun value(c: ItemContainer): Long {
        var total = 0L
        for (i in 0 until c.capacity) {
            val item = c[i] ?: continue
            total += getItem(item.id).cost.toLong() * item.amount.toLong()
        }
        return total
    }

    private fun fmt(v: Long): String = "%,d".format(v)

    private companion object {
        const val TOP_N = 10
    }
}
