package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.game.model.attr.CONQUEST_STEP_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints

/**
 * **King of Lumbridge** — the endgame conquest quest (the top of the feudal ladder, Act III). It
 * answers "what now?" the moment a player buys the [King][Title.KING] rank from Duke Horacio: the
 * realm's other quests carried them up the ranks, and this one finally puts the crown to use by
 * marching a full army on **Fallen Varrock** and breaking its garrison — a temporary battlefield
 * victory, never a capture: the city stays fallen (design authority 03 §2/§3).
 *
 * The quest is a thin guided wrapper over the war's existing conquest machinery ([CampaignCommandPlugin]'s
 * `::conquest`, the [RealmSupply] meter and [CampaignDirector]'s victory resolution) — it adds no new
 * gates, it just teaches the loop step by step:
 *
 *  1. **SUPPLY** — the realm must be stocked before it can march; skill the Mire / hand supplies to a
 *     Quartermaster until the war-stores reach a conquest's [CampaignTier.CONQUEST.supplyCost].
 *  2. **LAUNCH** — gather the war-chest and launch the conquest of Fallen Varrock (`::conquest varrock`).
 *  3. **WIN**    — lead the army to victory: break Fallen Varrock's garrison.
 *
 * This is the pure state machine; [ConquestPlugin] owns the wiring (login resume, the supply poll),
 * [RankPurchase.buy] auto-begins it on the King rank-up, [CampaignCommandPlugin] reports the launch
 * and [CampaignDirector] reports the win. Its live step is published to the Quest Journal
 * (client hint arrows + native quest tab) by [org.alter.plugins.content.quests.QuestJournal].
 *
 * State is a single persistent step ordinal ([CONQUEST_STEP_ATTR]); the chain survives relogs and
 * never re-fires once [Step.DONE].
 */
object Conquest {

    /** Drives the per-player poll while on a tracked step (watches the realm supply meter). */
    val TIMER = TimerKey()
    private const val POLL_TICKS = 5 // ~3s — the supply meter moves slowly; no need to poll tight

    /** The realm war-supply a conquest needs (and the SUPPLY step's goal). Sized off the war ladder. */
    val SUPPLY_TARGET = CampaignTier.CONQUEST.supplyCost

    /** One-time completion reward — the King's political currency. TUNE (the conquest itself already
     *  pays a large coin pool + spoils + prestige; this is just the quest's accolade on top). */
    private const val COMPLETION_PRESTIGE = 100

    // Persisted BY ORDINAL. Never reorder without a migration (the ordinal is the save value + the
    // Quest Journal varp the client reads).
    enum class Step(val objective: String) {
        NONE("(not started)"),
        SUPPLY("Stock the Realm Supplies to $SUPPLY_TARGET for the march on Fallen Varrock — skill the Mire and hand finished goods to a Quartermaster (::supply to check)."),
        LAUNCH("Muster your army and launch the conquest of Fallen Varrock — gather your war-chest, then command ::conquest varrock."),
        WIN("Lead your army to victory — break Fallen Varrock's garrison. The field is yours for a day; the city stays fallen."),
        DONE("King of Lumbridge — Fallen Varrock's garrison was broken under your banner. The realm marches at your word."),
    }

    /** The player's current step (NONE until they reach King and the chain begins). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[CONQUEST_STEP_ATTR] ?: 0) { Step.NONE }

    fun started(p: Player): Boolean = step(p) != Step.NONE
    fun complete(p: Player): Boolean = step(p) == Step.DONE

    /**
     * Begin the chain. Gated on the player actually being [King][Title.KING] — the quest is the
     * endgame accolade for reaching the top rung, so it only opens once the crown is bought.
     * Idempotent; the login hook catches anyone who reached King before this quest existed.
     */
    fun begin(p: Player) {
        if (step(p) != Step.NONE) return
        if (p.title != Title.KING) return
        advanceTo(p, Step.SUPPLY)
    }

    /** On login, re-arm the poll timer if on a tracked step. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) p.timers[TIMER] = POLL_TICKS
    }

    /** Steps the poll runs on — those with a live objective the poll watches or refreshes. */
    private fun isTracked(s: Step): Boolean = s == Step.SUPPLY || s == Step.LAUNCH || s == Step.WIN

    // --- hooks --------------------------------------------------------------------------

    /**
     * LAUNCH: [CampaignCommandPlugin] calls this when a commander successfully launches an operation.
     * Only a CONQUEST-tier launch advances the quest (a King learning the campaign/raid tiers first
     * shouldn't skip ahead). Also catches SUPPLY in case the poll hadn't ticked the meter over yet —
     * a successful launch proves the realm was stocked.
     */
    fun onLaunched(p: Player, tier: CampaignTier) {
        if (tier != CampaignTier.CONQUEST) return
        if (step(p) == Step.SUPPLY || step(p) == Step.LAUNCH) advanceTo(p, Step.WIN)
    }

    /** WIN → DONE: [CampaignDirector] calls this for the sponsor when a CONQUEST-tier op is WON. */
    fun onConquestWon(p: Player) {
        if (step(p) == Step.SUPPLY || step(p) == Step.LAUNCH || step(p) == Step.WIN) advanceTo(p, Step.DONE)
    }

    /** Poll, driven by [TIMER]. Advances SUPPLY once the realm war-stores reach the conquest cost, and
     *  re-arms itself while tracked. */
    fun pollTick(p: Player) {
        if (step(p) == Step.SUPPLY && RealmSupply.meter() >= SUPPLY_TARGET) {
            advanceTo(p, Step.LAUNCH)
            return
        }
        if (isTracked(step(p))) p.timers[TIMER] = POLL_TICKS
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[CONQUEST_STEP_ATTR] = next.ordinal
        when (next) {
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>King of Lumbridge — next objective:</col> ${next.objective}")
        }
    }

    private fun grantCompletion(p: Player) {
        p.addPoints(PointKind.PRESTIGE, COMPLETION_PRESTIGE)
        p.message("<col=ffcc00>King of Lumbridge complete!</col> Fallen Varrock's garrison broke before your army, ${p.address} — the city stays fallen, and the realm will march again.")
        p.message("The realm's armies march at your word — command more conquests with <col=0000ff>::conquest</col>, and claim your spoils with <col=0000ff>::claim</col>.")
    }

    /** One-line progress report (`::kingquest` and any NPC chatter). */
    fun statusLine(p: Player): String = when (step(p)) {
        Step.NONE -> "King of Lumbridge: reach the rank of King (buy it from Duke Horacio) to begin."
        Step.SUPPLY -> "King of Lumbridge: stock the Realm Supplies — <col=4f9b4f>${RealmSupply.meter()}/$SUPPLY_TARGET</col> for a conquest."
        Step.DONE -> "King of Lumbridge: <col=4f9b4f>complete</col> — you broke Fallen Varrock's garrison."
        else -> "King of Lumbridge — current objective: ${step(p).objective}"
    }
}
