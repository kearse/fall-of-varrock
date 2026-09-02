package org.alter.plugins.content.quests.framework

import org.alter.game.model.entity.Player

/**
 * A framework quest: a key, a display name, prerequisites, an ordered list of [QuestStep]s and
 * completion rewards. Subclass as an `object` and register it with [QuestRegistry.register];
 * [QuestEngine] does the rest (state, advancing, rewards, journal publish, arrows, nudges).
 *
 * - [chainIndex]: the client Quest Journal's chain slot (must match `LofQuest.CHAIN`); null = not
 *   shown in the client journal (Block 1 claims none — the additive `LofQuest` entry lands with
 *   the first Block-2 quest).
 * - [journalVarp]: a varp to publish the generic packed state to (step index+1 | progress<<8 |
 *   state<<20). Claim it in docs/overlay-design-system.md §8 first. Null = not published.
 * - [adminOnly]: hidden from `::quests` focus and never auto-begun — only `::questdebug`/`::demoquest`.
 * - [optional]: a side road — `QuestRegistry.activeChainIndex` never points a player at it as
 *   "next up" while a main-road quest is unstarted.
 */
abstract class QuestDefinition(
    val key: String,
    val displayName: String,
    val chainIndex: Int? = null,
    val journalVarp: Int? = null,
    val adminOnly: Boolean = false,
    val optional: Boolean = false,
) {
    open val prerequisites: List<Prerequisite> = emptyList()
    abstract val steps: List<QuestStep>
    open val completionRewards: List<Reward> = emptyList()

    /** Begin automatically (login / rank-up) the moment the prerequisites are met. */
    open val autoBegin: Boolean = false

    /** Draw the server guidance arrow to the current step's anchor ([QuestArrows]). */
    open val serverArrow: Boolean = true

    open val completionMessage: String? = null

    open fun onComplete(p: Player) {}

    fun step(id: String): QuestStep? = steps.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = steps.indexOfFirst { it.id == id }

    /**
     * Dialogue sugar: register an [NpcTalk] branch on [npcKey] that claims the conversation only
     * while the player is on [stepId] of THIS quest. The script typically narrates and then calls
     * `QuestEngine.satisfy(p, this, stepId)`.
     */
    protected fun talk(npcKey: String, stepId: String, priority: Int = NpcTalk.PRIORITY_QUEST, script: TalkScript) {
        NpcTalk.register(npcKey, priority) { p -> if (QuestEngine.stepId(p, this) == stepId) script else null }
    }
}
