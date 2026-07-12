package org.alter.plugins.content.minigames.duel

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.trading.getTradeSession

/**
 * Handles a stake-screen interaction requested by the themed client overlay
 * (net.runelite.client.plugins.lofstake). The client sends `::stake <action> [slot]` as public chat;
 * `MessagePublicHandler` routes it here as the `stakeclick` command:
 *   - `rm <slot>` → un-stake the item in that offer slot
 *   - `a`         → accept (progress)
 *   - `d`         → decline
 *
 * Every action is forwarded to the player's existing **secure [TradeSession]** — no new item logic,
 * no escrow changes. Adding items stays native (clicking the real inventory). Guarded to stake
 * sessions only, so it can never touch a normal trade.
 */
class StakeClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("stakeclick", description = "Stake overlay interaction (client overlay channel)") {
            val args = player.getCommandArgs()
            act(player, args.getOrNull(0), args.getOrNull(1)?.toIntOrNull())
        }
    }

    private fun act(p: Player, action: String?, slot: Int?) {
        val session = p.getTradeSession() ?: return
        if (!session.isStake) return
        when (action) {
            "a" -> session.progress(true)
            "d" -> session.decline()
            "rm" -> if (slot != null && slot >= 0) session.remove(slot, Int.MAX_VALUE)
        }
    }
}
