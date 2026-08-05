package org.alter.plugins.content.quests

import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player

/**
 * Server half of the client-drawn **Quest Journal** window (`lofquests`' `LofQuestBookOverlay` in
 * the custom client — the Feudal-Ranks-style progression screen: focused quest + story/tasks/
 * rewards + the whole chain as a node track).
 *
 * Same thin varp-pulse pattern as [org.alter.plugins.content.war.RankMenu] /
 * [org.alter.plugins.content.teleport.TeleportClientMenu]: [open] pulses [OPEN_VARP] carrying the
 * quest to focus (its chain index + 1); the client opens on the rising edge, then the pulse-to-0
 * so a persisted varp can't re-open the window on login. The client reads all quest state from the
 * quest varps it already consumes — this only says "open, focused here".
 *
 * The chain-index constants MUST match the client's `LofQuest.CHAIN` order (the real, built quests
 * in quest-line order). Callers: [QuestBookPlugin] (native tab click + `::quests`) and the per-quest
 * status commands, which each open focused on their own quest.
 */
object QuestBook {

    /** Overlay-open varp (docs/overlay-design-system.md §8). Value = focused chain index + 1; 0 = closed. */
    const val OPEN_VARP = 4645

    // Chain indices — must match client LofQuest.CHAIN order.
    const val RECRUIT_TRIALS = 0
    const val WARPREP_MAGIC = 1
    const val ROGUE_HUNTING_I = 2
    const val ROGUE_HUNTING_II = 3
    const val WARPREP_RANGED = 4
    const val WARPREP_SURVIVAL = 5
    const val KING = 6

    private const val LAST_INDEX = KING

    /** Pulse the open signal, focused on [chainIndex]. */
    fun open(p: Player, chainIndex: Int) {
        val idx = chainIndex.coerceIn(0, LAST_INDEX)
        p.setVarp(OPEN_VARP, idx + 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}
