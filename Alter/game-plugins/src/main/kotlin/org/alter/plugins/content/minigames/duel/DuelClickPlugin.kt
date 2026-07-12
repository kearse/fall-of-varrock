package org.alter.plugins.content.minigames.duel

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles a duel-rules interaction requested by the client overlay (net.runelite.client.plugins.lofduel).
 * The client sends `::duel <action>` as public chat; `MessagePublicHandler` routes it here as the
 * `duelclick` command:
 *   - `t<i>` → toggle rule i (0..11)
 *   - `s<i>` → toggle forbidden equipment slot i (0..10, the paper-doll)
 *   - `load` → load the "last duel" preset
 *   - `a`    → accept
 *   - `d`    → decline / close
 *
 * Also testable directly by typing `::duelclick t3` etc. in chat.
 */
class DuelClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("duelclick", description = "Duel rules interaction (client overlay channel)") {
            act(player, player.getCommandArgs().getOrNull(0))
        }
    }

    private fun act(p: Player, action: String?) {
        if (action == null || !DuelRulesClientMenu.isOpen(p)) return
        when {
            action == "a" -> DuelRulesClientMenu.accept(p)
            action == "d" -> DuelRulesClientMenu.cancel(p)
            action == "load" -> DuelRulesClientMenu.loadPreset(p)
            action.startsWith("t") -> action.drop(1).toIntOrNull()?.let { DuelRulesClientMenu.toggle(p, it) }
            action.startsWith("s") -> action.drop(1).toIntOrNull()?.let { DuelRulesClientMenu.toggleSlot(p, it) }
        }
    }
}
