package org.alter.plugins.content.war.warprep

import org.alter.api.Skills
import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.message
import org.alter.api.ext.setNpcHintArrow
import org.alter.api.ext.setTileHintArrow
import org.alter.game.model.attr.WARPREP_STEP_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import kotlin.math.abs
import org.alter.plugins.content.magic.spellbook.unlockMageBooks
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
 * ([Player.unlockMageBooks]) — and finally back to Vannaka for the debrief that closes the quest.
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

    /** Protect from Magic unlocks at Prayer 37 — the Prayer step's target. */
    const val PRAYER_TARGET = 37

    // The church altar is 3.5x (see PrayerAltarPlugin) → dragon bones give 252 xp each. One full
    // inventory (28) proved enough to clear level 37 in testing; anyone who still falls short is
    // covered by the bounded top-up/drill below, so no soft-lock. TUNABLE.
    const val PRAYER_BONES = 28
    private const val DRAGON_BONES = "item.dragon_bones"

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

    // Guidance anchors. Vannaka runs the briefings; the church altar is the Prayer objective; the
    // Void Knight (the Wizard Tower game-master, mainland end of the bridge) is the Magic
    // objective. (x, z, plane) — plane drives floor-aware routing.
    private const val VANNAKA_NPC = "npc.vannaka"
    private val VANNAKA_TILE = Triple(3219, 3215, 0)
    private val CHURCH_ALTAR_TILE = Triple(3242, 3207, 0) // Lumbridge church altar (PrayerAltarPlugin's home altar)
    private const val KNIGHT_NPC = "npc.void_knight"
    private val KNIGHT_TILE = Triple(3113, 3208, 0) // WizardTowerPlugin's knight spawn

    private const val ARROW_HEIGHT = 130
    private const val NEAR_TILES = 14

    enum class Step(val objective: String) {
        NONE("(not started)"),
        PRAYER("Train Prayer to $PRAYER_TARGET at the Lumbridge church altar — use the dragon bones on it."),
        GEAR("Return to Vannaka to be armed for the Wizard Tower."),
        TOWER("Speak to the Void Knight at the Wizard Tower bridge — clear the tower and take the grimoire from the Archmage."),
        RETURN("Return to Vannaka with word of the grimoire."),
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

    /** On login, re-arm the poll timer + refresh the arrow if on a tracked step. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) {
            p.timers[TIMER] = POLL_TICKS
            updateHintArrow(p)
        }
    }

    /** Steps the poll runs on — those with a live objective (progress watched and/or arrow refreshed). */
    private fun isTracked(s: Step): Boolean = s == Step.PRAYER || s == Step.GEAR || s == Step.TOWER || s == Step.RETURN

    /** Point the guidance arrow at the current objective. NPC anchors ([pointAtNpc]) show a TILE
     *  arrow from any distance — all the way from Lumbridge — and snap to the npc itself up close.
     *  Muted guidance (free play) skips the arrow entirely — the mute toggle cleared it already. */
    fun updateHintArrow(p: Player) {
        if (org.alter.plugins.content.quests.QuestJournal.muted(p)) return
        when (step(p)) {
            Step.PRAYER -> p.setTileHintArrow(CHURCH_ALTAR_TILE.first, CHURCH_ALTAR_TILE.second, ARROW_HEIGHT)
            Step.GEAR -> pointAtNpc(p, VANNAKA_NPC, VANNAKA_TILE)
            Step.TOWER -> pointAtNpc(p, KNIGHT_NPC, KNIGHT_TILE)
            Step.RETURN -> pointAtNpc(p, VANNAKA_NPC, VANNAKA_TILE)
            Step.NONE -> {}
            Step.DONE -> p.clearHintArrow()
        }
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

    /** RETURN: `SlayerPlugin` (Vannaka) calls this on the post-tower debrief — closes the quest. */
    fun onReportedToVannaka(p: Player) {
        if (step(p) != Step.RETURN) return
        advanceTo(p, Step.DONE)
    }

    /**
     * Poll, driven by [TIMER]. Detects the Prayer milestone from the player's level (no coupling to the
     * altar plugin) and refreshes the guidance arrow on every tracked step. Re-arms itself while tracked.
     */
    fun pollTick(p: Player) {
        when (step(p)) {
            Step.PRAYER -> if (p.getSkills().getBaseLevel(Skills.PRAYER) >= PRAYER_TARGET) { advanceTo(p, Step.GEAR); return }
            else -> {}
        }
        if (isTracked(step(p))) {
            updateHintArrow(p)
            p.timers[TIMER] = POLL_TICKS
        }
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], run its side effects (gifts, timers, unlock) and announce the objective. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[WARPREP_STEP_ATTR] = next.ordinal
        when (next) {
            Step.PRAYER -> giveItem(p, DRAGON_BONES, PRAYER_BONES) // bones for the church-altar training
            Step.RETURN -> grantGrimoire(p) // the spellbook unlock lands the moment the grimoire is taken
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        if (isTracked(next)) p.timers[TIMER] = POLL_TICKS
        if (next != Step.NONE && next != Step.DONE) {
            p.message("<col=801700>War-Prep — next objective:</col> ${next.objective}")
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

    /** Outcome of a PRAYER-step top-up request — drives Vannaka's line. */
    enum class TopUp { NOT_NEEDED, BONES, DRILLED }

    /** A top-up hands out at most this many extra bone batches; beyond it Vannaka drills XP instead
     *  (bones are tradeable, so an uncapped top-up is an infinite dragon-bones faucet). */
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
        if (p.inventory.getItemCount(id) > 0 || p.bank.getItemCount(id) > 0) return TopUp.NOT_NEEDED
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

    private fun grantCompletion(p: Player) {
        p.unlockMageBooks() // idempotent — already unlocked on the RETURN step
        p.message("<col=801700>War-Prep — Magic complete!</col> You've proven you can survive the front's magic. The war's raids are opening to you.")
    }

    // --- guidance helpers (mirrors RecruitTrials) ---------------------------------------

    private fun pointAtNpc(p: Player, npcKey: String, anchor: Triple<Int, Int, Int>) {
        val id = runCatching { getRSCM(npcKey) }.getOrNull()
        val npc = if (id != null) nearestNpc(p, id, anchor.first, anchor.second) else null
        if (npc != null &&
            npc.tile.height == p.tile.height &&
            abs(npc.tile.x - p.tile.x) <= NEAR_TILES &&
            abs(npc.tile.z - p.tile.z) <= NEAR_TILES
        ) {
            p.setNpcHintArrow(npc.index)
        } else {
            p.setTileHintArrow(anchor.first, anchor.second, ARROW_HEIGHT)
        }
    }

    private fun nearestNpc(p: Player, id: Int, x: Int, z: Int): Npc? {
        var best: Npc? = null
        var bestD = Int.MAX_VALUE
        p.world.npcs.forEach { n ->
            if (n.id != id || n.isDead()) return@forEach
            val d = abs(n.tile.x - x) + abs(n.tile.z - z)
            if (d < bestD) { bestD = d; best = n }
        }
        return best
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
