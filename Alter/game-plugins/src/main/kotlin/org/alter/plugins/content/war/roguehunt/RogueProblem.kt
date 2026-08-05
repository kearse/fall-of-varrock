package org.alter.plugins.content.war.roguehunt

import org.alter.api.ext.message
import org.alter.game.model.attr.ROGUE_PROBLEM_KILLS_ATTR
import org.alter.game.model.attr.ROGUE_PROBLEM_STEP_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
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
 * rung skipped); **Knighthood is earned on the ladder** (knight coin + kit drops + bounties),
 * and reaching it unlocks the player's first **companion** and the real wilderness / PK loop.
 * The ladder itself keeps assigning harder and harder knights long after this quest closes. This
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

    /** The rank the quest carries the player to — closes the RANK step (and the quest) when reached. */
    val TARGET_TITLE = Title.KNIGHT

    // Persisted BY ORDINAL. Never reorder without a migration (the ordinal is the save value + the
    // Quest Journal varp the client reads). KNIGHT holds CAPTAIN's old ordinal 3 — the beat was
    // reworked from "kill a named captain" to "kill your first assigned Rogue Knight" (the ladder
    // opener); a player saved mid-step simply gets the new objective.
    enum class Step(val objective: String) {
        NONE("(not started)"),
        BRIEF("Speak to the Recruiting Sergeant about the rogues overrunning Fallen Falador."),
        HUNT("Cut down $HUNT_GOAL of the rogue family — kills count anywhere. Hunt the safe road camps first (the jail west of Lumbridge, Draynor, south of Port Sarim); Fallen Falador is richer hunting but lawless raid ground. ::rogueproblem tracks it."),
        KNIGHT("Buy Soldier with your hunt purse, then thin your assigned Rogue Knight's camp and cut the knight down — ::knights tracks both and the marker leads the way."),
        REPORT("Return to the Recruiting Sergeant with word of the knight's fall."),
        RANK("Climb to Knight at Duke Horacio — the ladder's spoils pay the way: knight kills, their kits, camp loot and the Sergeant's bounties. A companion and the wilderness await."),
        DONE("The Rogue Problem — Knighthood earned. Muster a companion from General Zo, then keep climbing the Rogue Knight ladder (::knights)."),
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
        s == Step.BRIEF || s == Step.HUNT || s == Step.KNIGHT || s == Step.REPORT || s == Step.RANK

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
            p.message("<col=801700>The Rogue Problem:</col> $kills/$HUNT_GOAL cutthroats down — keep at it.")
        }
    }

    /** KNIGHT → REPORT: `RogueKnightLadder` calls this when the player fells their first assigned
     *  Rogue Knight (the ladder's rank-0 kill — the ladder itself continues past the quest). */
    fun onAssignedKnightKill(p: Player) {
        if (step(p) != Step.KNIGHT) return
        advanceTo(p, Step.REPORT)
    }

    /** REPORT → RANK: the Recruiting Sergeant calls this on the debrief — pays the purse and points
     *  the player at Duke Horacio to climb to Knight. */
    fun onReportedToSergeant(p: Player) {
        if (step(p) != Step.REPORT) return
        advanceTo(p, Step.RANK)
    }

    /** RANK → DONE: `DukeHoracioPlugin` calls this on any rank purchase; the quest closes once the
     *  player actually reaches [TARGET_TITLE] (they may need to buy Soldier first, then Knight). */
    fun onRankBought(p: Player) {
        if (step(p) != Step.RANK) return
        if (p.title.ordinal >= TARGET_TITLE.ordinal) advanceTo(p, Step.DONE)
    }

    /** Poll, driven by [TIMER]. Closes the RANK step if the player reached Knight by any means, and
     *  re-arms itself while tracked. */
    fun pollTick(p: Player) {
        if (step(p) == Step.RANK && p.title.ordinal >= TARGET_TITLE.ordinal) {
            advanceTo(p, Step.DONE)
            return
        }
        if (isTracked(step(p))) p.timers[TIMER] = POLL_TICKS
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[ROGUE_PROBLEM_STEP_ATTR] = next.ordinal
        when (next) {
            Step.KNIGHT -> grantHuntPurse(p) // the hunt bounty — a soldier's purse, nothing more
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>The Rogue Problem — next objective:</col> ${next.objective}")
        }
    }

    /** KNIGHT entry: the hunt bounty — a soldier's purse the moment the rank and file are thinned.
     *  Covers exactly [Title.SOLDIER]; Knighthood is earned on the ladder. */
    private fun grantHuntPurse(p: Player) {
        giveItem(p, COINS, HUNT_PURSE)
        p.message("<col=801700>The Sergeant pays your hunt bounty: ${"%,d".format(HUNT_PURSE)} coins</col> — a soldier's purse. Buy <col=ffae00>Soldier</col> from Duke Horacio, then take to the ladder: its knights guard the coin and kit that will earn your Knighthood.")
    }

    private fun grantCompletion(p: Player) {
        p.message("<col=801700>The Rogue Problem complete!</col> You've earned your ${TARGET_TITLE.display}hood — and with it your first <col=801700>companion</col>: seek General Zo in the castle courtyard to muster one.")
        p.message("<col=801700>The wilderness is open to you now.</col> Learn the ropes at the <col=ffae00>PK Training Arena</col> (loaner kits, sparring bots), then hunt for real — player kills pay Blood Money.")
        p.message("<col=801700>And the Rogue Knight ladder continues:</col> the Sergeant has harder and harder knights for you — each one guards the gear for the next fight (<col=ffae00>::knights</col>).")
    }

    /** One-line progress report (`::rogueproblem` and the Sergeant's chatter). */
    fun statusLine(p: Player): String = when (step(p)) {
        Step.NONE -> "The Rogue Problem: finish the War-Prep chain first."
        Step.HUNT -> "The Rogue Problem: <col=801700>${huntKills(p)}/$HUNT_GOAL</col> of the rogue family felled."
        Step.DONE -> "The Rogue Problem: <col=4f9b4f>complete</col> — the streets fear you."
        else -> "The Rogue Problem — current objective: ${step(p).objective}"
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
