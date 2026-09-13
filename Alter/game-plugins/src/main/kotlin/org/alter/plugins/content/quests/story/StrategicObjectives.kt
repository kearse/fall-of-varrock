package org.alter.plugins.content.quests.story

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestStep

/**
 * **The regional campaign phase** (design authority §9): after A Kingdom Alone the main campaign
 * stops pointing at a single next quest and tracks four standing strategic problems instead —
 * BREACH (Asgarnia), SECURE (Morytania), UNDERSTAND (Wilderness → Desert → Senntisten) and
 * SUSTAIN (Kandarin + the War Effort). Each is a framework quest with `Objective.Manual` steps so
 * it sits in the normal journal (client entry, native quest-tab row, `::questdebug`) and nothing
 * new had to be built: it is begun by [AKingdomAlone.onComplete], never has an arrow, never nags
 * on login, and is SOLVED only by its regional campaign's payoff calling [StrategicObjectives.solve].
 *
 * The four completion flags are the framework's own `quest.<key>.done` ([flag]); the Council of
 * Gielinor gate is exactly [StrategicObjectives.allSolved] — not quest points, not a count of
 * regional quests, not rank, not War Effort (design §15).
 */
abstract class StrategicObjective(
    key: String,
    displayName: String,
    chainIndex: Int,
    journalVarp: Int,
    override val nativeTabVarp: Int,
    override val nativeTabComplete: Int,
    /** The one-word objective ("BREACH"). */
    val code: String,
    /** The problem, in one line — the standing journal entry. */
    val problem: String,
    /** Where to start: region + first quest. */
    val lead: String,
) : QuestDefinition(key = key, displayName = displayName, chainIndex = chainIndex, journalVarp = journalVarp) {

    override val prerequisites: List<Prerequisite> = listOf(Prerequisite.QuestComplete(AKingdomAlone.KEY))

    /** Opened by A Kingdom Alone's completion (and the login / poll back-fill for anyone who missed it). */
    override val autoBegin: Boolean = true

    /** Leads are whole regions — no guidance arrow, and never the arrow provider's "deepest live quest". */
    override val serverArrow: Boolean = false

    /** The four announce themselves as ONE `::strategy` line on login, not four reminders. */
    override val loginReminder: Boolean = false

    override val completionMessage: String get() = "$code is solved. ${StrategicObjectives.remainingLine()}"

    /** The persistent story flag other systems may read (`Flags.has(p, Breach.flag)`). */
    val flag: String get() = Flags.Known.QUEST_DONE_PREFIX + key + ".done"

    fun solved(p: Player): Boolean = QuestEngine.isComplete(p, this)

    /** "BREACH — Incomplete: Find a way … (Lead: Falador / Asgarnia)" */
    fun statusLine(p: Player): String {
        val state = if (solved(p)) "<col=4f9b4f>Solved</col>" else "<col=ffae00>Incomplete</col>"
        return "<col=801700>$code</col> — $state: $problem Lead: $lead."
    }
}

/** BREACH — Asgarnia: how do we get an army into Fallen Varrock? */
object Breach : StrategicObjective(
    key = "breach", displayName = "BREACH — Asgarnia",
    chainIndex = QuestBook.BREACH, journalVarp = QuestJournal.BREACH_VARP,
    nativeTabVarp = QuestJournal.BREACH_QUEST_VARP, nativeTabComplete = QuestJournal.BREACH_QUEST_COMPLETE,
    code = "BREACH",
    problem = "Find a way for coalition forces to break through Varrock's defences.",
    lead = "Falador / Asgarnia — first quest: At the White Wall",
) {
    override val steps: List<QuestStep> = listOf(
        QuestStep("breach", Objective.Manual(problem), nudge = "Lead: $lead."),
    )
}

/** SECURE — Morytania: what protects Misthalin while its army is fighting at Varrock? */
object Secure : StrategicObjective(
    key = "secure", displayName = "SECURE — Morytania",
    chainIndex = QuestBook.SECURE, journalVarp = QuestJournal.SECURE_VARP,
    nativeTabVarp = QuestJournal.SECURE_QUEST_VARP, nativeTabComplete = QuestJournal.SECURE_QUEST_COMPLETE,
    code = "SECURE",
    problem = "Ensure Misthalin will remain secure while its army fights in the north.",
    lead = "the River Salve and Morytania — an accord with the east, not an alliance",
) {
    override val steps: List<QuestStep> = listOf(
        QuestStep("secure", Objective.Manual(problem), nudge = "Lead: $lead. The goal is the Salve Accord — an accord, not an alliance."),
    )
}

/**
 * UNDERSTAND — the one objective with an internal order: the Wilderness (The First Scar), then the
 * Kharidian Desert, then Senntisten knowledge. Regional quests advance it step by step
 * ([StrategicObjectives.advance]); the Senntisten step solves it.
 */
object Understand : StrategicObjective(
    key = "understand", displayName = "UNDERSTAND — Wilderness / Desert",
    chainIndex = QuestBook.UNDERSTAND, journalVarp = QuestJournal.UNDERSTAND_VARP,
    nativeTabVarp = QuestJournal.UNDERSTAND_QUEST_VARP, nativeTabComplete = QuestJournal.UNDERSTAND_QUEST_COMPLETE,
    code = "UNDERSTAND",
    problem = "Discover what truly happened during the Fall and whether the same danger remains.",
    lead = "the Wilderness — an older catastrophe than the Fall; then the Kharidian Desert",
) {
    const val WILDERNESS = "wilderness"
    const val DESERT = "desert"
    const val SENNTISTEN = "senntisten"

    override val steps: List<QuestStep> = listOf(
        QuestStep(WILDERNESS, Objective.Manual("Investigate the First Scar — the Wilderness as an older catastrophe."), nudge = "Lead: the Wilderness. No PvP kill is ever required."),
        QuestStep(DESERT, Objective.Manual("Follow the evidence into the Kharidian Desert."), nudge = "Azzanadra and Mahjarrat history lead toward Sliske and the Elder Horn."),
        QuestStep(SENNTISTEN, Objective.Manual("Uncover what Senntisten holds beneath Varrock."), nudge = "The convergence that preceded the Fall."),
    )
}

/** SUSTAIN — Kandarin + the War Effort: how do we keep an army alive once it reaches Varrock? */
object Sustain : StrategicObjective(
    key = "sustain", displayName = "SUSTAIN — Kandarin / War Effort",
    chainIndex = QuestBook.SUSTAIN, journalVarp = QuestJournal.SUSTAIN_VARP,
    nativeTabVarp = QuestJournal.SUSTAIN_QUEST_VARP, nativeTabComplete = QuestJournal.SUSTAIN_QUEST_COMPLETE,
    code = "SUSTAIN",
    problem = "Create the supply and transportation network required to maintain a major offensive.",
    lead = "Kandarin and the War Effort — the road an army would eat along",
) {
    override val steps: List<QuestStep> = listOf(
        QuestStep("sustain", Objective.Manual(problem), nudge = "Lead: $lead."),
    )
}

/** The four objectives as one thing: open them, solve them, ask whether the Council may convene. */
object StrategicObjectives {

    val ALL: List<StrategicObjective> get() = listOf(Breach, Secure, Understand, Sustain)

    /** Open the regional phase: begin every objective (A Kingdom Alone's completion calls this). */
    fun open(p: Player) {
        ALL.forEach { QuestEngine.begin(p, it) }
    }

    /**
     * A regional campaign's payoff solves its objective — the ONLY way one completes. Single-step
     * objectives complete outright; UNDERSTAND is advanced through its ordered steps with [advance]
     * and this solves whatever remains. Safe to call for a player who somehow never opened the
     * phase (it is begun first) and a no-op once solved.
     */
    fun solve(p: Player, objective: StrategicObjective) {
        if (objective.solved(p)) return
        if (!QuestEngine.started(p, objective)) QuestEngine.begin(p, objective, force = true)
        QuestEngine.complete(p, objective)
    }

    /** Advance an ordered objective past [stepId] (UNDERSTAND: wilderness → desert → senntisten). */
    fun advance(p: Player, objective: StrategicObjective, stepId: String): Boolean {
        if (objective.solved(p)) return false
        if (!QuestEngine.started(p, objective)) QuestEngine.begin(p, objective, force = true)
        return QuestEngine.satisfy(p, objective, stepId)
    }

    fun solvedCount(p: Player): Int = ALL.count { it.solved(p) }

    /** The Council of Gielinor gate: all four solved — and nothing else (design §15). */
    fun allSolved(p: Player): Boolean = ALL.all { it.solved(p) }

    fun councilAvailable(p: Player): Boolean = allSolved(p)

    /** The one-line standing overview (login + `::strategy`): "BREACH x SECURE x UNDERSTAND x SUSTAIN x". */
    fun overviewLine(p: Player): String {
        val marks = ALL.joinToString("  ") { o ->
            if (o.solved(p)) "<col=4f9b4f>${o.code} solved</col>" else "<col=ffae00>${o.code} incomplete</col>"
        }
        return "<col=801700>Preparing for Varrock:</col> $marks — <col=801700>::strategy</col>"
    }

    /** Tail for an objective's completion message. */
    fun remainingLine(): String =
        "When BREACH, SECURE, UNDERSTAND and SUSTAIN are all solved, the Council of Gielinor can convene."

    /** Print the full overview and open the Quest Journal on the first unsolved objective. */
    fun report(p: Player) {
        p.message("<col=801700>PREPARING FOR VARROCK</col> — the surviving kingdoms must solve four problems before a sustained assault on Fallen Varrock:")
        ALL.forEach { p.message(" ${it.statusLine(p)}") }
        if (allSolved(p)) {
            p.message("All four are solved. The Council of Gielinor can convene.")
        } else {
            p.message("Work where you can — progress in one may open opportunities in another. All four must be solved before the realm commits to Varrock.")
        }
        val focus = ALL.firstOrNull { !it.solved(p) } ?: ALL.last()
        focus.chainIndex?.let { QuestBook.open(p, it) }
    }
}
