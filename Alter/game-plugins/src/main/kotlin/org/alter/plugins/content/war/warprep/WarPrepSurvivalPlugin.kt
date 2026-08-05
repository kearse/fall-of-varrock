package org.alter.plugins.content.war.warprep

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook

/**
 * Wiring for [WarPrepSurvival] (the "War-Prep III — Survival" quest). Resumes/begins the per-player
 * state on login, drives the poll timer (which watches the Hitpoints milestone and the Fight Cave
 * hiscore for the FIELD test), and serves `::warpsurvival`.
 *
 * The quest is *given* by General Zo (see `GeneralZoPlugin`), who drills the soldier, hands the
 * survival kit and debriefs; this plugin only owns the passive wiring. It auto-begins the moment the
 * player finishes War-Prep II (the Lord rank-up — see `RankPurchase`), and the login hook here catches
 * anyone who reached Lord before this quest existed.
 */
class WarPrepSurvivalPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            WarPrepSurvival.begin(player)
            WarPrepSurvival.resumeOnLogin(player)
        }

        onTimer(WarPrepSurvival.TIMER) { WarPrepSurvival.pollTick(player) }

        onCommand("warpsurvival", description = "Open War-Prep III (Survival) in the Quest Journal") {
            player.message(WarPrepSurvival.statusLine(player))
            QuestBook.open(player, QuestBook.WARPREP_SURVIVAL)
        }
    }
}
