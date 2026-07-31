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
 * an open-ended grind: the Recruiting Sergeant sends the new Squire into **Fallen Falador** (where
 * the cutthroats fled when demons took Varrock — see `WorldSpawnsPlugin.applyFallenFalador`) to
 * thin its cutthroats and cut down one of its **named captains** (the existing [RogueHunt] +
 * `war/captains` content, now given a scripted first run), then pays a purse that covers the last
 * rungs to **Knight** — which unlocks the player's first **companion** and the real wilderness /
 * PK loop. This is the pure state machine; [RogueProblemPlugin] owns the wiring (login resume,
 * poll timer, kill hooks), the Recruiting Sergeant speaks the beats, and Duke Horacio reports the
 * rank-up.
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

    /** Rogues to fell in Fallen Falador for the HUNT step (quest-scoped, not the lifetime tally). TUNE. */
    const val HUNT_GOAL = 30

    private const val COINS = "item.coins_995"

    /**
     * The quest's culminating purse — sized straight off the ladder so it always covers the rungs
     * from the player's post-War-Prep rank ([Title.SQUIRE]) up to [Title.KNIGHT]: Soldier + Knight.
     * This is the "…and that gives you enough for Knight" payout the design calls for; combined with
     * the guaranteed captain bounty and rogue drops earned on the way, the player clears the climb.
     *
     * ECONOMY NOTE (TUNE): the roadmap frames Squire→Knight as a longer grind. This purse
     * deliberately short-cuts it into a single guided quest — dial it down (and lean on the earned
     * hunt/captain/milestone coin) if you want the climb to stay a multi-session haul.
     */
    val COMPLETION_COINS = Title.SOLDIER.cost + Title.KNIGHT.cost

    /** The rank the quest carries the player to — closes the RANK step (and the quest) when reached. */
    val TARGET_TITLE = Title.KNIGHT

    // Persisted BY ORDINAL. Never reorder without a migration (the ordinal is the save value + the
    // Quest Journal varp the client reads).
    enum class Step(val objective: String) {
        NONE("(not started)"),
        BRIEF("Speak to the Recruiting Sergeant about the rogues overrunning Fallen Falador."),
        HUNT("Cut down $HUNT_GOAL rogues in the streets of Fallen Falador — the tally is quest-scoped; ::rogueproblem tracks it."),
        CAPTAIN("Hunt down one of the named captains holed up in Fallen Falador — see the board with ::bounties."),
        REPORT("Return to the Recruiting Sergeant with word of the captain's fall."),
        RANK("Take your purse to Duke Horacio and climb to Knight — a companion and the wilderness await."),
        DONE("The Rogue Problem — Knighthood earned. Muster a companion from General Zo, then hunt at the PK Arena."),
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
        s == Step.BRIEF || s == Step.HUNT || s == Step.CAPTAIN || s == Step.REPORT || s == Step.RANK

    // --- pillar hooks -------------------------------------------------------------------

    /** BRIEF → HUNT: the Recruiting Sergeant calls this once the player accepts the contract. */
    fun onSergeantBriefed(p: Player) {
        if (step(p) != Step.BRIEF) return
        advanceTo(p, Step.HUNT)
    }

    /** HUNT: [RogueProblemPlugin]'s death hook calls this for every rogue-family kill; when the
     *  quest-scoped tally hits [HUNT_GOAL] the streets are cleared and the hunt turns to a captain. */
    fun onRogueKill(p: Player) {
        if (step(p) != Step.HUNT) return
        val kills = huntKills(p) + 1
        p.attr[ROGUE_PROBLEM_KILLS_ATTR] = kills
        if (kills >= HUNT_GOAL) {
            advanceTo(p, Step.CAPTAIN)
        } else if (kills == HUNT_GOAL / 2) {
            p.message("<col=801700>The Rogue Problem:</col> $kills/$HUNT_GOAL cutthroats down — keep at it.")
        }
    }

    /** CAPTAIN → REPORT: `NamedCaptainsPlugin` calls this when the player fells a named captain. */
    fun onCaptainKill(p: Player) {
        if (step(p) != Step.CAPTAIN) return
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
            Step.RANK -> grantPurse(p) // the quest's payout — enough to reach Knight
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>The Rogue Problem — next objective:</col> ${next.objective}")
        }
    }

    /** RANK entry: the Sergeant's payout for breaking the captains — a purse sized to reach Knight. */
    private fun grantPurse(p: Player) {
        giveItem(p, COINS, COMPLETION_COINS)
        p.message("<col=801700>The Sergeant pays you ${"%,d".format(COMPLETION_COINS)} coins</col> — enough to climb to ${TARGET_TITLE.display} at Duke Horacio.")
    }

    private fun grantCompletion(p: Player) {
        p.message("<col=801700>The Rogue Problem complete!</col> You've earned your ${TARGET_TITLE.display}hood — and with it your first <col=801700>companion</col>: seek General Zo in the castle courtyard to muster one.")
        p.message("<col=801700>The wilderness is open to you now.</col> Learn the ropes at the <col=ffae00>PK Training Arena</col> (loaner kits, sparring bots), then hunt for real — player kills pay Blood Money.")
    }

    /** One-line progress report (`::rogueproblem` and the Sergeant's chatter). */
    fun statusLine(p: Player): String = when (step(p)) {
        Step.NONE -> "The Rogue Problem: finish the War-Prep chain first."
        Step.HUNT -> "The Rogue Problem: <col=801700>${huntKills(p)}/$HUNT_GOAL</col> rogues felled in Fallen Falador."
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
