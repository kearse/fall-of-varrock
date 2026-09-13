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
            // Players who finished The Last Free City (the Recruit Trials chain) without visiting
            // Vannaka afterwards — or before this chain existed — never got started. POINT them at
            // him; don't start the chain for them. Beginning it here dropped 28 quest-locked dragon
            // bones into the pack of someone who had never been told War-Prep existed, which reads
            // as a glitch rather than a gift (operator, 2026-09-13). Vannaka starts the quest the
            // moment they talk to him (SlayerPlugin's war-prep intro), bones explained in hand.
            if (RecruitTrials.step(player) == RecruitTrials.Step.DONE && !WarPrepChain.started(player)) {
                WarPrepChain.remindToStart(player)
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
                // NONE covers two very different players: one who hasn't finished The Last Free City
                // yet, and one who has but never went back to Vannaka. Telling the latter to "finish
                // The Last Free City first" is a dead end — send them to the man with the orders.
                if (RecruitTrials.step(player) == RecruitTrials.Step.DONE) {
                    WarPrepChain.remindToStart(player)
                } else {
                    player.message("<col=801700>War-Prep:</col> finish The Last Free City first.")
                }
            } else if (s == WarPrepChain.Step.DONE) {
                player.message("<col=801700>War-Prep:</col> ${s.objective}")
            } else {
                player.message("<col=801700>War-Prep — current objective:</col> ${WarPrepChain.objectiveLine(player)}")
            }
        }
    }
}
