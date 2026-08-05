package org.alter.plugins.content.war.roguehunt

import org.alter.api.ext.message
import org.alter.game.model.attr.ROGUE_PROBLEM_KILLS_ATTR
import org.alter.game.model.attr.ROGUE_PROBLEM_STEP_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.bots.knights.RogueKnightLadder
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.rscm.RSCM.getRSCM

/**
 * **The Rogue Problem** — the Act II quest (story-and-grind-design §4) that answers "what now?"
 * the moment the [WarPrepChain] (Wizard Tower / War-Prep I — Magic) finishes at the Squire rung.
 * It is the guided bridge across the **Squire → Knight** climb that the roadmap otherwise leaves as
 * an open-ended grind: the Recruiting Sergeant sets the new Squire on the rogue rank and file —
 * huntable on the SAFE road camps west of Lumbridge (the jail hideout, Draynor, south of Port
 * Sarim — see `BotZones`) or, denser but lawless, in **Fallen Falador** (where the cutthroats fled
 * when demons took Varrock — see `WorldSpawnsPlugin.applyFallenFalador`; the city is a raid-city
 * PvP ground, which was farming fresh Squires when the quest steered them there first) —
 * then opens the **Rogue Knight ladder** (`bots/knights/`) with the player's
 * first assigned named knight. Clearing the hunt pays a **soldier's purse** ([HUNT_PURSE] — no
 * rung skipped); **Knighthood is earned on the ladder** (knight coin + kit drops + bounties) and
 * bought whenever the purse allows — buying it never ends the quest. The quest's true finish
 * line is the LADDER step: **every camp broken, every named knight beaten**, ending with the
 * Rogue Commander — the quest IS the realm's PK curriculum. Beaten knights stay farmable after.
 * This
 * is the pure state machine; [RogueProblemPlugin] owns the wiring (login resume, poll timer, kill
 * hooks), the Recruiting Sergeant speaks the beats, and Duke Horacio reports the rank-up.
 *
 * State is a single persistent step ordinal ([ROGUE_PROBLEM_STEP_ATTR]) plus a quest-scoped hunt
 * counter ([ROGUE_PROBLEM_KILLS_ATTR]); the chain survives relogs and never re-fires once
 * [Step.DONE]. Its live step is published to the Quest Journal (client hint arrows + native quest
 * tab) so the quest helper guides the player the whole way.
 */
object RogueProblem {

    /** Drives the per-player poll while on a tracked step (refreshes guidance + detects the Knight rung). */
    val TIMER = TimerKey()
    private const val POLL_TICKS = 3

    /** Rogues to fell for the HUNT step — any rogue-family kill counts, anywhere in the world
     *  (quest-scoped, not the lifetime tally). TUNE. */
    const val HUNT_GOAL = 30

    private const val COINS = "item.coins_995"

    /**
     * The HUNT bounty — paid the moment the 30-kill hunt clears, sized to exactly cover the
     * [Title.SOLDIER] rank so the ladder is walked as a Soldier, no rung skipped. Knighthood is
     * EARNED, never gifted: the ladder pays it — first-kill coin, the knights' worn-kit drops,
     * camp rogues' kits, and the Sergeant's milestone bounties — the multi-session climb the
     * roadmap calls for. (The old single 650k Soldier+Knight purse skipped Soldier entirely.)
     */
    val HUNT_PURSE = Title.SOLDIER.cost

    // Persisted BY ORDINAL. Never reorder without a migration (the ordinal is the save value + the
    // Quest Journal varp the client reads). KNIGHT holds CAPTAIN's old ordinal 3 — the beat was
    // reworked from "kill a named captain" to "kill your first assigned Rogue Knight" (the ladder
    // opener). LADDER holds RANK's old ordinal 5 — the beat was reworked from "buy Knighthood"
    // to "break every camp on the ladder" (the quest IS the PK curriculum now; ranks are bought
    // along the way as the spoils allow). A player saved mid-step simply gets the new objective.
    enum class Step(val objective: String) {
        NONE("(not started)"),
        BRIEF("Speak to the Recruiting Sergeant about the rogues overrunning Fallen Falador."),
        HUNT("Cut down $HUNT_GOAL of the rogue family — kills count anywhere. Hunt the safe road camps first (the jail west of Lumbridge, Draynor, south of Port Sarim); Fallen Falador is richer hunting but lawless raid ground. ::rogueproblem tracks it."),
        KNIGHT("Buy Soldier with your hunt purse, then thin your assigned Rogue Knight's camp and cut the knight down — ::knights tracks both and the marker leads the way."),
        REPORT("Return to the Recruiting Sergeant with word of the knight's fall."),
        LADDER("Break every knight on the ladder, camp by camp — ::knights tracks the climb, the marker leads. Buy your ranks from Duke Horacio as the spoils come in; the ladder pays for them."),
        DONE("Rogue Hunting II — the ladder is broken: every rogue camp cleared, the Commander dead. Farm any knight for its gear (::knights); the wilderness is yours."),
    }

    /** The player's current step (NONE until the chain begins). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[ROGUE_PROBLEM_STEP_ATTR] ?: 0) { Step.NONE }

    fun started(p: Player): Boolean = step(p) != Step.NONE
    fun complete(p: Player): Boolean = step(p) == Step.DONE

    /** Quest-scoped rogues felled on the HUNT step so far (clamped for display). */
    fun huntKills(p: Player): Int = (p.attr[ROGUE_PROBLEM_KILLS_ATTR] ?: 0).coerceAtLeast(0)

    /**
     * Begin the chain. Gated on the War-Prep chain being finished (the quest is Act II — it only
     * makes sense once the Wizard Tower and the Squire rank-up are behind the player). Idempotent.
     */
    fun begin(p: Player) {
        if (step(p) != Step.NONE) return
        if (!WarPrepChain.complete(p)) return
        advanceTo(p, Step.BRIEF)
    }

    /** On login, re-arm the poll timer if on a tracked step. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) p.timers[TIMER] = POLL_TICKS
    }

    /** Steps the poll runs on — those with a live objective the poll watches or refreshes. */
    private fun isTracked(s: Step): Boolean =
        s == Step.BRIEF || s == Step.HUNT || s == Step.KNIGHT || s == Step.REPORT || s == Step.LADDER

    // --- pillar hooks -------------------------------------------------------------------

    /** BRIEF → HUNT: the Recruiting Sergeant calls this once the player accepts the contract. */
    fun onSergeantBriefed(p: Player) {
        if (step(p) != Step.BRIEF) return
        advanceTo(p, Step.HUNT)
    }

    /** HUNT: [RogueProblemPlugin]'s death hook calls this for every rogue-family kill; when the
     *  quest-scoped tally hits [HUNT_GOAL] the streets are cleared and the Sergeant opens the
     *  Rogue Knight ladder with the player's first named assignment. */
    fun onRogueKill(p: Player) {
        if (step(p) != Step.HUNT) return
        val kills = huntKills(p) + 1
        p.attr[ROGUE_PROBLEM_KILLS_ATTR] = kills
        if (kills >= HUNT_GOAL) {
            advanceTo(p, Step.KNIGHT)
        } else if (kills == HUNT_GOAL / 2) {
            p.message("<col=801700>Rogue Hunting I:</col> $kills/$HUNT_GOAL cutthroats down — keep at it.")
        }
    }

    /** KNIGHT → REPORT: `RogueKnightLadder` calls this when the player fells their first assigned
     *  Rogue Knight (the ladder's rank-0 kill — the ladder itself continues past the quest). */
    fun onAssignedKnightKill(p: Player) {
        if (step(p) != Step.KNIGHT) return
        advanceTo(p, Step.REPORT)
    }

    /** REPORT → LADDER: the Recruiting Sergeant calls this on the debrief — the guided beats are
     *  done; what remains is the climb itself, camp by camp to the Rogue Commander. */
    fun onReportedToSergeant(p: Player) {
        if (step(p) != Step.REPORT) return
        advanceTo(p, Step.LADDER)
    }

    /** LADDER → DONE: `RogueKnightLadder` calls this when the player's final ladder knight falls —
     *  every camp broken is the quest's true finish line (ranks are bought whenever the purse
     *  allows; reaching Knight early never ends the climb). */
    fun onLadderCleared(p: Player) {
        if (step(p) != Step.LADDER) return
        advanceTo(p, Step.DONE)
    }

    /** Poll, driven by [TIMER]. Closes the LADDER step if the ladder is complete by any means
     *  (legacy saves included), and re-arms itself while tracked. */
    fun pollTick(p: Player) {
        if (step(p) == Step.LADDER && RogueKnightLadder.complete(p)) {
            advanceTo(p, Step.DONE)
            return
        }
        if (isTracked(step(p))) p.timers[TIMER] = POLL_TICKS
    }

    // --- transitions --------------------------------------------------------------------

    /** The quest-tab name for the phase [s] belongs to — Act II is TWO listed quests off this one
     *  chain: Rogue Hunting I (the hunt) and Rogue Hunting II (the ladder). */
    fun questName(s: Step): String =
        if (s.ordinal >= Step.KNIGHT.ordinal) "Rogue Hunting II" else "Rogue Hunting I"

    /** Advance to [next], run its side effects and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[ROGUE_PROBLEM_STEP_ATTR] = next.ordinal
        when (next) {
            Step.KNIGHT -> grantHuntPurse(p) // Rogue Hunting I complete — the hunt bounty pays out
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>${questName(next)} — next objective:</col> ${next.objective}")
        }
    }

    /** KNIGHT entry: the hunt bounty — a soldier's purse the moment the rank and file are thinned.
     *  Covers exactly [Title.SOLDIER]; Knighthood is earned on the ladder. */
    private fun grantHuntPurse(p: Player) {
        giveItem(p, COINS, HUNT_PURSE)
        p.message("<col=4f9b4f>Rogue Hunting I complete!</col> <col=801700>The Sergeant pays your hunt bounty: ${"%,d".format(HUNT_PURSE)} coins</col> — a soldier's purse. Buy <col=ffae00>Soldier</col> from Duke Horacio, then take to the ladder: <col=801700>Rogue Hunting II</col> begins.")
    }

    private fun grantCompletion(p: Player) {
        p.message("<col=801700>Rogue Hunting II complete!</col> Every rogue camp is broken and the Commander is dead — you learned to fight players the hard way, and won.")
        p.message("<col=801700>The ladder stays yours to farm:</col> every beaten knight can be hunted again for its gear (<col=ffae00>::knights</col>) — and the open wilderness pays Blood Money for real hunts.")
        if (p.title.ordinal < Title.KNIGHT.ordinal) {
            p.message("<col=ffae00>Your spoils are long past a Knighthood</col> — buy your ranks from Duke Horacio: rune armour and a companion of your own (General Zo musters them) await.")
        }
        // The broken ladder is Act II's exit — hand straight into Act III (its begin() re-checks
        // every gate itself, so this is a no-op for anyone not actually eligible yet).
        org.alter.plugins.content.war.warprep.WarPrepRanged.begin(p)
    }

    /** One-line progress report (`::rogueproblem` and the Sergeant's chatter). */
    fun statusLine(p: Player): String = when (val s = step(p)) {
        Step.NONE -> "Rogue Hunting I: finish the War-Prep chain first."
        Step.HUNT -> "Rogue Hunting I: <col=801700>${huntKills(p)}/$HUNT_GOAL</col> of the rogue family felled."
        Step.LADDER -> "Rogue Hunting II: <col=801700>${RogueKnightLadder.rank(p)}/${RogueKnights.LADDER.size}</col> knights of the ladder broken — ::knights leads the hunt."
        Step.DONE -> "Rogue Hunting II: <col=4f9b4f>complete</col> — every camp broken; the streets fear you."
        else -> "${questName(s)} — current objective: ${s.objective}"
    }

    /** Add [amount] of [key] to the bag; whatever doesn't fit overflows to the bank. Defensive on keys. */
    private fun giveItem(p: Player, key: String, amount: Int = 1) {
        runCatching {
            val id = getRSCM(key)
            val tx = p.inventory.add(id, amount, assureFullInsertion = false)
            val left = amount - tx.completed
            if (left > 0) p.bank.add(id, left)
        }
    }
}
