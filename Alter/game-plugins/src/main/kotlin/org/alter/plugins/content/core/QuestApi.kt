package org.alter.plugins.content.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestFollow
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.QuestStates

private val logger = KotlinLogging.logger {}

/**
 * **Quest state** for every team (design authority 03 §7, 06 §4). Three kinds of key share one
 * store and one read:
 *
 *  - a **framework quest** (`QuestRegistry.register(def)`): [record] drives the real engine —
 *    `advanceTo` a step id (its `onEnter`, arrow, journal), [COMPLETE] pays rewards + sets the
 *    done flag, [RESET] clears;
 *  - a **legacy chain** (`recruit_trials` … `king_of_lumbridge`): read-only here — its own step
 *    machine writes it;
 *  - an **unregistered key** — a story beat or regional flag recorded before its quest exists
 *    (`"story.arrav"`, `"kandarin.trade_route"`): a raw step string + complete flag in the same
 *    blob (`quest_states`), so it survives and is read back the same way once a definition lands.
 *
 * `recordQuestState(player, quest, state)` → [record]. State is a **step id string** (never an
 * ordinal), or the sentinels [COMPLETE] / [RESET].
 */
object QuestApi {

    const val COMPLETE = "complete"
    const val RESET = "reset"

    // ---- read ------------------------------------------------------------------------------------

    /** True if [questKey] (legacy, framework or raw) is complete for [p]. */
    fun isComplete(p: Player, questKey: String): Boolean =
        QuestRegistry.isComplete(p, key(questKey)) || QuestStates.of(p, key(questKey))?.complete == true

    fun isStarted(p: Player, questKey: String): Boolean {
        val k = key(questKey)
        QuestRegistry.byKey(k)?.let { return it.started(p) }
        return QuestStates.of(p, k)?.let { it.complete || it.step.isNotEmpty() } ?: false
    }

    /**
     * The current state of [questKey] for [p]: a framework quest's step id, [COMPLETE] when done,
     * `"started"` for a legacy chain in progress, the raw step for an unregistered key, or null
     * when not started.
     */
    fun state(p: Player, questKey: String): String? {
        val k = key(questKey)
        QuestRegistry.definition(k)?.let { def ->
            return when {
                QuestEngine.isComplete(p, def) -> COMPLETE
                else -> QuestEngine.stepId(p, def)
            }
        }
        QuestRegistry.byKey(k)?.let { chain ->
            return when {
                chain.complete(p) -> COMPLETE
                chain.started(p) -> "started"
                else -> null
            }
        }
        val raw = QuestStates.of(p, k) ?: return null
        return if (raw.complete) COMPLETE else raw.step.takeIf { it.isNotEmpty() }
    }

    /** The player-facing objective line ("Kill 5 goblins (2/5)" / "Complete." / "Not started."). */
    fun objectiveLine(p: Player, questKey: String): String? = QuestRegistry.byKey(key(questKey))?.objectiveLine(p)

    fun definition(questKey: String): QuestDefinition? = QuestRegistry.definition(key(questKey))

    // ---- write -----------------------------------------------------------------------------------

    /**
     * Record that [p]'s [questKey] is now at [state] (a step id, [COMPLETE] or [RESET]). Returns
     * false for a legacy chain (read-only), an unknown step id of a framework quest, or a blank key.
     */
    fun record(p: Player, questKey: String, state: String): Boolean {
        val k = key(questKey)
        if (k.isEmpty()) return false
        val s = state.trim()
        QuestRegistry.definition(k)?.let { def ->
            return when (s.lowercase()) {
                COMPLETE -> { if (!QuestEngine.isComplete(p, def)) QuestEngine.complete(p, def); true }
                RESET, "" -> { QuestEngine.reset(p, def); true }
                else -> QuestEngine.advanceTo(p, def, s)
            }
        }
        if (QuestRegistry.byKey(k) != null) {
            logger.warn { "QuestApi.record: '$k' is a legacy chain — its own step machine owns its state (ignored)" }
            return false
        }
        // Raw beat: no definition yet. Same blob, same shape, so a later QuestDefinition reads it.
        when (s.lowercase()) {
            RESET, "" -> {
                QuestStates.remove(p, k)
                Flags.clear(p, Flags.Known.QUEST_DONE_PREFIX + k + ".done")
                return true
            }
            COMPLETE -> {
                val st = QuestStates.getOrCreate(p, k)
                st.complete = true
                st.step = ""
                st.completedAt = System.currentTimeMillis()
                if (st.startedAt == 0L) st.startedAt = st.completedAt
                Flags.set(p, Flags.Known.QUEST_DONE_PREFIX + k + ".done")
            }
            else -> {
                val st = QuestStates.getOrCreate(p, k)
                st.step = s
                st.complete = false
                if (st.startedAt == 0L) st.startedAt = System.currentTimeMillis()
            }
        }
        QuestStates.save(p)
        return true
    }

    /** Begin a framework quest (prerequisites checked unless [force]). */
    fun begin(p: Player, questKey: String, force: Boolean = false): Boolean =
        definition(questKey)?.let { QuestEngine.begin(p, it, force) } ?: false

    /** An external event says the current step (or [stepId], if given) is done. */
    fun satisfy(p: Player, questKey: String, stepId: String? = null): Boolean =
        definition(questKey)?.let { QuestEngine.satisfy(p, it, stepId) } ?: false

    fun complete(p: Player, questKey: String): Boolean = record(p, questKey, COMPLETE)

    /** A per-quest counter ("kills", "supplies_delivered"): add [delta], returns the new value. */
    fun addCounter(p: Player, questKey: String, name: String, delta: Int = 1): Int? =
        definition(questKey)?.let { QuestEngine.addCounter(p, it, name, delta) }

    // ---- journal ---------------------------------------------------------------------------------

    /** The quest whose objective the guidance arrow follows (null = the deepest in-progress one). */
    fun followed(p: Player): String? = QuestFollow.followed(p)

    /** Point the guidance arrow at [questKey]'s objective. False if no such quest. */
    fun follow(p: Player, questKey: String): Boolean = QuestFollow.follow(p, key(questKey))

    fun unfollow(p: Player) = QuestFollow.clear(p)

    private fun key(questKey: String): String = questKey.trim().lowercase()
}
