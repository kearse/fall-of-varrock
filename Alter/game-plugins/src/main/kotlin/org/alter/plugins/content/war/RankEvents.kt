package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.plugins.content.war.warprep.WarPrepSurvival

private val logger = KotlinLogging.logger {}

/**
 * **Rank-bought observers** — the one place systems subscribe to "this player was just raised
 * to [Title]". Replaces the eight hard-coded quest callbacks [RankPurchase.buy] used to carry:
 * a quest (or Block-2 framework) registers a listener instead of editing the purchase.
 *
 * Listeners run in descending [priority] order (ties: registration order), each isolated — one
 * throwing listener never blocks the rest. Registration is order-free at plugin construction;
 * nothing fires until a real purchase.
 */
object RankEvents {

    private class Listener(val priority: Int, val fn: (Player, Title) -> Unit)

    private val listeners = ArrayList<Listener>()

    fun onRankBought(priority: Int = 0, listener: (Player, Title) -> Unit) {
        listeners += Listener(priority, listener)
        listeners.sortByDescending { it.priority }
    }

    internal fun fire(p: Player, title: Title) {
        for (l in listeners.toList()) {
            runCatching { l.fn(p, title) }
                .onFailure { logger.error(it) { "Rank-bought listener failed for ${p.username} -> ${title.name}" } }
        }
    }
}

/**
 * The legacy quest chains' rank hooks, registered in the exact order [RankPurchase.buy] used to
 * call them (the order matters: War-Prep I's RANK step must close before War-Prep II's `begin`
 * reads `WarPrepChain.complete`). Installed once by [TitlePlugin]. The Rogue Problem is NOT here:
 * it is optional and only ever starts from the Recruiting Sergeant's offer.
 *
 * LEGACY: these chains are the pre-Block-2 onboarding hallway; the Block-2 quest framework
 * subscribes to [RankEvents] itself and these registrations retire with the chains.
 */
object LegacyRankHooks {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        RankEvents.onRankBought(800) { p, _ -> RecruitTrials.onBuyRank(p) }      // intro quest's RANK step
        RankEvents.onRankBought(700) { p, _ -> WarPrepChain.onRankBought(p) }    // War-Prep I RANK step
        RankEvents.onRankBought(500) { p, _ -> WarPrepRanged.begin(p) }          // War-Prep II opens once War-Prep I closes
        RankEvents.onRankBought(400) { p, _ -> WarPrepRanged.onRankBought(p) }   // War-Prep II closes at Lord
        RankEvents.onRankBought(300) { p, _ -> WarPrepSurvival.begin(p) }        // War-Prep III opens at Lord
        RankEvents.onRankBought(200) { p, _ -> WarPrepSurvival.onRankBought(p) } // War-Prep III closes at Minister
        RankEvents.onRankBought(100) { p, _ -> Conquest.begin(p) }               // King of Lumbridge opens at King
    }
}
