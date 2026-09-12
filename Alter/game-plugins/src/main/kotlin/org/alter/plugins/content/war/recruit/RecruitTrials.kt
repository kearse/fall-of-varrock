package org.alter.plugins.content.war.recruit

import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.message
import org.alter.game.action.EquipAction
import org.alter.game.model.Tile
import org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR
import org.alter.game.model.attr.RECRUIT_TRIAL_STEP_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.points
import org.alter.rscm.RSCM.getRSCM

/**
 * **The Last Free City** (Main Story Quest 1 — `docs/quests/the-last-free-city.md`), running on
 * the Recruit Trials state machine. The player-facing quest is the story of a probe attack on
 * Lumbridge; the implementation is the same four-pillar onboarding chain it always was:
 *
 *  1. **FIGHT**  — hold the east goblin camp beside the Knights of Lumbridge (kill 5 goblins).
 *  2. **RANK**   — Duke Horacio recognises the defence with the first feudal rank.
 *  3. **SLAY**   — Vannaka's cleanup contract: hunt the goblins that scattered.
 *  4. **SUPPLY** — replace what the defence consumed: mine copper+tin, smelt a bronze bar, smith
 *     a bronze dagger, hand it to the Quartermaster (skilling supplies the war).
 *  5. **DEBRIEF** — report to Sergeant Damien, who names the war (Varrock fell) and points north.
 *
 * This object holds the pure state machine; [RecruitTrialsPlugin] owns the wiring (the Sergeant
 * NPC, the muster kit, the death/timer hooks). Detection is deliberately low-coupling: FIGHT rides
 * the additive `onAnyNpcDeath` list, RANK is a single notify line in `DukeHoracioPlugin`, and
 * SLAY/SUPPLY are polled from player state by a scoped per-player timer.
 *
 * All state is persistent, so the chain survives a relog and — once [Step.DONE] — never re-fires.
 * The Sergeant's greeting itself is additionally gated on the session-only NEW_ACCOUNT_ATTR.
 */
object RecruitTrials {

    /** The player-facing quest name (the journal, the chat prefix, the native quest-tab row). */
    const val QUEST_NAME = "The Last Free City"

    /** Drives the SLAY/SUPPLY state poll while the recruit is on those steps. */
    val TRIAL_TIMER = TimerKey()

    /**
     * Opens Sergeant Damien's alarm dialogue for a player. Assigned by [RecruitTrialsPlugin] (the
     * dialogue is a private, suspend, QueueTask-scoped function it owns). FirstLoginFlow calls this
     * to run the Sergeant's script once the recruit has finished the intro video and confirmed their
     * character — the last beat of onboarding.
     */
    var greet: ((Player) -> Unit)? = null

    /** How often (ticks) the SLAY/SUPPLY poll runs. Cheap: only armed during those steps. */
    private const val POLL_TICKS = 3

    /** Goblins to kill for the FIGHT step (the probe attack on the east camp). */
    const val GOBLIN_GOAL = 5

    // The SUPPLY step walks the recruit through the whole gather→process→supply loop in The Mire:
    // mine copper + tin, smelt a bronze bar at the furnace, smith a bronze dagger at the anvil, then
    // hand that FINISHED good to the Quartermaster (the war-supply sink takes finished goods, not raw
    // ore). Vannaka hands over a hammer + pickaxe so every station works. Item keys / War Effort.
    private const val COPPER_ORE = "item.copper_ore"
    private const val TIN_ORE = "item.tin_ore"
    private const val BRONZE_BAR = "item.bronze_bar"
    private const val BRONZE_DAGGER = "item.bronze_dagger" // the finished good the recruit hands in
    private const val HAMMER = "item.hammer"               // needed to smith — granted with the contract
    private const val PICKAXE = "item.bronze_pickaxe"      // needed to mine — granted if they lack one

    // The muster kit Damien presses into the recruit's hands before the east camp: a battered wooden
    // shield (equipped on the spot) and a bundle of bronze knives — very low-tier, immediately
    // understandable, nothing valuable. The knives are throwable ammunition, so a whole bundle goes
    // out rather than one expendable blade.
    private const val MUSTER_SHIELD = "item.wooden_shield"
    private const val MUSTER_KNIFE = "item.bronze_knife"
    private const val MUSTER_KNIVES = 50

    /** Small War Effort the Quartermaster logs for the recruit's supply drop (teaches the gather→hand-in loop). */
    private const val DELIVER_WAR_EFFORT = 5

    // Rewards (master design brief §10 leaves exact numbers open — sensible defaults). The Sergeant
    // pays a soldier's coin for holding the camp — enough to buy the first rank from the Duke;
    // finishing the whole chain grants the recruit's first War Effort.
    private const val REWARD_REPORT_COINS = 10_000
    private const val REWARD_WAR_EFFORT = 50

    /**
     * The east Lumbridge goblin camp — the opening battlefield. The Knights of Lumbridge garrison
     * (`GoblinCampPlugin`) skirmishes with the ambient camp goblins here; the guaranteed tutorial pack
     * below is spawned around the same camp so the story attack and the living camp are one place.
     * Matches `GoblinCampPlugin.CAMP_CENTRE` and the client journal's FIGHT anchor.
     */
    val CAMP_CENTRE = Tile(3254, 3234, 0)

    /**
     * Spawn tiles of the guaranteed tutorial goblin pack: the outer ring of the camp, spread wide so
     * a fresh account never pulls more than a goblin or two at once, mixed in among the knights'
     * posts. The ambient camp goblins are presence-gated (they despawn when nobody is around), so
     * without this pack a solo new player's very first objective could dead-end with nothing to
     * kill. [RecruitTrialsPlugin] spawns them; [isTutorialGoblin] recognises them by spawn tile so the
     * Knights of Lumbridge leave them for the recruits (see `GoblinCampPlugin.skirmishKnights`).
     */
    val TUTORIAL_GOBLIN_TILES: List<Tile> = listOf(
        -5 to 3, 5 to -3, -3 to -5, 3 to 5, -6 to -1, 6 to 2, 1 to -6, -2 to 6,
    ).map { (dx, dz) -> Tile(CAMP_CENTRE.x + dx, CAMP_CENTRE.z + dz, 0) }

    /** True for a goblin of the guaranteed tutorial pack (matched by spawn tile — attributes are
     *  wiped by the engine respawn path, spawn tiles are not). */
    fun isTutorialGoblin(npc: Npc): Boolean = npc.spawnTile in TUTORIAL_GOBLIN_TILES

    // Gear rewards (master design brief §1): the recruit gets the bronze kit early, then a steel
    // piece per milestone, finishing in a full steel set — the most a Commoner may wear. Gear goes
    // into the bag (overflowing to the bank if it's full).
    private val BRONZE_KIT = arrayOf("item.bronze_full_helm", "item.bronze_platebody", "item.bronze_platelegs", "item.bronze_kiteshield")
    private const val STEEL_HELM = "item.steel_full_helm"
    private const val STEEL_BODY = "item.steel_platebody"
    private val STEEL_FINISH = arrayOf("item.steel_scimitar", "item.steel_platelegs", "item.steel_kiteshield")

    /**
     * The chain's steps, in story order. The `objective` is the journal line (chat "next objective"
     * message, `::trials`, Vannaka's "orders first" nudge, the client journal's objective line).
     *
     * **Ordinal contract:** the step ordinal is persisted per player ([RECRUIT_TRIAL_STEP_ATTR]).
     * [Step.DEBRIEF] was added after launch and therefore sits AFTER [Step.DONE] in declaration
     * order — inserting it before DONE would have turned every finished player's saved `11` into an
     * unfinished DEBRIEF. Story order is RETURN → DEBRIEF → DONE regardless; see [clientOrdinal]
     * for the wire ordinal the client journal receives.
     */
    enum class Step(val objective: String) {
        TALK("Lumbridge is under attack. Sergeant Damien is calling for every pair of hands by the castle gate."),
        FIGHT("Help the Knights of Lumbridge defeat the goblins attacking the east camp."),
        REPORT("The attack has been pushed back. Report to Sergeant Damien."),
        RANK("I stood for Lumbridge. Duke Horacio can recognise my service and grant my first rank."),
        SLAY("Vannaka wants the surviving attackers hunted down before they regroup."),
        MINE_BRIEF("The stragglers are dealt with. Report back to Vannaka."),
        SUPPLY("The defence consumed equipment and supplies. I need to help replace them in The Mire — mine copper and tin."),
        SMELT("Smelt the copper and tin into a bronze bar at the furnace in The Mire."),
        SMITH("Smith the bronze bar into a dagger at the anvil in The Mire."),
        DELIVER("Deliver the weapon I made to the Quartermaster for the War Effort."),
        RETURN("The Quartermaster has my dagger. Report back to Vannaka."),
        DONE("Lumbridge survived the probe. I entered the day a Peasant and ended it in the service of the last free city. The war lies north."),
        DEBRIEF("The immediate danger has passed. Report to Sergeant Damien."),
    }

    /**
     * The ordinal published to the client journal (varp 4610, bits 0-5): story order, so the
     * client's linear step list stays simple — RETURN 10 → DEBRIEF 11 → DONE 12. Must match
     * `LofQuest.LAST_FREE_CITY` in the client.
     */
    fun clientOrdinal(s: Step): Int = when (s) {
        Step.DEBRIEF -> Step.DONE.ordinal
        Step.DONE -> Step.DEBRIEF.ordinal
        else -> s.ordinal
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
            s == Step.SUPPLY || s == Step.SMELT || s == Step.SMITH || s == Step.DELIVER ||
            s == Step.RETURN || s == Step.DEBRIEF

    /**
     * Guidance arrows are drawn **client-side** by the Quest Journal plugin (`lofquests`), which
     * auto-guides the active objective and can be toggled off for free play. The server no longer
     * draws its own over-tile/over-npc hint arrows for the chain — this just clears any arrow left
     * over from before the change (harmless once none is set).
     */
    fun updateHintArrow(p: Player) {
        p.clearHintArrow()
    }

    // --- pillar hooks -------------------------------------------------------------------

    /**
     * TALK → FIGHT: Damien presses the muster kit into the recruit's hands — the wooden shield goes
     * straight onto their arm (the starter scimitar is one-handed, so the slot is free), the knife
     * bundle into the pack. Called by the Sergeant's alarm dialogue right before [advanceTo] FIGHT,
     * with no suspending line between the two, so an early chat-close can't re-claim the kit.
     */
    fun grantMusterKit(p: Player) {
        if (step(p) != Step.TALK) return
        val shield = runCatching { getRSCM(MUSTER_SHIELD) }.getOrNull()
        if (shield != null) {
            val equipped = runCatching { EquipAction.equip(p, Item(shield)) }.getOrNull() == EquipAction.Result.SUCCESS
            if (!equipped) giveItem(p, MUSTER_SHIELD, 1)
        }
        giveItem(p, MUSTER_KNIFE, MUSTER_KNIVES)
        p.message("<col=801700>Sergeant Damien hands you a battered wooden shield and a bundle of bronze knives.</col>")
    }

    /** FIGHT: called by [RecruitTrialsPlugin] for each goblin the recruit helps put down. */
    fun onGoblinKill(p: Player) {
        if (step(p) != Step.FIGHT) return
        val kills = (p.attr[RECRUIT_GOBLIN_KILLS_ATTR] ?: 0) + 1
        p.attr[RECRUIT_GOBLIN_KILLS_ATTR] = kills
        if (kills >= GOBLIN_GOAL) {
            p.message("<col=801700>$QUEST_NAME:</col> goblins defeated $kills/$GOBLIN_GOAL. The attackers begin falling back.")
            advanceTo(p, Step.REPORT)
        } else {
            p.message("<col=801700>$QUEST_NAME:</col> goblins defeated $kills/$GOBLIN_GOAL.")
        }
    }

    /** REPORT: the Sergeant pays out for holding the camp — a soldier's coin for the rank + the bronze kit. */
    fun grantReportReward(p: Player) {
        if (step(p) != Step.REPORT) return
        giveItem(p, "item.coins_995", REWARD_REPORT_COINS)
        giveBag(p, *BRONZE_KIT) // the bronze kit — wearable now, upgraded to steel as the chain goes on
        p.message("<col=801700>The Sergeant hands you ${"%,d".format(REWARD_REPORT_COINS)} coins and a bronze kit.</col> Take the coin to Duke Horacio — he'll recognise your service with a rank.")
        advanceTo(p, Step.RANK)
    }

    /** RANK: `DukeHoracioPlugin` calls this when the recruit claims their first rank — a Commoner may
     *  bear steel, so start the steel set with a helm. */
    fun onBuyRank(p: Player) {
        if (step(p) != Step.RANK) return
        giveBag(p, STEEL_HELM)
        p.message("<col=801700>Rank reward:</col> a steel full helm — a Commoner may wear steel.")
        advanceTo(p, Step.SLAY)
    }

    /** SLAY: `SlayerPlugin` calls this when the recruit COMPLETES the cleanup contract — send them
     *  back to Vannaka for the supply contract. */
    fun onSlayerTaskComplete(p: Player) {
        if (step(p) == Step.SLAY) advanceTo(p, Step.MINE_BRIEF)
    }

    /** MINE_BRIEF: `SlayerPlugin` calls this when the recruit reports back to Vannaka after the
     *  stragglers — the combat-contract reward (a steel platebody) plus the tools for the supply run
     *  (so mining and smithing work on a fresh account), then Vannaka hands out the supply contract. */
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
        // War Effort + Realm Supplies + the service ledger, exactly like a real depot hand-in.
        org.alter.plugins.content.economy.SupplyDepot.credit(p, DELIVER_WAR_EFFORT)
        advanceTo(p, Step.RETURN)
        return true
    }

    /** RETURN: `SlayerPlugin` calls this when the recruit reports back to Vannaka after the supply drop —
     *  he pays out the steel that completes the set, banks the supply pack, and sends them to Damien. */
    fun grantFinalReward(p: Player) {
        if (step(p) != Step.RETURN) return
        giveBag(p, *STEEL_FINISH) // steel scimitar + legs + kiteshield — completes the full steel set
        depositSupplyPack(p)      // coin + food + potions + the Book of Commands, to the bank
        p.message("<col=801700>Vannaka signs off your supply run and kits you out:</col> a steel scimitar, platelegs and kiteshield. You now have a full steel set — a Commoner's finest.")
        advanceTo(p, Step.DEBRIEF)
    }

    /** DEBRIEF: `RecruitTrialsPlugin` calls this from Damien's finale — the quest completes here. */
    fun onDebriefed(p: Player) {
        if (step(p) != Step.DEBRIEF) return
        advanceTo(p, Step.DONE)
    }

    /**
     * State poll, driven by [TRIAL_TIMER]; re-arms itself while the recruit is on a tracked step.
     * Most steps advance via an explicit hook (goblin kill / buy rank / slayer complete / quartermaster
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
            p.message("<col=801700>$QUEST_NAME — next objective:</col> ${next.objective}")
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
        p.message("<col=801700>Quest complete: $QUEST_NAME.</col> Lumbridge is still ours.")
        p.message("Reward: <col=801700>$REWARD_WAR_EFFORT War Effort</col> (total ${p.points(PointKind.WAR_EFFORT)}).")
        p.message("Keep fighting, slaying and supplying the war to climb the ranks — Vannaka has your next drills.")
    }
}
