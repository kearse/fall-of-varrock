package org.alter.plugins.content.war.warprep

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

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

        // Quest-locked bones can't be dropped: a dropped stack becomes public loot — free bones
        // for a passer-by, and the "I'm out of bones" plea to Vannaka for the dropper. The other
        // sinks (bank, trade, GE, shops, looting bag) are sealed at their own chokepoints; see
        // [WarPrepChain.bonesLocked].
        for (key in arrayOf("item.dragon_bones", "item.dragon_bones_noted")) {
            canDropItem(key) {
                if (WarPrepChain.bonesLocked(player, getRSCM(key))) {
                    WarPrepChain.warnBonesLocked(player)
                    false
                } else {
                    true
                }
            }
        }

        onCommand("warprep", description = "Show your War-Prep objective") {
            val s = WarPrepChain.step(player)
            if (s == WarPrepChain.Step.NONE) {
                player.message("<col=801700>War-Prep:</col> finish the Recruit Trials first.")
            } else if (s == WarPrepChain.Step.DONE) {
                player.message("<col=801700>War-Prep:</col> ${s.objective}")
            } else {
                player.message("<col=801700>War-Prep — current objective:</col> ${WarPrepChain.objectiveLine(player)}")
            }
        }
    }
}
