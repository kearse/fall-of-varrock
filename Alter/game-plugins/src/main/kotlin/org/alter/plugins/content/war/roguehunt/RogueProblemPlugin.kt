package org.alter.plugins.content.war.roguehunt

import dev.openrune.cache.CacheManager.getNpc
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Wiring for [RogueProblem] (the Act II "Rogue Problem" quest). Resumes the per-player state on
 * login, drives the poll timer, counts quest-scoped rogue kills on the additive death list (cheap —
 * bails instantly for non-rogue kills, mirroring [RogueHuntPlugin]), and serves `::rogueproblem`.
 *
 * The quest is *given* by the Recruiting Sergeant and its captain beat is reported by
 * `NamedCaptainsPlugin`; this plugin only owns the passive wiring. It auto-begins the moment the
 * player finishes the War-Prep chain (the Squire rank-up — see `DukeHoracioPlugin`), and the login
 * hook here catches anyone who finished War-Prep before this quest existed.
 */
class RogueProblemPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            // Legacy / interrupted: a player already past War-Prep never got the quest started.
            RogueProblem.begin(player)
            RogueProblem.resumeOnLogin(player)
        }

        onTimer(RogueProblem.TIMER) { RogueProblem.pollTick(player) }

        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (RogueProblem.step(killer) == RogueProblem.Step.HUNT && RogueHunt.isRogue(npcName(npc.id))) {
                RogueProblem.onRogueKill(killer)
            }
        }

        onCommand("rogueproblem", description = "Show your Rogue Problem quest objective") {
            player.message(RogueProblem.statusLine(player))
        }
    }

    private fun npcName(id: Int): String? =
        runCatching { getNpc(id).name }.getOrNull()
}
