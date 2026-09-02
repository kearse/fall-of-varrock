package org.alter.plugins.content.war.warprep

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.WARPREP_RANGED_KILLS_ATTR
import org.alter.game.model.attr.WARPREP_RANGED_STEP_ATTR
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * **War-Prep II — Ranged** — the prep quest that follows [WarPrepChain] (War-Prep I — Magic) and
 * readies the soldier for the front's skirmish lines by teaching the bow. It is built as a
 * **separate** state machine, not an extension of [WarPrepChain]: Quest 1 (Magic) closes at the
 * Squire rung and this quest carries the player on toward **Lord**. The Rogue Problem (the PK
 * ladder) is an OPTIONAL side assignment and no longer gates it (design authority §8).
 *
 * Shape: gear-up → field test → report → rank-up. Vannaka arms the player with a **marksman's
 * kit**, sends them to fell [FIELD_GOAL] enemies with a ranged weapon, then debriefs and pays a
 * **skirmish bounty** ([SKIRMISH_BOUNTY] — pay for the task, never the rank). The Lordship itself
 * is EARNED — marches, knight farming, loot keys, War Effort — and the RANK step tracks until
 * it's bought. (The old "train Ranged to 40" DRILL step was cut; its ordinal survives as a legacy
 * no-op that migrates straight to GEAR.)
 *
 * All state is a single persistent step ordinal ([WARPREP_RANGED_STEP_ATTR]) plus a quest-scoped
 * FIELD kill counter ([WARPREP_RANGED_KILLS_ATTR]); the chain survives relogs and never re-fires once
 * [Step.DONE]. [WarPrepRangedPlugin] owns the wiring (login resume/begin, poll timer, kill hook,
 * `::warpranged`); Vannaka speaks the beats (see `SlayerPlugin`). Its live step is published to the
 * Quest Journal (client hint arrows + native quest tab) by [org.alter.plugins.content.quests.QuestJournal].
 */
object WarPrepRanged {

    /** Drives the per-player state poll while on a tracked step (detects the Ranged milestone + Lord). */
    val TIMER = TimerKey()
    private const val POLL_TICKS = 3

    private const val NUDGE_TICKS = 500 // ~5 minutes
    private val NUDGE_COUNTDOWN = AttributeKey<Int>()

    /** Enemies to fell with a ranged weapon on the FIELD step (quest-scoped). TUNABLE. */
    const val FIELD_GOAL = 20

    private const val ARROWS = "item.rune_arrow"

    // Marksman's kit for the skirmish: a crossbow, coloured d'hide and a stack of ammo. All TUNABLE /
    // defensive on missing keys.
    private const val MARKSMAN_BOW = "item.magic_shortbow"
    private val MARKSMAN_ARMOUR = arrayOf("item.green_dhide_body", "item.green_dhide_chaps", "item.green_dhide_vambraces")
    private const val MARKSMAN_ARROWS = 2000

    /**
     * Vannaka's payout for the skirmish test — a bounty sized to the TASK, never the rank (the old
     * full-Lord purse gifted the 2m climb away). Lord's [Title.LORD.cost] is earned from the loops
     * the Knight rank opened: knight farming, city raids, loot keys, bounties. TUNE.
     */
    const val SKIRMISH_BOUNTY = 250_000
    private const val COINS = "item.coins_995"

    /** The rank the quest carries the player to — closes the RANK step (and the quest) when reached. */
    val TARGET_TITLE = Title.LORD

    // Persisted BY ORDINAL — never reorder without a migration (the ordinal is the save value + the
    // Quest Journal varp the client reads).
    enum class Step(val objective: String) {
        NONE("(not started)"),
        /** LEGACY (never entered): the old "train Ranged to 40" drill — the Rogue Hunting ladder
         *  trains combat for real now. Ordinal kept for saves; the poll migrates it to GEAR. */
        DRILL("Return to Vannaka for the marksman's kit."),
        GEAR("Return to Vannaka for the marksman's kit."),
        FIELD("Skirmish test: fell $FIELD_GOAL enemies with a ranged weapon; ::warpranged tracks it."),
        REPORT("Return to Vannaka with word of the skirmish."),
        RANK("Earn your Lordship and buy it at Duke Horacio — fight the marches for War Effort and spoils, farm the Rogue Knights for their kits, hunt the wild for loot keys. Heavier armour and command await."),
        DONE("War-Prep — Ranged mastered: you can hold a skirmish line."),
    }

    /** The player's current step (NONE until the chain begins). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[WARPREP_RANGED_STEP_ATTR] ?: 0) { Step.NONE }

    fun started(p: Player): Boolean = step(p) != Step.NONE
    fun complete(p: Player): Boolean = step(p) == Step.DONE

    /** Quest-scoped enemies felled with a ranged weapon on the FIELD step so far (clamped for display). */
    fun fieldKills(p: Player): Int = (p.attr[WARPREP_RANGED_KILLS_ATTR] ?: 0).coerceAtLeast(0)

    /**
     * Begin the chain. Gated on War-Prep I (the Wizard Tower chain) being finished — the Rogue
     * Problem is optional and never gates it. Idempotent.
     */
    fun begin(p: Player) {
        if (step(p) != Step.NONE) return
        if (!WarPrepChain.complete(p)) return
        advanceTo(p, Step.GEAR) // straight to the kit — no drill step
    }

    /** On login, re-arm the poll timer if on a tracked step, and remind the player of the objective. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) {
            p.timers[TIMER] = POLL_TICKS
            nudge(p)
        }
    }

    /** Steps the poll runs on — those with a live objective the poll watches or refreshes. */
    private fun isTracked(s: Step): Boolean =
        s == Step.DRILL || s == Step.GEAR || s == Step.FIELD || s == Step.REPORT || s == Step.RANK

    /** The current objective, with live progress where the step has a measurable target. */
    fun objectiveLine(p: Player): String = when (step(p)) {
        Step.FIELD -> "${Step.FIELD.objective} (${fieldKills(p)}/$FIELD_GOAL)"
        else -> step(p).objective
    }

    /** Say the objective and restart the nudge countdown. */
    fun nudge(p: Player) {
        p.attr[NUDGE_COUNTDOWN] = NUDGE_TICKS
        p.message("<col=801700>War-Prep II — current objective:</col> ${objectiveLine(p)}")
        p.message("Vannaka has more for you once it's done. Check it any time with <col=ffae00>::warpranged</col>.")
    }

    // --- pillar hooks -------------------------------------------------------------------

    /** GEAR: `SlayerPlugin` (Vannaka) calls this once he's armed the recruit with the marksman kit.
     *  Accepts legacy DRILL saves too (the cut drill step) so nobody strands between the poll ticks. */
    fun onArmedForSkirmish(p: Player) {
        if (step(p) != Step.GEAR && step(p) != Step.DRILL) return
        advanceTo(p, Step.FIELD)
    }

    /** FIELD: [WarPrepRangedPlugin]'s death hook calls this for every enemy felled with a ranged
     *  weapon; when the quest-scoped tally hits [FIELD_GOAL] the skirmish test is passed. */
    fun onRangedKill(p: Player) {
        if (step(p) != Step.FIELD) return
        val kills = fieldKills(p) + 1
        p.attr[WARPREP_RANGED_KILLS_ATTR] = kills
        if (kills >= FIELD_GOAL) {
            advanceTo(p, Step.REPORT)
        } else if (kills == FIELD_GOAL / 2) {
            p.message("<col=801700>War-Prep II:</col> $kills/$FIELD_GOAL felled with the bow — keep the line.")
        }
    }

    /** REPORT → RANK: `SlayerPlugin` (Vannaka) calls this on the post-skirmish debrief — pays the
     *  skirmish bounty ([SKIRMISH_BOUNTY]); the Lordship itself is earned and bought when it is. */
    fun onReportedToVannaka(p: Player) {
        if (step(p) != Step.REPORT) return
        advanceTo(p, Step.RANK)
    }

    /** RANK → DONE: `DukeHoracioPlugin` (via `RankPurchase`) calls this on any rank purchase; the quest
     *  closes once the player actually reaches [TARGET_TITLE]. */
    fun onRankBought(p: Player) {
        if (step(p) != Step.RANK) return
        if (p.title.ordinal >= TARGET_TITLE.ordinal) advanceTo(p, Step.DONE)
    }

    /**
     * Poll, driven by [TIMER]. Detects the Ranged milestone from the player's level and closes the RANK
     * step if Lord was reached by any means. Re-arms itself while tracked.
     */
    fun pollTick(p: Player) {
        when (step(p)) {
            Step.DRILL -> { advanceTo(p, Step.GEAR); return } // legacy saves: the drill is cut
            Step.RANK -> if (p.title.ordinal >= TARGET_TITLE.ordinal) { advanceTo(p, Step.DONE); return }
            else -> {}
        }
        if (isTracked(step(p))) {
            p.timers[TIMER] = POLL_TICKS
            val left = (p.attr[NUDGE_COUNTDOWN] ?: NUDGE_TICKS) - POLL_TICKS
            if (left <= 0) nudge(p) else p.attr[NUDGE_COUNTDOWN] = left
        }
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[WARPREP_RANGED_STEP_ATTR] = next.ordinal
        when (next) {
            Step.RANK -> grantRankPurse(p)
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        p.attr[NUDGE_COUNTDOWN] = NUDGE_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>War-Prep II — next objective:</col> ${objectiveLine(p)}")
        }
    }

    /** GEAR briefing payload: hand the recruit the marksman's kit — a bow, d'hide and a stock of arrows. */
    fun armForSkirmish(p: Player) {
        giveItem(p, MARKSMAN_BOW, 1)
        for (piece in MARKSMAN_ARMOUR) giveItem(p, piece, 1)
        giveItem(p, ARROWS, MARKSMAN_ARROWS)
    }

    /** RANK entry: Vannaka's skirmish bounty — pay for the task; the Lordship is earned. */
    private fun grantRankPurse(p: Player) {
        giveItem(p, COINS, SKIRMISH_BOUNTY)
        p.message("<col=801700>Vannaka pays your skirmish bounty: ${"%,d".format(SKIRMISH_BOUNTY)} coins.</col> The ${TARGET_TITLE.display}ship's ${"%,d".format(TARGET_TITLE.cost)} you'll earn — marches, knight farming, loot keys — then buy it at Duke Horacio.")
    }

    private fun grantCompletion(p: Player) {
        p.message("<col=801700>War-Prep — Ranged complete!</col> You can hold a skirmish line now — bow and blade both. General Zo has the next lesson when you're Lord: staying alive when the front collapses.")
    }

    /** One-line progress report (`::warpranged` and Vannaka's chatter). */
    fun statusLine(p: Player): String = when (step(p)) {
        Step.NONE -> "War-Prep II — Ranged: finish War-Prep I (the Wizard Tower) first."
        Step.FIELD -> "War-Prep II — Ranged: <col=801700>${fieldKills(p)}/$FIELD_GOAL</col> felled with a ranged weapon."
        Step.DONE -> "War-Prep II — Ranged: <col=4f9b4f>complete</col>."
        else -> "War-Prep II — Ranged — current objective: ${step(p).objective}"
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
