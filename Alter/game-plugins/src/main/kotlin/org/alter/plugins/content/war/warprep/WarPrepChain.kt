package org.alter.plugins.content.war.warprep

import org.alter.api.Skills
import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.message
import org.alter.api.ext.setNpcHintArrow
import org.alter.api.ext.setTileHintArrow
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.WARPREP_STEP_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import kotlin.math.abs
import org.alter.plugins.content.magic.spellbook.unlockMageBooks
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * **War-Prep quest chain** — the onboarding that picks up where the [Recruit Trials] leave off and
 * readies a citizen-soldier for the war's **raids** (raid access is gated on [complete]). It is a
 * short chain of "prep quests", each teaching a pillar the player will need on the front. This is
 * the pure state machine; [WarPrepChainPlugin] owns the wiring (login resume, the poll timer).
 *
 * **Quest 1 — Magic** (built): the only way to wield Ancient/Lunar/Arceuus magic is to clear the
 * Wizard Tower. Vannaka first drills the recruit's **Prayer to 37** (unlocking Protect from Magic,
 * which they'll need against the tower's mages) at the Lumbridge church altar, arms them with a
 * staff/robes/runes/prayer potions, then sends them to the **Void Knight** at the tower bridge to
 * enter the minigame and take the **grimoire** — which permanently unlocks the special spellbooks
 * ([Player.unlockMageBooks]) — then back to Vannaka for the debrief. Vannaka pays out a purse
 * that covers the recruit's **next feudal rank** ([RANK_REWARD_COINS]), and the final step walks
 * them to **Duke Horacio** to buy it — so the tower flows straight into a rank-up and the heavier
 * armour it unlocks (the progression loop the whole server runs on).
 *
 * Quests 2-5 (Ranged, survivability, a graduation war-game, …) are scaffolded: append [Step]s and
 * their hooks, and the raid gate ([complete]) moves to the real end of the chain.
 *
 * All state is a single persistent step ordinal ([WARPREP_STEP_ATTR]); the chain survives relogs
 * and never re-fires once [Step.DONE].
 */
object WarPrepChain {

    /** Drives the per-player state poll while on a tracked step (refreshes the arrow + detects Prayer). */
    val TIMER = TimerKey()
    private const val POLL_TICKS = 3

    /**
     * The objective was announced exactly once, when the step was entered — so a recruit who logged
     * out on PRAYER came back to silence and no idea the Wizard Tower was waiting behind it. Nudge
     * the live objective on login and again every [NUDGE_TICKS] while the step stays unfinished.
     * Session-only countdown: a fresh login always nudges, and the timer restarts from there.
     */
    private const val NUDGE_TICKS = 500 // ~5 minutes
    private val NUDGE_COUNTDOWN = AttributeKey<Int>()

    /** Protect from Magic unlocks at Prayer 37 — the Prayer step's target. */
    const val PRAYER_TARGET = 37

    // The church altar is 3.5x (see PrayerAltarPlugin) → dragon bones give 252 xp each. One full
    // inventory (28) proved enough to clear level 37 in testing; anyone who still falls short is
    // covered by the bounded top-up/drill below, so no soft-lock. TUNABLE.
    const val PRAYER_BONES = 28
    private const val DRAGON_BONES = "item.dragon_bones"
    private const val DRAGON_BONES_NOTED = "item.dragon_bones_noted"

    // Magic-quest gear: an elemental staff (free element rune) + mystic robes + a stack of runes —
    // a few hundred of each BASIC element so a fresh recruit is never dry on casts, plus the
    // combat-spell ammo — and a crate of noted prayer potions to keep Protect from Magic up for the
    // whole tower. All TUNABLE / defensive on missing keys.
    private const val MAGIC_STAFF = "item.mystic_fire_staff"
    private val MAGIC_ROBES = arrayOf("item.mystic_hat", "item.mystic_robe_top", "item.mystic_robe_bottom")
    private val MAGIC_RUNES = arrayOf(
        "item.air_rune" to 500, "item.water_rune" to 300, "item.earth_rune" to 300, "item.fire_rune" to 300,
        "item.mind_rune" to 500, "item.chaos_rune" to 300, "item.death_rune" to 150,
    )
    private const val PRAYER_POTIONS = "item.prayer_potion4_noted"
    private const val PRAYER_POTION_COUNT = 100

    /**
     * Vannaka's payout for taking the tower: a purse that covers the recruit's NEXT rank. The chain
     * follows the Recruit Trials (which end with the Commoner rank bought), so the next rung is
     * [Title.SQUIRE] — read from the ladder so the purse never drifts out of sync with the price.
     */
    val RANK_REWARD_COINS = Title.SQUIRE.cost
    private const val COINS = "item.coins_995"

    // Guidance anchors. Vannaka runs the briefings; the church altar is the Prayer objective; the
    // Void Knight (the Wizard Tower game-master, mainland end of the bridge) is the Magic
    // objective. (x, z, plane) — plane drives floor-aware routing.
    private const val VANNAKA_NPC = "npc.vannaka"
    private val VANNAKA_TILE = Triple(3222, 3212, 0) // GE hub desk ring, north face
    private val CHURCH_ALTAR_TILE = Triple(3242, 3207, 0) // Lumbridge church altar (PrayerAltarPlugin's home altar)
    private const val KNIGHT_NPC = "npc.void_knight"
    private val KNIGHT_TILE = Triple(3113, 3208, 0) // WizardTowerPlugin's knight spawn
    private const val DUKE_NPC = "npc.duke_horacio"
    private val DUKE_TILE = Triple(3220, 3211, 0) // RecruitTrials' Duke anchor (GE hub desk ring, west of the pillar)

    private const val ARROW_HEIGHT = 130
    private const val NEAR_TILES = 14

    // Persisted BY ORDINAL: RANK's insertion shifted DONE (5→6), so a character saved on the old
    // DONE reads RANK once — harmless: the poll's title check flips Squire+ straight back to DONE,
    // and anyone below just gets the (legitimate) pointer to the Duke for their next rank.
    enum class Step(val objective: String) {
        NONE("(not started)"),
        PRAYER("Train Prayer to $PRAYER_TARGET at the Lumbridge church altar — use the dragon bones on it."),
        GEAR("Return to Vannaka to be armed for the Wizard Tower."),
        TOWER("Speak to the Void Knight at the Wizard Tower bridge — clear the tower and take the grimoire from the Archmage."),
        RETURN("Return to Vannaka with word of the grimoire."),
        RANK("Take your purse to Duke Horacio and buy your next rank — it unlocks heavier armour."),
        DONE("War-Prep — Magic mastered: the Ancient, Lunar and Arceuus spellbooks are unlocked."),
    }

    /** The player's current step (NONE until the chain begins). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[WARPREP_STEP_ATTR] ?: 0) { Step.NONE }

    fun started(p: Player): Boolean = step(p) != Step.NONE
    fun complete(p: Player): Boolean = step(p) == Step.DONE

    /** Raid access gate: the recruit must finish the war-prep chain before joining the war's raids. */
    fun raidReady(p: Player): Boolean = complete(p)

    /** Begin the chain (called from Vannaka's Recruit-Trials finale). Idempotent — won't restart it. */
    fun begin(p: Player) {
        if (step(p) != Step.NONE) return
        advanceTo(p, Step.PRAYER)
    }

    /** On login, re-arm the poll timer + refresh the arrow if on a tracked step, and remind the
     *  player what they're actually meant to be doing. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) {
            p.timers[TIMER] = POLL_TICKS
            updateHintArrow(p)
            nudge(p)
        }
    }

    /** The current objective, with live progress where the step has a measurable target. */
    fun objectiveLine(p: Player): String {
        val s = step(p)
        if (s != Step.PRAYER) return s.objective
        return "${s.objective} (Prayer ${p.getSkills().getBaseLevel(Skills.PRAYER)}/$PRAYER_TARGET)"
    }

    /** Say the objective and restart the nudge countdown. */
    fun nudge(p: Player) {
        p.attr[NUDGE_COUNTDOWN] = NUDGE_TICKS
        p.message("<col=801700>War-Prep — current objective:</col> ${objectiveLine(p)}")
        p.message("Vannaka has more for you once it's done. Check it any time with <col=ffae00>::warprep</col>.")
    }

    /** Steps the poll runs on — those with a live objective (progress watched and/or arrow refreshed). */
    private fun isTracked(s: Step): Boolean =
        s == Step.PRAYER || s == Step.GEAR || s == Step.TOWER || s == Step.RETURN || s == Step.RANK

    /**
     * Guidance arrows are now drawn **client-side** by the Quest Journal plugin (`lofquests`); the
     * server no longer draws its own hint arrows for War-Prep. This just clears any arrow left over
     * from before the change.
     */
    fun updateHintArrow(p: Player) {
        p.clearHintArrow()
    }

    // --- pillar hooks -------------------------------------------------------------------

    /** GEAR: `SlayerPlugin` (Vannaka) calls this once he's armed the recruit for the tower. */
    fun onArmedForTower(p: Player) {
        if (step(p) != Step.GEAR) return
        advanceTo(p, Step.TOWER)
    }

    /** TOWER: the Wizard Tower minigame calls this when the recruit takes the grimoire on their
     *  first clear. The spellbooks unlock on the spot; the arrow then routes back to Vannaka
     *  ([Step.RETURN]) for the debrief that closes the quest. */
    fun onGrimoireTaken(p: Player) {
        if (step(p) != Step.TOWER) return
        advanceTo(p, Step.RETURN)
    }

    /** RETURN: `SlayerPlugin` (Vannaka) calls this on the post-tower debrief — pays the rank purse
     *  ([RANK_REWARD_COINS]) and sends the recruit to Duke Horacio for the rank-up. */
    fun onReportedToVannaka(p: Player) {
        if (step(p) != Step.RETURN) return
        advanceTo(p, Step.RANK)
    }

    /** RANK: `DukeHoracioPlugin` calls this when the player buys a rank — the rank-up (and the
     *  armour tier it unlocks) is the chain's final beat, so the quest closes here. */
    fun onRankBought(p: Player) {
        if (step(p) != Step.RANK) return
        advanceTo(p, Step.DONE)
    }

    /**
     * Poll, driven by [TIMER]. Detects the Prayer milestone from the player's level (no coupling to the
     * altar plugin) and refreshes the guidance arrow on every tracked step. Re-arms itself while tracked.
     */
    fun pollTick(p: Player) {
        when (step(p)) {
            Step.PRAYER -> if (p.getSkills().getBaseLevel(Skills.PRAYER) >= PRAYER_TARGET) { advanceTo(p, Step.GEAR); return }
            // Already at/past the rung the purse was for (tower coin farmed and spent early, or a
            // pre-existing character): nothing left to buy for the quest — close it out.
            Step.RANK -> if (p.title.ordinal >= Title.SQUIRE.ordinal) { advanceTo(p, Step.DONE); return }
            else -> {}
        }
        if (isTracked(step(p))) {
            updateHintArrow(p)
            p.timers[TIMER] = POLL_TICKS
            // Periodic re-nudge so a stalled recruit isn't left guessing (see NUDGE_TICKS).
            val left = (p.attr[NUDGE_COUNTDOWN] ?: NUDGE_TICKS) - POLL_TICKS
            if (left <= 0) nudge(p) else p.attr[NUDGE_COUNTDOWN] = left
        }
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects (gifts, timers, unlock) and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[WARPREP_STEP_ATTR] = next.ordinal
        when (next) {
            Step.PRAYER -> giveItem(p, DRAGON_BONES, PRAYER_BONES) // bones for the church-altar training
            Step.RETURN -> grantGrimoire(p) // the spellbook unlock lands the moment the grimoire is taken
            Step.RANK -> grantRankPurse(p)  // the tower's payout — enough to buy the next rank
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        p.attr[NUDGE_COUNTDOWN] = NUDGE_TICKS // this announcement counts as the nudge
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>War-Prep — next objective:</col> ${objectiveLine(p)}")
        }
        updateHintArrow(p)
    }

    /** GEAR briefing payload: hand the recruit the staff, robes, runes and noted prayer potions
     *  to fight (and keep praying) through the tower. */
    fun armForTower(p: Player) {
        giveItem(p, MAGIC_STAFF, 1)
        for (robe in MAGIC_ROBES) giveItem(p, robe, 1)
        for ((rune, amount) in MAGIC_RUNES) giveItem(p, rune, amount)
        giveItem(p, PRAYER_POTIONS, PRAYER_POTION_COUNT)
    }

    // --- quest-locked bones -------------------------------------------------------------
    //
    // Vannaka's dragon bones are a QUEST GIFT, not income: while the recruit is on the PRAYER
    // step they are locked to the player — no banking, dropping, trading, GE/Trading-Post
    // listing or shop-vendoring. The only intended exit is the church altar. Without the lock,
    // every stash the top-up check can't see (a mule trade, a GE escrow, a noted withdrawal)
    // turns "I'm out of bones" into a dragon-bones faucet. The lock ends with the step, so any
    // spare bones become ordinary items once Prayer 37 is reached.

    /** One-line refusal shown by every blocked sink (bank/drop/trade/GE/shop). */
    const val BONES_LOCKED_MESSAGE =
        "Vannaka's dragon bones are for the church altar — offer them there. He'll want them accounted for."

    // Deposit-inventory tries every backpack slot in one click; without dedup a locked stack of
    // 28 bones would print 28 refusals. One warning per game cycle. Session-only.
    private val BONES_WARN_CYCLE = AttributeKey<Int>()

    /** True while [itemId] is the quest gift's dragon bones (noted or not) and [p] is still on
     *  the PRAYER step — i.e. the item is quest-locked and must be refused by item sinks. */
    fun bonesLocked(p: Player, itemId: Int): Boolean {
        if (step(p) != Step.PRAYER) return false
        return itemId == runCatching { getRSCM(DRAGON_BONES) }.getOrNull() ||
            itemId == runCatching { getRSCM(DRAGON_BONES_NOTED) }.getOrNull()
    }

    /** Say [BONES_LOCKED_MESSAGE], at most once per game cycle (multi-slot actions spam otherwise). */
    fun warnBonesLocked(p: Player) {
        val cycle = p.world.currentCycle
        if (p.attr[BONES_WARN_CYCLE] == cycle) return
        p.attr[BONES_WARN_CYCLE] = cycle
        p.message(BONES_LOCKED_MESSAGE)
    }

    /** Outcome of a PRAYER-step top-up request — drives Vannaka's line. */
    enum class TopUp { NOT_NEEDED, BONES, DRILLED }

    /** A top-up hands out at most this many extra bone batches; beyond it Vannaka drills XP instead.
     *  Backstop behind the [bonesLocked] seal, for exits the seal can't cover (e.g. death drops). */
    private const val MAX_TOPUPS = 2
    private const val TOPUP_BONES = 30

    /**
     * Anti-soft-lock for a recruit who ran their bones dry before Prayer [PRAYER_TARGET] — bounded so
     * it can't be farmed: the first [MAX_TOPUPS] dry visits hand out [TOPUP_BONES] bones; after that
     * Vannaka "drills" the recruit — granting the REMAINING Prayer xp directly. XP isn't an item, so
     * there's nothing left to drop/trade and re-claim.
     */
    fun topUpBones(p: Player): TopUp {
        if (step(p) != Step.PRAYER) return TopUp.NOT_NEEDED
        if (p.getSkills().getBaseLevel(Skills.PRAYER) >= PRAYER_TARGET) return TopUp.NOT_NEEDED
        val id = runCatching { getRSCM(DRAGON_BONES) }.getOrNull() ?: return TopUp.NOT_NEEDED
        // "Dry" means dry: bones anywhere the player can still reach — noted or not, bag or bank —
        // refuse the top-up. (Withdraw-as-note used to hide the stack from this check entirely.)
        val noted = runCatching { getRSCM(DRAGON_BONES_NOTED) }.getOrNull()
        val owned = p.inventory.getItemCount(id) + p.bank.getItemCount(id) +
            (noted?.let { p.inventory.getItemCount(it) + p.bank.getItemCount(it) } ?: 0)
        if (owned > 0) return TopUp.NOT_NEEDED
        val used = p.attr[org.alter.game.model.attr.WARPREP_BONE_TOPUPS_ATTR] ?: 0
        if (used < MAX_TOPUPS) {
            p.attr[org.alter.game.model.attr.WARPREP_BONE_TOPUPS_ATTR] = used + 1
            giveItem(p, DRAGON_BONES, TOPUP_BONES)
            return TopUp.BONES
        }
        // Cap reached: close the objective with direct XP — unexploitable and un-soft-lockable.
        val need = org.alter.game.model.skill.SkillSet.getXpForLevel(PRAYER_TARGET) -
            p.getSkills().getCurrentXp(Skills.PRAYER)
        if (need > 0) p.addXp(Skills.PRAYER, need)
        return TopUp.DRILLED
    }

    /** RETURN entry: the grimoire's reward is immediate — the books unlock as it's lifted. */
    private fun grantGrimoire(p: Player) {
        p.unlockMageBooks() // permanently unlock Ancient/Lunar/Arceuus
        p.message("<col=801700>The grimoire's power is yours:</col> the <col=801700>Ancient, Lunar and Arceuus</col> spellbooks are unlocked. Swap books with <col=801700>::spellbook</col>.")
    }

    /** RANK entry: Vannaka's payout for the tower — a purse sized to the next rank on the ladder,
     *  so the quest flows straight into the Duke's rank-up and the armour tier it unlocks. */
    private fun grantRankPurse(p: Player) {
        giveItem(p, COINS, RANK_REWARD_COINS)
        p.message("<col=801700>Vannaka pays you ${"%,d".format(RANK_REWARD_COINS)} coins for the tower</col> — enough to buy your next rank from Duke Horacio.")
    }

    private fun grantCompletion(p: Player) {
        p.unlockMageBooks() // idempotent — already unlocked on the RETURN step
        p.message("<col=801700>War-Prep — Magic complete!</col> You've proven you can survive the front's magic. You may now fight beside the Knights of Lumbridge when they march on the enemy.")
        p.message("<col=801700>Watch for the Knight-Captain's muster call</col> — it sounds before every march — and answer it with <col=ffae00>::march</col>.")
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
