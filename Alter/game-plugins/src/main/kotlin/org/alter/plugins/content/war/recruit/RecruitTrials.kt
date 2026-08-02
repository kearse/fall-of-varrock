package org.alter.plugins.content.war.recruit

import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.message
import org.alter.api.ext.setNpcHintArrow
import org.alter.api.ext.setTileHintArrow
import org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR
import org.alter.game.model.attr.RECRUIT_TRIAL_STEP_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import kotlin.math.abs
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.points
import org.alter.rscm.RSCM.getRSCM

/**
 * **Recruit Trials** — the First 10 Minutes onboarding (master design brief §1).
 *
 * A four-step chain that hands a fresh citizen of Lumbridge the three pillars of the war
 * and leaves them already on the feudal ladder with their first **War Effort**:
 *
 *  1. **FIGHT**  — kill 5 goblins at the always-on frontier ring.
 *  2. **RANK**   — report to Duke Horacio (learn the rank ladder).
 *  3. **SLAY**   — take a war-contract from Vannaka.
 *  4. **SUPPLY** — run the full Mire loop: mine copper+tin, smelt a bronze bar, smith a bronze
 *     dagger, and hand that finished good to the Quartermaster (skilling supplies the war).
 *
 * This object holds the pure state machine; [RecruitTrialsPlugin] owns the wiring (the
 * Sergeant NPC, the welcome, the death/timer hooks). Detection is deliberately
 * low-coupling: FIGHT rides the additive `onAnyNpcDeath` list, RANK is a single notify
 * line in `DukeHoracioPlugin`, and SLAY/SUPPLY are polled from player state by a scoped
 * per-player timer (no edits to the Slayer or skilling plugins).
 *
 * All state is persistent, so the chain survives a relog and — once [Step.DONE] — never
 * re-fires (the welcome itself is additionally gated on the session-only NEW_ACCOUNT_ATTR).
 */
object RecruitTrials {

    /** Drives the SLAY/SUPPLY state poll while the recruit is on those steps. */
    val TRIAL_TIMER = TimerKey()

    /**
     * Opens the Recruiting Sergeant's welcome dialogue for a player. Assigned by
     * [RecruitTrialsPlugin] (the dialogue is a private, suspend, QueueTask-scoped function it owns).
     * FirstLoginFlow calls this to run the Sergeant's first-forces script once the recruit has
     * finished the intro video and confirmed their character — the last beat of onboarding.
     */
    var greet: ((Player) -> Unit)? = null

    /** How often (ticks) the SLAY/SUPPLY poll runs. Cheap: only armed during those steps. */
    private const val POLL_TICKS = 3

    /** Goblins to kill for the FIGHT trial. */
    const val GOBLIN_GOAL = 5

    // The SUPPLY trial walks the recruit through the whole gather→process→supply loop in The Mire:
    // mine copper + tin, smelt a bronze bar at the furnace, smith a bronze dagger at the anvil, then
    // hand that FINISHED good to the Quartermaster (the war-supply sink takes finished goods, not raw
    // ore). The tutorial hands over a hammer + pickaxe so every station works. Item keys / War Effort.
    private const val COPPER_ORE = "item.copper_ore"
    private const val TIN_ORE = "item.tin_ore"
    private const val BRONZE_BAR = "item.bronze_bar"
    private const val BRONZE_DAGGER = "item.bronze_dagger" // the finished good the recruit hands in
    private const val HAMMER = "item.hammer"               // needed to smith — granted with the contract
    private const val PICKAXE = "item.bronze_pickaxe"      // needed to mine — granted if they lack one

    /** Small War Effort the Quartermaster logs for the recruit's supply drop (teaches the gather→hand-in loop). */
    private const val DELIVER_WAR_EFFORT = 5

    // Rewards (master design brief §10 leaves exact numbers open — sensible defaults). The Sergeant
    // pays coins for clearing the back woods — enough to buy the first rank from the Duke; finishing
    // the whole chain grants the recruit's first War Effort.
    private const val REWARD_REPORT_COINS = 10_000
    private const val REWARD_WAR_EFFORT = 50

    // The named-objective NPCs get an over-head NPC hint arrow (tracks them). The Triples are the
    // FALLBACK tile (and the "find the npc nearest here" anchor) if the npc can't be located.
    private const val SERGEANT_NPC = "npc.sergeant_damien"
    private const val DUKE_NPC = "npc.duke_horacio"
    private const val VANNAKA_NPC = "npc.vannaka"
    private const val QUARTERMASTER_NPC = "npc.quartermaster" // the Supply Officer in The Mire crypt
    // anchor = (x, z, PLANE the npc stands on). Plane drives the floor-aware routing in pointAtNpc.
    private val SERGEANT_TILE = Triple(3217, 3220, 0)
    private val DUKE_TILE = Triple(3220, 3211, 0) // Duke stands in the GE hub's desk ring, west of the pillar
    private val VANNAKA_TILE = Triple(3222, 3212, 0) // GE hub desk ring, north face
    private val QUARTERMASTER_TILE = Triple(3248, 3193, 0) // Supply Officer's post by the crypt in The Mire

    // FIGHT has no single npc to track (a field of goblins), so it uses a tile arrow. TUNABLE.
    private val FIGHT_TILE = Triple(3193, 3221, 0)

    // Where the castle rats (the SLAY contract target) scurry, for the "go kill the rats" arrow. TUNABLE.
    private val RAT_AREA_TILE = Triple(3206, 3205, 0)

    // SUPPLY loop stations in The Mire, our new skilling grounds (all surface, plane 0) — the whole
    // gather→process→supply chain is co-located here, so the recruit learns it in one tight loop and
    // gets their first look at the skills area. Tiles match SwampHubPlugin's furnace/anvil spawns. TUNABLE.
    private val MINE_ROCK_TILE = Triple(3237, 3189, 0)     // copper rock (tin sits on the adjacent tile)
    private val FURNACE_TILE = Triple(3237, 3192, 0)       // smelt the bronze bar here
    private val ANVIL_TILE = Triple(3238, 3196, 0)         // smith the bronze dagger here

    // Gear rewards (master design brief §1): the recruit gets the bronze kit early, then a steel
    // piece per milestone, finishing in a full steel set — the most a Commoner may wear. Gear goes
    // into the bag (overflowing to the bank if it's full).
    private val BRONZE_KIT = arrayOf("item.bronze_full_helm", "item.bronze_platebody", "item.bronze_platelegs", "item.bronze_kiteshield")
    private const val STEEL_HELM = "item.steel_full_helm"
    private const val STEEL_BODY = "item.steel_platebody"
    private val STEEL_FINISH = arrayOf("item.steel_scimitar", "item.steel_platelegs", "item.steel_kiteshield")

    /** Tile-arrow elevation (≈ over-head height) and the range within which we switch to the
     *  target npc's own over-head arrow (npc arrows don't render off-screen). TUNABLE. */
    private const val ARROW_HEIGHT = 130
    private const val NEAR_TILES = 14

    enum class Step(val objective: String) {
        TALK("Speak to the Recruiting Sergeant by the Lumbridge gate."),
        FIGHT("Clear the back woods — kill $GOBLIN_GOAL goblins that slipped our defences."),
        REPORT("Report back to the Recruiting Sergeant."),
        RANK("Claim your first rank from Duke Horacio — in the market by the Slayer Master."),
        SLAY("Take a war-contract from Vannaka."),
        MINE_BRIEF("Report back to Vannaka for your next contract."),
        SUPPLY("Mine copper and tin ore in The Mire — our skilling grounds south-east of the castle."),
        SMELT("Smelt a bronze bar at the furnace in The Mire."),
        SMITH("Smith a bronze dagger at the anvil in The Mire."),
        DELIVER("Hand the bronze dagger to the Quartermaster (the Supply Officer) in The Mire."),
        RETURN("Report back to Vannaka for your reward."),
        DONE("Recruit Trials complete — you are a citizen-soldier of Lumbridge."),
    }

    /** The recruit's current step (TALK by default; never null once they log in new). */
    fun step(p: Player): Step = Step.values().getOrElse(p.attr[RECRUIT_TRIAL_STEP_ATTR] ?: 0) { Step.TALK }

    /** True while the chain is still in progress (used to gate the tracker/timer). */
    fun inProgress(p: Player): Boolean = step(p) != Step.DONE

    /** Initialise a brand-new recruit at [Step.TALK] (idempotent — won't reset progress). */
    fun begin(p: Player) {
        if (p.attr[RECRUIT_TRIAL_STEP_ATTR] == null) {
            p.attr[RECRUIT_TRIAL_STEP_ATTR] = Step.TALK.ordinal
        }
    }

    /** On login, re-arm the poll/arrow-refresh timer if the recruit is on a tracked step. */
    fun resumeOnLogin(p: Player) {
        if (isTracked(step(p))) p.timers[TRIAL_TIMER] = POLL_TICKS
        updateHintArrow(p)
    }

    /** Steps the [TRIAL_TIMER] poll runs on — those whose progress is watched or whose arrow flips to
     *  an over-head npc arrow on arrival. The skilling steps (SUPPLY/SMELT/SMITH) also detect here. */
    private fun isTracked(s: Step): Boolean =
        s == Step.REPORT || s == Step.RANK || s == Step.SLAY || s == Step.MINE_BRIEF ||
            s == Step.SUPPLY || s == Step.SMELT || s == Step.SMITH || s == Step.DELIVER || s == Step.RETURN

    /**
     * Guidance arrows are now drawn **client-side** by the Quest Journal plugin (`lofquests`), which
     * auto-guides the active objective and can be toggled off for free play. The server no longer
     * draws its own over-tile/over-npc hint arrows for the trials — this just clears any arrow left
     * over from before the change (harmless once none is set).
     */
    fun updateHintArrow(p: Player) {
        p.clearHintArrow()
    }

    // --- pillar hooks -------------------------------------------------------------------

    /** FIGHT: called by [RecruitTrialsPlugin] for each goblin the recruit kills. */
    fun onGoblinKill(p: Player) {
        if (step(p) != Step.FIGHT) return
        val kills = (p.attr[RECRUIT_GOBLIN_KILLS_ATTR] ?: 0) + 1
        p.attr[RECRUIT_GOBLIN_KILLS_ATTR] = kills
        if (kills >= GOBLIN_GOAL) {
            advanceTo(p, Step.REPORT)
        } else {
            p.message("<col=801700>Recruit Trials:</col> goblins killed $kills/$GOBLIN_GOAL.")
        }
    }

    /** REPORT: the Sergeant pays out for clearing the back woods — coins for the rank + the bronze kit. */
    fun grantReportReward(p: Player) {
        if (step(p) != Step.REPORT) return
        giveItem(p, "item.coins_995", REWARD_REPORT_COINS)
        giveBag(p, *BRONZE_KIT) // the bronze kit — wearable now, upgraded to steel as the trials go on
        p.message("<col=801700>The Sergeant hands you ${"%,d".format(REWARD_REPORT_COINS)} coins and a bronze kit.</col> Take the coin to Duke Horacio for your rank.")
        advanceTo(p, Step.RANK)
    }

    /** RANK: `DukeHoracioPlugin` calls this when the recruit buys their first rank — a Commoner may
     *  bear steel, so start the steel set with a helm. */
    fun onBuyRank(p: Player) {
        if (step(p) != Step.RANK) return
        giveBag(p, STEEL_HELM)
        p.message("<col=801700>Rank reward:</col> a steel full helm — a Commoner may wear steel.")
        advanceTo(p, Step.SLAY)
    }

    /** SLAY: `SlayerPlugin` calls this when the recruit COMPLETES their (combat) contract — send them
     *  back to Vannaka for the supply contract. */
    fun onSlayerTaskComplete(p: Player) {
        if (step(p) == Step.SLAY) advanceTo(p, Step.MINE_BRIEF)
    }

    /** MINE_BRIEF: `SlayerPlugin` calls this when the recruit reports back to Vannaka after the rats —
     *  the combat-contract reward (a steel platebody) plus the tools for the supply run (so mining and
     *  smithing work on a fresh account), then Vannaka hands out the supply contract. */
    fun onMiningAssigned(p: Player) {
        if (step(p) != Step.MINE_BRIEF) return
        giveBag(p, STEEL_BODY)
        ensureTool(p, PICKAXE) // so the recruit can mine...
        ensureTool(p, HAMMER)  // ...and smith, even with an empty pack
        p.message("<col=801700>Contract reward:</col> a steel platebody. Vannaka also hands you a pickaxe and a hammer for the supply run.")
        advanceTo(p, Step.SUPPLY)
    }

    /**
     * DELIVER: `WarlordsArmouryPlugin` calls this when the recruit hands the finished bronze dagger to
     * the Quartermaster. This mirrors the live war-supply loop — the dagger is consumed and logged as a
     * little War Effort — then the recruit is sent back to Vannaka to close out the contract. Returns
     * true if the drop-off happened (they had the dagger); false if not, so the caller can nudge them
     * back to the anvil.
     */
    fun onSupplyDelivered(p: Player): Boolean {
        if (step(p) != Step.DELIVER) return false
        val id = runCatching { getRSCM(BRONZE_DAGGER) }.getOrNull() ?: return false
        if (p.inventory.getItemCount(id) < 1) return false
        p.inventory.remove(id, 1)
        p.addPoints(PointKind.WAR_EFFORT, DELIVER_WAR_EFFORT)
        org.alter.plugins.content.war.RealmSupply.contribute(p.world, DELIVER_WAR_EFFORT) // feeds the campaign-gating meter
        advanceTo(p, Step.RETURN)
        return true
    }

    /** RETURN: `SlayerPlugin` calls this when the recruit reports back to Vannaka after the supply drop —
     *  he pays out the steel that completes the set, banks the supply pack, and the trials finish. */
    fun grantFinalReward(p: Player) {
        if (step(p) != Step.RETURN) return
        giveBag(p, *STEEL_FINISH) // steel scimitar + legs + kiteshield — completes the full steel set
        depositSupplyPack(p)      // coin + food + potions + the Book of Commands, to the bank
        p.message("<col=801700>Vannaka signs off your supply run and kits you out:</col> a steel scimitar, platelegs and kiteshield. You now have a full steel set — a Commoner's finest.")
        advanceTo(p, Step.DONE)
    }

    /**
     * State poll, driven by [TRIAL_TIMER]; re-arms itself while the recruit is on a tracked step.
     * Most trials advance via an explicit hook (goblin kill / buy rank / slayer complete / quartermaster
     * hand-in). The Mire skilling steps (SUPPLY→SMELT→SMITH) are detected HERE from the recruit's pack —
     * no edits to the mining/smithing plugins — and every tracked step also gets its guidance arrow
     * refreshed so it flips to an over-head npc arrow on arrival.
     */
    fun pollTick(p: Player) {
        when (step(p)) {
            Step.SUPPLY -> if (invHas(p, COPPER_ORE) && invHas(p, TIN_ORE)) { advanceTo(p, Step.SMELT); return } // ore dug
            Step.SMELT -> if (invHas(p, BRONZE_BAR)) { advanceTo(p, Step.SMITH); return }                       // bar smelted
            Step.SMITH -> if (invHas(p, BRONZE_DAGGER)) { advanceTo(p, Step.DELIVER); return }                  // dagger smithed
            else -> {}
        }
        if (isTracked(step(p))) {
            updateHintArrow(p) // re-evaluate distance so the arrow flips to over-head on arrival
            p.timers[TRIAL_TIMER] = POLL_TICKS
        }
    }

    // --- transitions --------------------------------------------------------------------

    /** Advance to [next], handle its side effects (snapshots, timers, reward) and announce it. */
    fun advanceTo(p: Player, next: Step) {
        p.attr[RECRUIT_TRIAL_STEP_ATTR] = next.ordinal
        when (next) {
            Step.FIGHT -> p.attr[RECRUIT_GOBLIN_KILLS_ATTR] = 0
            Step.DONE -> grantCompletion(p)
            else -> {}
        }
        // Tracked steps arm the poll timer — it drives the skilling detection and refreshes the arrow.
        if (isTracked(next)) p.timers[TRIAL_TIMER] = POLL_TICKS
        if (next != Step.DONE) {
            p.message("<col=801700>Recruit Trials — next objective:</col> ${next.objective}")
        }
        updateHintArrow(p)
    }

    /**
     * Deposit the supply-contract pack straight into the recruit's BANK (so a full inventory can never
     * make them miss anything — e.g. the Book of Commands): coin, food, potions and the Book of
     * Commands. Items are unnoted (the bank holds unnoted) and guarded against missing keys.
     */
    private fun depositSupplyPack(p: Player) {
        val pack = listOf(
            "item.coins_995" to 10_000,
            "item.trout" to 50,
            "item.salmon" to 25,
            "item.attack_potion3" to 5,
            "item.strength_potion3" to 5,
            "item.prayer_potion3" to 3,
            "item.book" to 1, // Book of Commands — read it (or type ::commands) for every command
        )
        for ((key, amount) in pack) runCatching { p.bank.add(getRSCM(key), amount) }
        p.message("<col=801700>Deposited to your bank:</col> 10,000 coins, food, potions, and the <col=801700>Book of Commands</col> (read it or type <col=801700>::commands</col>).")
    }

    /** Add [amount] of [key] to the recruit's bag; whatever doesn't fit overflows to the bank. */
    private fun giveItem(p: Player, key: String, amount: Int = 1) {
        runCatching {
            val id = getRSCM(key)
            val tx = p.inventory.add(id, amount, assureFullInsertion = false)
            val left = amount - tx.completed
            if (left > 0) p.bank.add(id, left)
        }
    }

    /** Add one of each [keys] item to the recruit's bag, overflowing to the bank if the bag is full. */
    private fun giveBag(p: Player, vararg keys: String) {
        for (key in keys) giveItem(p, key, 1)
    }

    /** True if the recruit is carrying at least [amount] of item [key] (defensive against missing keys). */
    private fun invHas(p: Player, key: String, amount: Int = 1): Boolean {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return false
        return p.inventory.getItemCount(id) >= amount
    }

    /** Give the recruit tool [key] only if they aren't already carrying one (no duplicate pickaxes/hammers). */
    private fun ensureTool(p: Player, key: String) {
        if (!invHas(p, key)) giveItem(p, key, 1)
    }

    private fun grantCompletion(p: Player) {
        p.addPoints(PointKind.WAR_EFFORT, REWARD_WAR_EFFORT)
        p.message("<col=801700>Recruit Trials complete!</col> Welcome to the war, soldier.")
        p.message("Reward: <col=801700>$REWARD_WAR_EFFORT War Effort</col> (total ${p.points(PointKind.WAR_EFFORT)}).")
        p.message("Keep fighting, slaying and supplying the war to climb the ranks.")
    }
}
