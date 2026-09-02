package org.alter.plugins.content.war.warprep

import org.alter.api.Skills
import org.alter.game.model.attr.ARENA_BEST_WAVE_ATTR
import org.alter.game.model.attr.WARPREP_SURVIVAL_DRILL_TOPUPS_ATTR
import org.alter.game.model.attr.WARPREP_SURVIVAL_STEP_ATTR
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * **War-Prep III — Survival** — the Act III prep quest that follows [WarPrepRanged] and carries the
 * Lord up to **Minister**, teaching field survival for when the front collapses around you. Built as
 * a **separate** state machine (the [org.alter.plugins.content.war.roguehunt.RogueProblem] pattern).
 *
 * Shape mirrors the earlier prep quests (skill drill → gear-up → field test → report → rank-up):
 * **General Zo** — the commander of the Lumbridge defense — drills the soldier's **Hitpoints to
 * [HP_TARGET]**, hands a **survival kit** (food, brews, restores, armour), and sends them into the
 * **Fight Cave** to prove they can endure — survive to wave [FIELD_WAVE]. He then debriefs and pays
 * a **survival bounty** ([SURVIVAL_BOUNTY] — pay for the trial, never the rank); the Ministry's 10m
 * is EARNED in command (marches, raids, the ladder's elite) and the RANK step tracks until bought.
 *
 * The FIELD test reuses the existing Fight Cave hiscore ([ARENA_BEST_WAVE_ATTR]) — the poll simply
 * watches it, so no coupling into the cave plugin is needed. All state is a single persistent step
 * ordinal ([WARPREP_SURVIVAL_STEP_ATTR]); the chain survives relogs and never re-fires once
 * [Step.DONE]. [WarPrepSurvivalPlugin] owns the wiring; General Zo speaks the beats.
 */
object WarPrepSurvival {

    /** Drives the per-player state poll while on a tracked step (detects the HP milestone, cave wave, Minister). */
    val TIMER = TimerKey()
    private const val POLL_TICKS = 3

    // The objective is announced on step entry and once on login — never on a timer (the old
    // 5-minute re-nudge was cut 2026-09-02 as chat spam; `::warpsurvival` shows it on demand).

    /** The Hitpoints level the DRILL step trains to. TUNABLE. */
    const val HP_TARGET = 60

    /** The Fight Cave wave the FIELD step requires the player to survive to. The `::arena` Fight Cave
     *  is 11 waves, so this is deliberately a mid-cave endurance bar, not a full clear. TUNABLE. */
    const val FIELD_WAVE = 6

    /** General Zo vouches a player through the FIELD test once they've reached this (lower) wave — the
     *  anti-soft-lock bar, so an unlucky run in the cave never traps the quest. TUNABLE. */
    const val FIELD_VOUCH_WAVE = 4

    // Survival kit: food, brews, restores, high-Defence armour and antipoison — everything a soldier
    // needs to outlast a collapsing front. All TUNABLE / defensive on missing keys.
    private val SURVIVAL_ARMOUR = arrayOf("item.rune_full_helm", "item.rune_platebody", "item.rune_platelegs")
    private val SURVIVAL_SUPPLIES = arrayOf(
        "item.shark" to 20, "item.saradomin_brew4" to 6, "item.super_restore4" to 4, "item.antipoison4" to 2,
    )

    /**
     * General Zo's payout for the Fight Cave trial — a bounty sized to the TASK, never the rank
     * (the old full-Minister purse gifted the 10m climb away). Minister's [Title.MINISTER.cost] is
     * earned from the loops a Lord commands: marches and raids, the ladder's elite rares, the deep
     * wild. TUNE.
     */
    const val SURVIVAL_BOUNTY = 1_000_000
    private const val COINS = "item.coins_995"

    /** The rank the quest carries the player to — closes the RANK step (and the quest) when reached. */
    val TARGET_TITLE = Title.MINISTER

    // Persisted BY ORDINAL — never reorder without a migration.
    enum class Step(val objective: String) {
        NONE("(not started)"),
        DRILL("Toughen up: raise your Hitpoints to $HP_TARGET."),
        GEAR("Return to General Zo for a survival kit."),
        FIELD("Endure the Fight Cave — survive to wave $FIELD_WAVE; ::warpsurvival tracks it."),
        REPORT("Return to General Zo with word of the trial."),
        RANK("Earn your Ministry and buy it at Duke Horacio — command pays: lead marches and raids, farm the ladder's elite for their rares, hunt the deep wild."),
        DONE("War-Prep — Survival mastered: you can outlast a collapsing front."),
    }

    /** The player's current step (NONE until the chain begins). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[WARPREP_SURVIVAL_STEP_ATTR] ?: 0) { Step.NONE }

    fun started(p: Player): Boolean = step(p) != Step.NONE
    fun complete(p: Player): Boolean = step(p) == Step.DONE

    /** The player's best Fight Cave wave so far (the FIELD test's live measure). */
    fun bestWave(p: Player): Int = (p.attr[ARENA_BEST_WAVE_ATTR] ?: 0).coerceAtLeast(0)

    /**
     * Begin the chain. Gated on War-Prep II (Ranged) being finished (this quest carries Lord→Minister).
     * Idempotent.
     */
    fun begin(p: Player) {
        if (step(p) != Step.NONE) return
        if (!WarPrepRanged.complete(p)) return
        advanceTo(p, Step.DRILL)
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
        Step.DRILL -> "${Step.DRILL.objective} (Hitpoints ${p.getSkills().getBaseLevel(Skills.HITPOINTS)}/$HP_TARGET)"
        Step.FIELD -> "${Step.FIELD.objective} (best wave ${bestWave(p)}/$FIELD_WAVE)"
        else -> step(p).objective
    }

    /** Say the objective (login + `::warpsurvival`). */
    fun nudge(p: Player) {
        p.message("<col=801700>War-Prep III — current objective:</col> ${objectiveLine(p)}")
        p.message("General Zo has more for you once it's done. Check it any time with <col=0000ff>::warpsurvival</col>.")
    }

    // --- pillar hooks -------------------------------------------------------------------

    /** GEAR: `GeneralZoPlugin` calls this once he's handed over the survival kit. */
    fun onArmedForTrial(p: Player) {
        if (step(p) != Step.GEAR) return
        advanceTo(p, Step.FIELD)
    }

    /** REPORT → RANK: `GeneralZoPlugin` calls this on the post-trial debrief — pays the survival bounty and
     *  sends the player to Duke Horacio for the rank-up. */
    fun onReportedToZo(p: Player) {
        if (step(p) != Step.REPORT) return
        advanceTo(p, Step.RANK)
    }

    /** FIELD anti-soft-lock: General Zo vouches a player through once they've reached [FIELD_VOUCH_WAVE]
     *  (proof of an honest attempt) but keep dying short of [FIELD_WAVE]. Returns true if he vouched. */
    fun vouchField(p: Player): Boolean {
        if (step(p) != Step.FIELD) return false
        if (bestWave(p) < FIELD_VOUCH_WAVE) return false
        advanceTo(p, Step.REPORT)
        return true
    }

    /** RANK → DONE: `DukeHoracioPlugin` (via `RankPurchase`) calls this on any rank purchase; the quest
     *  closes once the player reaches [TARGET_TITLE]. */
    fun onRankBought(p: Player) {
        if (step(p) != Step.RANK) return
        if (p.title.ordinal >= TARGET_TITLE.ordinal) advanceTo(p, Step.DONE)
    }

    /**
     * Poll, driven by [TIMER]. Detects the Hitpoints milestone, auto-passes the FIELD test when the
     * Fight Cave hiscore reaches [FIELD_WAVE], and closes the RANK step if Minister was reached by any
     * means. Re-arms itself while tracked.
     */
    fun pollTick(p: Player) {
        when (step(p)) {
            Step.DRILL -> if (p.getSkills().getBaseLevel(Skills.HITPOINTS) >= HP_TARGET) { advanceTo(p, Step.GEAR); return }
            Step.FIELD -> if (bestWave(p) >= FIELD_WAVE) { advanceTo(p, Step.REPORT); return }
            Step.RANK -> if (p.title.ordinal >= TARGET_TITLE.ordinal) { advanceTo(p, Step.DONE); return }
            else -> {}
        }
        if (isTracked(step(p))) {
            p.timers[TIMER] = POLL_TICKS
        }
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[WARPREP_SURVIVAL_STEP_ATTR] = next.ordinal
        when (next) {
            Step.RANK -> grantRankPurse(p)
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>War-Prep III — next objective:</col> ${objectiveLine(p)}")
        }
    }

    /** GEAR briefing payload: hand the soldier the survival kit — armour, food, brews and restores. */
    fun armForTrial(p: Player) {
        for (piece in SURVIVAL_ARMOUR) giveItem(p, piece, 1)
        for ((item, amount) in SURVIVAL_SUPPLIES) giveItem(p, item, amount)
    }

    /** Outcome of a DRILL-step top-up request — drives General Zo's line. */
    enum class TopUp { NOT_NEEDED, DRILLED }

    private const val MAX_TOPUPS = 2

    /**
     * Anti-soft-lock for the DRILL step: Hitpoints trains through the combat the player already does,
     * so there is no consumable to hand out — but a player who can't be bothered to grind it isn't
     * soft-locked. On a bounded [MAX_TOPUPS] number of dry visits below [HP_TARGET], General Zo
     * "hardens" the soldier, granting the remaining Hitpoints XP directly (unfarmable — XP, not items).
     */
    fun toughenUp(p: Player): TopUp {
        if (step(p) != Step.DRILL) return TopUp.NOT_NEEDED
        if (p.getSkills().getBaseLevel(Skills.HITPOINTS) >= HP_TARGET) return TopUp.NOT_NEEDED
        val used = p.attr[WARPREP_SURVIVAL_DRILL_TOPUPS_ATTR] ?: 0
        if (used < MAX_TOPUPS) {
            // First visits: just push them to keep fighting (HP comes with combat).
            p.attr[WARPREP_SURVIVAL_DRILL_TOPUPS_ATTR] = used + 1
            return TopUp.NOT_NEEDED
        }
        val need = org.alter.game.model.skill.SkillSet.getXpForLevel(HP_TARGET) -
            p.getSkills().getCurrentXp(Skills.HITPOINTS)
        if (need > 0) p.addXp(Skills.HITPOINTS, need)
        return TopUp.DRILLED
    }

    /** RANK entry: General Zo's survival bounty — pay for the trial; the Ministry is earned. */
    private fun grantRankPurse(p: Player) {
        giveItem(p, COINS, SURVIVAL_BOUNTY)
        p.message("<col=801700>General Zo pays your survival bounty: ${"%,d".format(SURVIVAL_BOUNTY)} coins.</col> The ${TARGET_TITLE.display}'s ${"%,d".format(TARGET_TITLE.cost)} you'll earn in command — marches, raids, the ladder's elite — then buy it at Duke Horacio.")
    }

    private fun grantCompletion(p: Player) {
        // A small survival cache as a keepsake perk — TUNE.
        giveItem(p, "item.saradomin_brew4", 4)
        giveItem(p, "item.super_restore4", 2)
        p.message("<col=801700>War-Prep — Survival complete!</col> You've proven you can outlast a collapsing front. The realm's highest ranks — and the King's endgame — are within your reach now.")
    }

    /** One-line progress report (`::warpsurvival` and General Zo's chatter). */
    fun statusLine(p: Player): String = when (step(p)) {
        Step.NONE -> "War-Prep III — Survival: finish War-Prep II (Ranged) first."
        Step.DRILL -> "War-Prep III — Survival: raise your Hitpoints to <col=801700>$HP_TARGET</col> (currently ${p.getSkills().getBaseLevel(Skills.HITPOINTS)})."
        Step.FIELD -> "War-Prep III — Survival: survive the Fight Cave to wave <col=801700>$FIELD_WAVE</col> (best so far ${bestWave(p)})."
        Step.DONE -> "War-Prep III — Survival: <col=4f9b4f>complete</col>."
        else -> "War-Prep III — Survival — current objective: ${step(p).objective}"
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
