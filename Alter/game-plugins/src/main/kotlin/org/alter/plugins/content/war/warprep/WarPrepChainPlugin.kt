package org.alter.plugins.content.war.warprep

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.recruit.RecruitTrials

/**
 * **War-Prep quest chain** wiring — resumes the per-player state on login and drives the poll timer
 * that watches skill milestones + refreshes the guidance arrow. The chain itself lives in
 * [WarPrepChain]; Vannaka (in `SlayerPlugin`) speaks the quest beats, and the Wizard Tower minigame
 * reports the grimoire pickup.
 */
class WarPrepChainPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            // Players who finished the Recruit Trials before this chain existed never got started —
            // begin it for them now so nobody is stranded without a path to raids.
            if (RecruitTrials.step(player) == RecruitTrials.Step.DONE && !WarPrepChain.started(player)) {
                WarPrepChain.begin(player)
            }
            WarPrepChain.resumeOnLogin(player)
        }

        onTimer(WarPrepChain.TIMER) { WarPrepChain.pollTick(player) }

        onCommand("warprep", description = "Show your War-Prep objective") {
            val s = WarPrepChain.step(player)
            if (s == WarPrepChain.Step.NONE) {
                player.message("<col=801700>War-Prep:</col> finish the Recruit Trials first.")
            } else {
                player.message("<col=801700>War-Prep — current objective:</col> ${s.objective}")
            }
        }
    }
}
