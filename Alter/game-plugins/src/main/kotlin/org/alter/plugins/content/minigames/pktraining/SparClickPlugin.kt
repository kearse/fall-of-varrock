package org.alter.plugins.content.minigames.pktraining

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles a companion-sparring settings interaction requested by the client overlay
 * (net.runelite.client.plugins.lofspar). The client sends `::lofspar <action>` as public chat;
 * `MessagePublicHandler` routes it here as the `sparclick` command:
 *   - `f<i>`  → fight style i ([SparStyle] ordinal)
 *   - `d<i>`  → difficulty i ([SparDifficulty] ordinal)
 *   - `t<i>`  → toggle rule i (0..8)
 *   - `m<i>`  → companion loadout mode i ([CompanionKitMode]; 2 = Custom opens the kit locker)
 *   - `load`  → load the last-used setup
 *   - `start` → begin the bout
 *   - `x`     → decline / close
 *
 * Also testable directly by typing `::sparclick t3` etc. in chat.
 */
class SparClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("sparclick", description = "Companion sparring settings interaction (client overlay channel)") {
            act(player, player.getCommandArgs().getOrNull(0))
        }
    }

    private fun act(p: Player, action: String?) {
        if (action == null || !SparringClientMenu.isOpen(p)) return
        when {
            action == "start" -> SparringClientMenu.start(p)
            action == "x" -> SparringClientMenu.cancel(p)
            action == "load" -> SparringClientMenu.loadLast(p)
            action.startsWith("f") -> action.drop(1).toIntOrNull()?.let { SparringClientMenu.setStyle(p, it) }
            action.startsWith("d") -> action.drop(1).toIntOrNull()?.let { SparringClientMenu.setDiff(p, it) }
            action.startsWith("t") -> action.drop(1).toIntOrNull()?.let { SparringClientMenu.toggleRule(p, it) }
            action.startsWith("m") -> action.drop(1).toIntOrNull()?.let { SparringClientMenu.setKitMode(p, it) }
        }
    }
}
