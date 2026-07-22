package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Wiring for [Conquest] (the endgame "King of Lumbridge" quest). Resumes the per-player state on
 * login, drives the supply poll timer, and serves `::kingquest`.
 *
 * The quest is *opened* by the King rank-up ([RankPurchase.buy]), its launch beat is reported by
 * [CampaignCommandPlugin] and its win beat by [CampaignDirector]; this plugin only owns the passive
 * wiring. The login hook auto-begins it for anyone who reached King before this quest existed.
 */
class ConquestPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            // Legacy / interrupted: a player already crowned King never got the quest started.
            Conquest.begin(player)
            Conquest.resumeOnLogin(player)
        }

        onTimer(Conquest.TIMER) { Conquest.pollTick(player) }

        onCommand("kingquest", description = "Show your King of Lumbridge quest objective") {
            player.message(Conquest.statusLine(player))
        }
    }
}
