package org.alter.plugins.content.quests.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getVarp
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags

private val logger = KotlinLogging.logger {}

/**
 * Drives every framework [QuestDefinition]: begin / advance / complete / reset, objective
 * evaluation (kills from the npc-death hook, areas/items/predicates from the poll), rewards,
 * completion flags (`quest.<key>.done` in [Flags]), the periodic objective nudge, and the generic
 * journal-varp publish. Pure state + messaging — [QuestFrameworkPlugin] owns the wiring.
 *
 * Mutate-before-narrate: every transition writes and saves state FIRST, then talks — a
 * `p.queue{}` dialogue can die on death/logout/attack, the state must not.
 */
object QuestEngine {

    private const val NUDGE_TICKS = 500 // ~5 minutes between reminders
    private const val POLL_TICKS = 3 // QuestFrameworkPlugin's poll cadence (nudge countdown unit)
    private const val KILLS = "kills"

    /** Session-only per-quest nudge countdown. */
    private val NUDGE_LEFT = AttributeKey<MutableMap<String, Int>>()

    // ---- state reads -------------------------------------------------------------------------

    fun state(p: Player, q: QuestDefinition): QuestState? = QuestStates.of(p, q.key)

    fun started(p: Player, q: QuestDefinition): Boolean = state(p, q)?.let { it.complete || it.step.isNotEmpty() } ?: false

    fun isComplete(p: Player, q: QuestDefinition): Boolean = state(p, q)?.complete == true

    /** The current step id, or null when not started / complete. */
    fun stepId(p: Player, q: QuestDefinition): String? = state(p, q)?.takeIf { !it.complete && it.step.isNotEmpty() }?.step

    fun step(p: Player, q: QuestDefinition): QuestStep? = stepId(p, q)?.let { q.step(it) }

    fun counter(p: Player, q: QuestDefinition, name: String = KILLS): Int = state(p, q)?.counters?.get(name) ?: 0

    fun unmet(p: Player, q: QuestDefinition): List<Prerequisite> = q.prerequisites.filter { !it.met(p) }

    fun canBegin(p: Player, q: QuestDefinition): Boolean = !started(p, q) && !q.adminOnly && unmet(p, q).isEmpty()

    /** "Kill 5 goblins (2/5)" — the current objective with live progress, or a status word. */
    fun objectiveLine(p: Player, q: QuestDefinition): String {
        val s = step(p, q) ?: return if (isComplete(p, q)) "Complete." else "Not started."
        val o = s.objective
        return if (o is Objective.KillNpcs) "${o.text} (${counter(p, q).coerceAtMost(o.count)}/${o.count})" else o.text
    }

    // ---- transitions --------------------------------------------------------------------------

    /** Begin [q] for [p]. [force] skips the prerequisites and the admin-only gate (debug). */
    fun begin(p: Player, q: QuestDefinition, force: Boolean = false): Boolean {
        if (started(p, q)) return false
        if (!force && (q.adminOnly || unmet(p, q).isNotEmpty())) return false
        val first = q.steps.firstOrNull() ?: return false
        val s = QuestStates.getOrCreate(p, q.key)
        s.step = first.id
        s.complete = false
        s.startedAt = System.currentTimeMillis()
        s.counters.clear()
        QuestStates.save(p)
        p.message("<col=801700>${q.displayName}</col> — begun.")
        enter(p, q, first)
        return true
    }

    fun beginIfEligible(p: Player, q: QuestDefinition) {
        if (q.autoBegin && canBegin(p, q)) begin(p, q)
    }

    /** Clear the current step (paying its rewards) and move to the next, or complete. */
    fun advance(p: Player, q: QuestDefinition) {
        val cur = step(p, q) ?: return
        val idx = q.indexOf(cur.id)
        cur.rewards.forEach { r -> runCatching { r.grant(p) }.onFailure { logger.error(it) { "Reward failed: ${q.key}/${cur.id}" } } }
        runCatching { cur.onLeave?.invoke(p) }.onFailure { logger.error(it) { "onLeave threw: ${q.key}/${cur.id}" } }
        val next = q.steps.getOrNull(idx + 1)
        if (next == null) {
            complete(p, q)
        } else {
            val s = QuestStates.getOrCreate(p, q.key)
            s.step = next.id
            QuestStates.save(p)
            enter(p, q, next)
        }
    }

    /** Jump to [stepId] (debug / branching). Runs the target step's onEnter; pays nothing. */
    fun advanceTo(p: Player, q: QuestDefinition, stepId: String): Boolean {
        val target = q.step(stepId) ?: return false
        val s = QuestStates.getOrCreate(p, q.key)
        s.step = target.id
        s.complete = false
        QuestStates.save(p)
        enter(p, q, target)
        return true
    }

    /**
     * An external event says the current step is done (a dialogue, a war result, a hand-in).
     * With [stepId] given, only fires while ON that step. Returns true if the quest advanced.
     */
    fun satisfy(p: Player, q: QuestDefinition, stepId: String? = null): Boolean {
        val cur = step(p, q) ?: return false
        if (stepId != null && cur.id != stepId) return false
        advance(p, q)
        return true
    }

    fun addCounter(p: Player, q: QuestDefinition, name: String = KILLS, delta: Int = 1): Int {
        val s = QuestStates.getOrCreate(p, q.key)
        val v = (s.counters[name] ?: 0) + delta
        s.counters[name] = v
        QuestStates.save(p)
        return v
    }

    fun complete(p: Player, q: QuestDefinition) {
        val s = QuestStates.getOrCreate(p, q.key)
        s.complete = true
        s.step = ""
        s.completedAt = System.currentTimeMillis()
        QuestStates.save(p)
        Flags.set(p, Flags.Known.QUEST_DONE_PREFIX + q.key + ".done")
        q.completionRewards.forEach { r -> runCatching { r.grant(p) }.onFailure { logger.error(it) { "Completion reward failed: ${q.key}" } } }
        p.message("<col=801700>${q.displayName}</col> — <col=4f9b4f>complete!</col>")
        if (q.completionRewards.isNotEmpty()) p.message("Reward: ${q.completionRewards.joinToString(", ") { it.description }}.")
        q.completionMessage?.let { p.message(it) }
        runCatching { q.onComplete(p) }.onFailure { logger.error(it) { "onComplete threw: ${q.key}" } }
        publish(p, q)
    }

    fun reset(p: Player, q: QuestDefinition) {
        QuestStates.remove(p, q.key)
        Flags.clear(p, Flags.Known.QUEST_DONE_PREFIX + q.key + ".done")
        publish(p, q)
    }

    private fun enter(p: Player, q: QuestDefinition, step: QuestStep) {
        QuestStates.getOrCreate(p, q.key).counters.remove(KILLS)
        QuestStates.save(p)
        runCatching { step.onEnter?.invoke(p) }.onFailure { logger.error(it) { "onEnter threw: ${q.key}/${step.id}" } }
        p.message("<col=801700>${q.displayName} — next objective:</col> ${step.objective.text}")
        step.nudge?.let { p.message(it) }
        resetNudge(p, q)
        publish(p, q)
    }

    // ---- evaluation ---------------------------------------------------------------------------

    /** The additive npc-death hook: count the kill for every quest whose current step wants it. */
    fun onNpcKilled(killer: Player, npc: Npc) {
        for (q in QuestRegistry.frameworkQuests()) {
            val cur = step(killer, q) ?: continue
            val o = cur.objective as? Objective.KillNpcs ?: continue
            if (!o.matches(killer, npc)) continue
            val n = addCounter(killer, q)
            if (n >= o.count) {
                advance(killer, q)
            } else if (o.count <= 5 || n == o.count / 2) {
                killer.message("<col=801700>${q.displayName}:</col> $n/${o.count}.")
            }
        }
    }

    /** The poll: areas, items, predicates; then the periodic nudge. */
    fun pollTick(p: Player) {
        for (q in QuestRegistry.frameworkQuests()) {
            val cur = step(p, q) ?: continue
            val done = when (val o = cur.objective) {
                is Objective.ReachArea -> o.area.contains(p.tile)
                is Objective.HaveItems -> o.has(p).also { if (it && o.consume) o.take(p) }
                is Objective.Predicate -> runCatching { o.test(p) }.getOrDefault(false)
                else -> false
            }
            if (done) { advance(p, q); continue }
            val left = (nudges(p)[q.key] ?: NUDGE_TICKS) - POLL_TICKS
            if (left <= 0) nudge(p, q, cur) else nudges(p)[q.key] = left
        }
    }

    /** Login: auto-begin what's eligible, remind the player of every live objective. */
    fun resumeOnLogin(p: Player) {
        QuestRegistry.frameworkQuests().forEach { resume(p, it) }
    }

    fun resume(p: Player, q: QuestDefinition) {
        beginIfEligible(p, q)
        step(p, q)?.let { nudge(p, q, it) }
    }

    fun nudge(p: Player, q: QuestDefinition, step: QuestStep) {
        p.message("<col=801700>${q.displayName} — current objective:</col> ${objectiveLine(p, q)}")
        step.nudge?.let { p.message(it) }
        resetNudge(p, q)
    }

    private fun nudges(p: Player): MutableMap<String, Int> = p.attr[NUDGE_LEFT] ?: HashMap<String, Int>().also { p.attr[NUDGE_LEFT] = it }

    private fun resetNudge(p: Player, q: QuestDefinition) { nudges(p)[q.key] = NUDGE_TICKS }

    // ---- journal ------------------------------------------------------------------------------

    /**
     * Generic journal publish for quests that claimed a [QuestDefinition.journalVarp]:
     * `stepIndex+1 (bits 0-7) | progress (bits 8-19, the kills counter) | state (bits 20-21:
     * 0 none, 1 in progress, 2 complete)`. Only writes on change; never out of range.
     */
    fun publish(p: Player, q: QuestDefinition) {
        val varp = q.journalVarp ?: return
        if (varp >= p.varps.maxVarps) return
        val cur = step(p, q)
        val stepIdx = cur?.let { q.indexOf(it.id) + 1 } ?: 0
        val progress = counter(p, q).coerceIn(0, 0xFFF)
        val state = when {
            isComplete(p, q) -> 2
            started(p, q) -> 1
            else -> 0
        }
        val packed = (stepIdx and 0xFF) or (progress shl 8) or (state shl 20)
        if (p.getVarp(varp) != packed) p.setVarp(varp, packed)
    }
}
