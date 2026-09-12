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

    /** Overlay-open varp (docs/overlay-design-system.md §8). Value = focused chain index + 1; 0 = closed.
     *  Was 4645 — that id sits inside the kit editor's 4640-4679 slot block, so publishing a kit
     *  whose chest slot was filled pulsed this open (::kits popped the quest journal). */
    const val OPEN_VARP = 4683

    // Chain indices — must match client LofQuest.CHAIN order (= the enum's declaration order,
    // FUTURE teasers excluded) AND the native quest tab's row order (QuestTablePatch.PLAN sort
    // names). The legacy hallway keeps 0-6; framework main-story quests are APPENDED in story
    // order (The North 7, then First Reclamation, A Kingdom Alone…) — each quest adds only its
    // own constant and the last one to land bumps LAST_INDEX.
    const val RECRUIT_TRIALS = 0   // The Last Free City (Main Story Quest 1)
    const val WARPREP_MAGIC = 1
    const val ROGUE_HUNTING_I = 2
    const val ROGUE_HUNTING_II = 3
    const val WARPREP_RANGED = 4
    const val WARPREP_SURVIVAL = 5
    const val KING = 6
    const val THE_NORTH = 7         // The North (Main Story Quest 3)
    const val FIRST_RECLAMATION = 8 // First Reclamation (Main Story Quest 4)
    // 9 A Kingdom Alone, 10-13 BREACH / SECURE / UNDERSTAND / SUSTAIN, 14 At the White Wall — their own PRs.
    const val A_MATTER_OF_TROLLS = 15 // Asgarnia — BREACH, quest 2 (the Northern Front)

    const val LAST_INDEX = A_MATTER_OF_TROLLS

    /** Pulse the open signal, focused on [chainIndex]. */
    fun open(p: Player, chainIndex: Int) {
        val idx = chainIndex.coerceIn(0, LAST_INDEX)
        p.setVarp(OPEN_VARP, idx + 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}
