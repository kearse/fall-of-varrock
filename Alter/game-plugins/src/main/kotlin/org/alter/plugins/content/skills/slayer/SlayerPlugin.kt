package org.alter.plugins.content.skills.slayer

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.SLAYER_INTRO_DONE_ATTR
import org.alter.game.model.attr.SLAYER_STREAK_ATTR
import org.alter.game.model.attr.SLAYER_TASK_LEFT_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.attr.SLAYER_TASK_TOTAL_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.points
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Server half of the client-drawn **War Contracts** window (`lofcontracts`): Vannaka's board —
 * the active combat contract with its kill count, the active resource contract, the streak and
 * the War Effort balance — replacing his five-way chat menu.
 *
 * State rides one `~LOFCON~` CONSOLE line (parsed + hidden client-side):
 *   `~LOFCON~<streak>|<warEffort>|<combatName or ->|<left>|<total>|<resourceName or ->|<resLeft>|<resSkill or ->`
 * The window opens on a varp 4626 pulse; actions come back as `::con <combat|resource|rewards>`
 * → `conclick` (handled in [SlayerPlugin], which owns the assignment logic).
 */
object ContractMenu {
    /** Overlay-open varp (docs/overlay-design-system.md §8) — pulsed to 0, never persisted. */
    const val OPEN_VARP = 4626

    private const val PREFIX = "~LOFCON~"

    fun open(p: Player) {
        push(p)
        p.setVarp(OPEN_VARP, 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }

    /** Push the contract-board state (also called after an assignment so the window updates). */
    fun push(p: Player) {
        val taskKey = p.attr[SLAYER_TASK_NPC_ATTR]
        val left = p.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        val total = p.attr[SLAYER_TASK_TOTAL_ATTR] ?: 0
        val combat = if (taskKey != null && left > 0) {
            SlayerTasks.ALL.firstOrNull { it.npcName == taskKey }?.display ?: "your target"
        } else null
        val res = ResourceContracts.current(p)
        val sb = StringBuilder(PREFIX)
            .append(p.attr[SLAYER_STREAK_ATTR] ?: 0).append('|')
            .append(p.points(PointKind.WAR_EFFORT)).append('|')
            .append(combat ?: "-").append('|').append(if (combat != null) left else 0).append('|')
            .append(if (combat != null) total else 0).append('|')
            .append(res?.first?.display ?: "-").append('|').append(res?.second ?: 0).append('|')
            .append(res?.first?.skill ?: "-")
        p.message(sb.toString(), org.alter.api.ChatMessageType.CONSOLE)
    }
}

/**
 * **Slayer** (content roadmap Phase 1). Turael, the starter Slayer master, lives in
 * Lumbridge (the server home): he assigns a random eligible task from [SlayerTasks],
 * tracks kills via [onAnyNpcDeath] (no per-npc binding, so it never clashes with other
 * death hooks), awards Slayer xp per kill and **Slayer points** (streak-scaled) on
 * completion, and runs a **points reward shop** — the sink that gives the points value.
 *
 * Slayer is the biggest content multiplier: it turns the whole NPC roster into a
 * purposeful, solo grind that drives the consumables economy.
 */
class SlayerPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    // Vannaka — Turael/Mazchna have no click options in this cache; Vannaka has
    // [Talk-to, Assignment, Trade, Rewards].
    private val master = "npc.vannaka"
    private val masterTile = Triple(3223, 3218, 0) // station 4 (Services), east side of the home hub
    private val rewardShop = "War Rewards"

    /** Resolved master id, passed to every dialogue line explicitly: the contract window's
     *  `::con` actions run WITHOUT an interacting npc (the default chatNpc resolution throws). */
    private val masterId: Int by lazy { resolveId(master) }

    /** Vannaka line with the head/name forced, safe from any entry point (window or Talk-to). */
    private suspend fun QueueTask.say(p: Player, message: String) =
        chatNpc(p, message, npc = masterId, title = "Vannaka")

    /** Tasks whose npc actually exists in the cache, keyed by RSCM npc key. */
    private val tasks: Map<String, SlayerTask> = SlayerTasks.ALL
        .filter { resolves(it.npcName) }
        .associateBy { it.npcName }

    /**
     * The cache name each task's monster answers to (e.g. "cow"), lower-cased. Many monsters
     * exist under several npc ids that all share one name — cows alone spawn as 2790/2791/2793/2795
     * — so a kill is credited by NAME, not by the single canonical id. Without this, only the one
     * variant [resolveId] happens to return would ever count, and a "kill 24 Cows" contract could
     * eat far more than 24 kills while the counter sat still. Resolved once at load.
     */
    private val taskName: Map<String, String> = tasks.mapNotNull { (key, _) ->
        npcName(resolveId(key))?.let { key to it }
    }.toMap()

    /**
     * Where `::slayertele` drops you for each contract's target — a known-walkable tile in that
     * monster's hunting ground (anchored to real spawns). Keyed by the same RSCM npc key stored in
     * [SLAYER_TASK_NPC_ATTR]. A task with no entry here (e.g. skeletons, which still lack an
     * accessible spawn) just tells the player there's no marker yet. TUNABLE.
     */
    private val taskTeles: Map<String, Tile> = mapOf(
        "npc.goblin" to Tile(3247, 3244, 0),       // goblin field, east of the castle
        "npc.rat_2854" to Tile(3206, 3202, 0),     // castle courtyard rats
        "npc.giant_rat" to Tile(3163, 3173, 0),    // the western swamp
        "npc.giant_spider" to Tile(3246, 3248, 0), // the eastern spider nest
        "npc.man" to Tile(3216, 3219, 0),          // townsfolk by the castle
        "npc.woman" to Tile(3217, 3205, 0),
        "npc.cow" to Tile(3178, 3316, 0),          // the cow field, north-west
        "npc.chicken" to Tile(3172, 3293, 0),      // the farm pen
        "npc.guard" to Tile(3221, 3222, 0),        // castle guards
        "npc.zombie" to Tile(3250, 3187, 0),       // the Mire undead corner (SwampHubPlugin)
    )

    init {
        buildRewardShop()

        spawnNpc(master, x = masterTile.first, z = masterTile.second, height = masterTile.third, walkRadius = 0, direction = Direction.EAST)
        bindTalk()

        // Track every player kill against the killer's active task (cheap: non-player
        // killers and untasked players bail immediately).
        onAnyNpcDeath { onKill(player = (npc.attr[KILLER_ATTR]?.get() as? Player), deadId = npc.id) }

        onCommand("slayer", description = "Show your Slayer task") {
            reportTask(player)
            // Text fallback for the resource contract too — the window is custom-client-only.
            val res = ResourceContracts.current(player)
            if (res != null) {
                player.message("Resource contract: <col=801700>${res.second} ${res.first.display}</col> left (${res.first.skill}).")
            }
        }

        onCommand("contracts", description = "Open the War Contracts window") {
            ContractMenu.open(player)
        }

        // Quality-of-life: lift the player to their current combat contract's hunting ground. Also the
        // practical answer to "where do I even find these?" for every task, not just the ones without an
        // obvious spawn. Free like ::home; only works with an active combat contract.
        onCommand("slayertele", description = "Teleport to your current Slayer task") {
            val taskKey = player.attr[SLAYER_TASK_NPC_ATTR]
            val left = player.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
            if (taskKey == null || left <= 0) {
                player.message("You have no active contract to travel to. See Vannaka for one.")
                return@onCommand
            }
            if (!player.lock.canTeleport()) {
                player.message("You can't teleport right now.")
                return@onCommand
            }
            val name = tasks[taskKey]?.display ?: "your target"
            val dest = taskTeles[taskKey]
            if (dest == null) {
                player.message("There's no marked hunting ground for <col=801700>$name</col> yet.")
                return@onCommand
            }
            player.moveTo(dest)
            player.message("You are lifted to the hunting ground of your <col=801700>$name</col> contract.")
        }
        // The window's action channel ("::con <action>" → conclick). Also testable directly.
        // Viewing (::contracts) works anywhere; ACTIONS keep the walk-to-Vannaka invariant —
        // the token arrives as raw public chat from any tile, like every overlay channel.
        onCommand("conclick", description = "War Contracts window action (client overlay channel)") {
            if (!player.tile.isWithinRadius(org.alter.game.model.Tile(masterTile.first, masterTile.second, masterTile.third), MASTER_RADIUS)) {
                player.message("Vannaka signs contracts at his post, south of the shop hub.")
                return@onCommand
            }
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "combat" -> player.queue { assignTask(player); ContractMenu.push(player) }
                "resource" -> player.queue { assignResource(player); ContractMenu.push(player) }
                "rewards" -> player.openShop(rewardShop)
            }
        }
    }

    private fun bindTalk() {
        val actions = try {
            getNpc(getRSCM(master)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        if (actions.isEmpty()) {
            logger.warn { "Slayer master '$master' has no click options in cache." }
            return
        }
        actions.forEach { opt ->
            when {
                opt.equals("Talk-to", true) -> onNpcOption(master, option = opt) { player.queue { dialog(player) } }
                opt.equals("Assignment", true) -> onNpcOption(master, option = opt) { player.queue { assignTask(player) } }
                opt.equals("Rewards", true) -> onNpcOption(master, option = opt) { player.openShop(rewardShop) }
                opt.equals("Trade", true) -> onNpcOption(master, option = opt) { player.openShop(rewardShop) }
            }
        }
    }

    private suspend fun QueueTask.dialog(p: Player) {
        ensureIntro(p)
        // Intro-quest finale: the recruit returns to Vannaka for the reward + first real assignment.
        if (RecruitTrials.step(p) == RecruitTrials.Step.RETURN) {
            recruitFinale(p)
            return
        }
        // Intro-quest: reported back after the rats — Vannaka hands out the supply (mining) contract.
        if (RecruitTrials.step(p) == RecruitTrials.Step.MINE_BRIEF) {
            recruitMiningBrief(p)
            return
        }
        // Intro-quest SLAY step (master design brief §1): flow straight from the introduction into
        // the first contract — no menu, so the conversation never feels like it reset.
        if (RecruitTrials.step(p) == RecruitTrials.Step.SLAY) {
            assignTask(p)
            return
        }
        // War-Prep Magic quest: Vannaka MUST act on GEAR (arm them for the tower) and RETURN (the
        // post-tower debrief that closes the quest); PRAYER/TOWER just get a one-line nudge before
        // the normal contract menu still opens.
        if (WarPrepChain.step(p) == WarPrepChain.Step.GEAR) {
            warPrepArm(p)
            return
        }
        if (WarPrepChain.step(p) == WarPrepChain.Step.RETURN) {
            warPrepDebrief(p)
            return
        }
        if (WarPrepChain.step(p) == WarPrepChain.Step.PRAYER) warPrepPrayerNudge(p)
        if (WarPrepChain.step(p) == WarPrepChain.Step.TOWER) warPrepTowerNudge(p)
        // War-Prep II (Ranged): Vannaka MUST act on GEAR (arm the marksman kit) and REPORT (the debrief
        // that closes the quest); DRILL/FIELD just get a one-line nudge before the contract menu opens.
        if (WarPrepRanged.step(p) == WarPrepRanged.Step.GEAR) {
            warPrepRangedArm(p)
            return
        }
        if (WarPrepRanged.step(p) == WarPrepRanged.Step.REPORT) {
            warPrepRangedDebrief(p)
            return
        }
        if (WarPrepRanged.step(p) == WarPrepRanged.Step.DRILL) warPrepRangedDrillNudge(p)
        if (WarPrepRanged.step(p) == WarPrepRanged.Step.FIELD) warPrepRangedFieldNudge(p)
        // Outside the quest beats, the contract board is the client-drawn War Contracts window
        // (lofcontracts): active contracts, streak and points at a glance — no options() menu.
        ContractMenu.open(p)
    }

    /** Resource (gathering) contract — the supply side of Vannaka's war-contracts (master design brief §2). */
    private suspend fun QueueTask.assignResource(p: Player) {
        val existing = ResourceContracts.current(p)
        if (existing != null) {
            say(p, "You've still <col=801700>${existing.second} ${existing.first.display}</col> to gather. Bring it in.")
            return
        }
        val assigned = ResourceContracts.assign(p)
        if (assigned == null) {
            say(p, "Nothing I can set you to right now — raise a gathering skill or two and come back.")
            return
        }
        val (task, amount) = assigned
        say(p, "Resource contract: gather <col=801700>$amount ${task.display}</col> — that's ${task.skill} work. You keep what you gather; I just need it done.")
        say(p, "Coin and War Effort when it's complete. Off you go.")
        // Fishing spots default to the best catch, which won't be the contracted one — say so.
        if (task.skill == "Fishing") {
            say(p, "The spots land your best catch by default. Use <col=801700>::fish</col> to pick out ${task.display} — or just click a spot; I've told the water what you're after.")
        }
        // The faucet advertises the ladder: skills already qualify for richer rank-gated work.
        if (ResourceContracts.richerWorkAwaits(p)) {
            say(p, "And ${p.address} — your skills are worth more than this. Buy your next rank from <col=801700>Duke Horacio</col> and I'll commission you for far richer work.")
        }
    }

    /** Intro-quest finale: the recruit returns to Vannaka after their trials for the reward (sent to
     *  the bank) and their first real, post-tutorial assignment. */
    private suspend fun QueueTask.recruitFinale(p: Player) {
        say(p, "Back already — and the contract done. You've passed every trial the realm set you, recruit.")
        say(p, "Here's your reward. I've sent it to your bank, so your pack stays clear for the fights ahead.")
        RecruitTrials.grantFinalReward(p) // steel finish + supplies to bank + completes the trials (War Effort)
        say(p, "From here, war-contracts are a steady trade. Come to me any time and pick your work: a <col=801700>combat contract</col> or a <col=801700>resource contract</col>.")
        say(p, "Combat sharpens your blade; resource contracts will walk you through every gathering skill the realm needs — mining, woodcutting, fishing and more. Either way, you're paid.")
        say(p, "Clear a contract, come back for your pay and the next. Here's your first proper one.")
        assignTask(p) // step is DONE now → a normal random contract, not the tutorial rats
        // Kick straight into the War-Prep chain: Quest 1 is Magic.
        warPrepMagicIntro(p)
    }

    /** War-Prep Quest 1 (Magic) intro — Vannaka pivots from the trials into preparing the recruit for
     *  raids, starting with Prayer/Protect-from-Magic. [WarPrepChain.begin] gifts the dragon bones and
     *  sets the church-altar objective. */
    private suspend fun QueueTask.warPrepMagicIntro(p: Player) {
        say(p, "One last thing, ${p.address}. Contracts keep you sharp, but a raider needs more than a blade — you'll need <col=801700>magic</col>, and the nerve to stand in front of it.")
        say(p, "The front's mages will melt a man who can't pray. So first, train your <col=801700>Prayer to ${WarPrepChain.PRAYER_TARGET}</col> — that's when you can call on <col=801700>Protect from Magic</col>. Here, take these.")
        WarPrepChain.begin(p) // gifts the dragon bones + sets the PRAYER objective/arrow
        say(p, "Dragon bones — <col=801700>offer</col> them on the <col=801700>church altar</col> in Lumbridge, far faster than burying. Follow the marker, and come back to me once Protect from Magic is yours.")
    }

    /** PRAYER step nudge: keep them at the altar; top up bones if they ran dry (bounded — see
     *  [WarPrepChain.topUpBones]), and past the cap drill the remaining Prayer into them directly. */
    private suspend fun QueueTask.warPrepPrayerNudge(p: Player) {
        when (WarPrepChain.topUpBones(p)) {
            WarPrepChain.TopUp.BONES ->
                say(p, "Out of bones already? Here's more — and mind them this time, they don't grow back. Offer them at the <col=801700>church altar</col> until your Prayer reaches <col=801700>${WarPrepChain.PRAYER_TARGET}</col>.")
            WarPrepChain.TopUp.DRILLED -> {
                say(p, "Lost ANOTHER stack? I'm not made of dragon bones, recruit. Kneel — we'll do this the army way.")
                say(p, "Vannaka drills the litany into you until your knees ache. Your <col=801700>Prayer</col> rises to <col=801700>${WarPrepChain.PRAYER_TARGET}</col>.")
            }
            WarPrepChain.TopUp.NOT_NEEDED ->
                say(p, "Train your Prayer to <col=801700>${WarPrepChain.PRAYER_TARGET}</col> at the church altar — offer those dragon bones on it. Then you can pray Protect from Magic, and you'll need it in the tower.")
        }
    }

    /** GEAR step: Prayer's ready — arm the recruit for the Wizard Tower and send them to the
     *  Void Knight who runs the assaults. */
    private suspend fun QueueTask.warPrepArm(p: Player) {
        say(p, "Prayer trained and Protect from Magic ready — good. You'll not walk into a tower of mages unarmed, though.")
        WarPrepChain.armForTower(p) // staff + robes + runes + noted prayer potions
        say(p, "Take this staff, these robes, and a proper stock of runes — <col=801700>air, water, earth and fire</col> by the hundreds, plus the combat runes. And a crate of <col=801700>prayer potions</col>, noted — sip them and <col=801700>Protect from Magic</col> never drops.")
        say(p, "The <col=801700>Wizard Tower</col> stands south-west, across the river. A <col=801700>Void Knight</col> holds the bridge to it — speak to him and he'll send you in. Fight up floor by floor and take the <col=801700>grimoire</col> from the Archmage at the top.")
        say(p, "Follow the marker to the Void Knight — and come back to me once the grimoire's power is yours.")
        WarPrepChain.onArmedForTower(p) // advance to TOWER
    }

    /** TOWER step nudge. */
    private suspend fun QueueTask.warPrepTowerNudge(p: Player) {
        say(p, "The grimoire won't fetch itself, ${p.address}. Follow the marker to the <col=801700>Void Knight</col> at the Wizard Tower bridge — he'll send you in. Clear it floor by floor and take it from the Archmage.")
    }

    /** RETURN step: back from the tower with the grimoire — Vannaka's debrief pays the rank purse
     *  and sends the recruit to Duke Horacio to buy the next rank (the chain's final beat). */
    private suspend fun QueueTask.warPrepDebrief(p: Player) {
        say(p, "Back — and I can smell the scorched robes from here. The grimoire's power is yours, ${p.address}: every spellbook the realm knows, at your call.")
        say(p, "Keep the tower in mind, too. The Void Knight will send you back in whenever you like, and those mages bleed <col=801700>runes</col> — there's no finer place to farm them.")
        say(p, "And the realm pays for a tower taken. Here — <col=801700>${"%,d".format(WarPrepChain.RANK_REWARD_COINS)} coins</col>, a purse fit for your next <col=801700>rank</col>.")
        WarPrepChain.onReportedToVannaka(p) // RETURN → RANK: pays the purse + arrows to the Duke
        say(p, "Take it to <col=801700>Duke Horacio</col> and have him raise you — a higher rank means <col=801700>heavier armour</col> on your back. Follow the marker; you've earned this one.")
    }

    /** War-Prep II (Ranged) DRILL nudge: keep them training the bow; top up arrows if they ran dry
     *  (bounded — see [WarPrepRanged.topUpAmmo]), and past the cap drill the remaining Ranged xp in. */
    private suspend fun QueueTask.warPrepRangedDrillNudge(p: Player) {
        when (WarPrepRanged.topUpAmmo(p)) {
            WarPrepRanged.TopUp.AMMO ->
                say(p, "Out of arrows? Here's more — and mind them. Keep loosing until your <col=801700>Ranged</col> reaches <col=801700>${WarPrepRanged.RANGED_TARGET}</col>.")
            WarPrepRanged.TopUp.DRILLED -> {
                say(p, "Fumbling your quiver AGAIN? Enough. On the line, soldier — we'll drill it into you.")
                say(p, "Vannaka runs you ragged on the range until your <col=801700>Ranged</col> reaches <col=801700>${WarPrepRanged.RANGED_TARGET}</col>.")
            }
            WarPrepRanged.TopUp.NOT_NEEDED ->
                say(p, "A raider holds the line at distance too. Train your <col=801700>Ranged to ${WarPrepRanged.RANGED_TARGET}</col> — loose those arrows — then come back and I'll kit you for the skirmish.")
        }
    }

    /** War-Prep II (Ranged) GEAR step: Ranged is trained — arm the marksman kit and send them to the
     *  skirmish (fell enemies with a ranged weapon). */
    private suspend fun QueueTask.warPrepRangedArm(p: Player) {
        say(p, "Ranged trained — good eye. Now you'll need a proper marksman's kit, not that training bow.")
        WarPrepRanged.armForSkirmish(p) // bow + d'hide + arrows
        say(p, "Take this bow, the hide armour and a full quiver. Get out there and <col=801700>fell ${WarPrepRanged.FIELD_GOAL} of the enemy with a ranged weapon</col> — bow, crossbow, thrown, your choice.")
        say(p, "Prove you can hold a skirmish line, then report back to me. Follow the marker.")
        WarPrepRanged.onArmedForSkirmish(p) // GEAR → FIELD
    }

    /** War-Prep II (Ranged) FIELD nudge. */
    private suspend fun QueueTask.warPrepRangedFieldNudge(p: Player) {
        say(p, "The skirmish isn't won yet, ${p.address}. <col=801700>${WarPrepRanged.fieldKills(p)}/${WarPrepRanged.FIELD_GOAL}</col> felled with a ranged weapon — keep at it, then report back.")
    }

    /** War-Prep II (Ranged) REPORT step: back from the skirmish — Vannaka's debrief pays the rank purse
     *  and sends the soldier to Duke Horacio to rise to Lord. */
    private suspend fun QueueTask.warPrepRangedDebrief(p: Player) {
        say(p, "A clean skirmish — I watched the reports come in. You can hold a line at range now, ${p.address}.")
        say(p, "The realm pays for a soldier who can. Here — <col=801700>${"%,d".format(WarPrepRanged.RANK_REWARD_COINS)} coins</col>, a purse fit to raise you to <col=801700>Lord</col>.")
        WarPrepRanged.onReportedToVannaka(p) // REPORT → RANK: pays the purse
        say(p, "Take it to <col=801700>Duke Horacio</col>. A Lord commands knights — and General Zo will have words for you once you wear the title. Follow the marker.")
    }

    /** Intro-quest: the recruit reports back to Vannaka after the rats — Vannaka rewards the combat
     *  contract (a steel platebody) and hands out the supply (mining) contract. */
    private suspend fun QueueTask.recruitMiningBrief(p: Player) {
        say(p, "Rats cleared — good work. Now hear this: my contracts aren't all blood and steel.")
        chatPlayer(p, "What else is there?")
        say(p, "<col=801700>Resource contracts.</col> The war runs on raw materials as much as it does on soldiers, so I hand out gathering work too — ore, logs, fish, whatever the realm's short on. Same pay: coin and War Effort.")
        chatPlayer(p, "What does the realm need ore for?")
        say(p, "The <col=801700>war</col>, soldier. Supplies arm the campaigns — and when we march on a city like Varrock, everyone who fed the war takes a cut of everything we drag out of it. Their rune, their riches, split among those who supplied and fought.")
        say(p, "That's how a soldier gets rich here. You're not ready to march yet... but you will be. For now — feed the war and earn your place.")
        say(p, "Here's your first resource contract: get to <col=801700>The Mire</col> — our skilling grounds south-east of the castle. Mine copper and tin, smelt a <col=801700>bronze bar</col> at the furnace, then hammer it into a <col=801700>bronze dagger</col> at the anvil.")
        say(p, "Hand that finished dagger to the <col=801700>Quartermaster</col> there — the Supply Officer. That's how supplies really reach the front: gathered, worked, then turned in. Then report back to me. Follow the marker.")
        RecruitTrials.onMiningAssigned(p) // steel platebody + tools reward, advance to the mining step
    }

    /** First-meeting introduction: who Vannaka is and how war-contracts work. Runs once. */
    private suspend fun QueueTask.ensureIntro(p: Player) {
        if (p.attr[SLAYER_INTRO_DONE_ATTR] == true) return
        p.attr[SLAYER_INTRO_DONE_ATTR] = true
        say(p, "So you're the recruit the Sergeant sent down. I'm Vannaka — I hand out the war-contracts.")
        chatPlayer(p, "War-contracts?")
        say(p, "Slayer work, soldier. The realm needs certain beasts killed, so I'll assign you a target and a number to put down. It keeps the frontier and the supply roads in check.")
        say(p, "Fulfil a contract and you're paid in <col=801700>War Effort</col> — the realm's coin of contribution. Combat work, gathering work, it all earns it.")
        say(p, "Spend War Effort at my reward shop yonder — better food, potions, gear. And the more you earn each day, the greater your edge in XP and drops.")
        say(p, "Run contracts back to back without slacking and your streak pays a bonus. The tougher the contract the better the pay — but the hardest are reserved for higher ranks.")
        say(p, "Enough talk. Let's find you a contract.")
    }

    private suspend fun QueueTask.assignTask(p: Player) {
        ensureIntro(p)
        // If they reached the Assignment option mid intro quest, route to the right beat.
        if (RecruitTrials.step(p) == RecruitTrials.Step.RETURN) {
            recruitFinale(p)
            return
        }
        if (RecruitTrials.step(p) == RecruitTrials.Step.MINE_BRIEF) {
            recruitMiningBrief(p)
            return
        }
        // Intro quest: a fixed, gentle first contract — 5 of the small rats around the castle. This is
        // checked BEFORE the "you already have a task" guard below, so a contract the recruit pulled
        // from the window early (e.g. during the RANK step) can never skip the scripted tutorial rats.
        if (RecruitTrials.step(p) == RecruitTrials.Step.SLAY) {
            val rat = tasks[TUTORIAL_RAT_KEY]
            if (rat != null) {
                p.attr[SLAYER_TASK_NPC_ATTR] = rat.npcName
                p.attr[SLAYER_TASK_LEFT_ATTR] = TUTORIAL_RAT_COUNT
                p.attr[SLAYER_TASK_TOTAL_ATTR] = TUTORIAL_RAT_COUNT
                say(p, "For your first contract, something simple: kill <col=801700>$TUTORIAL_RAT_COUNT ${rat.display}</col>. They scurry about just outside, around the castle. Off you go.")
                return
            }
        }
        val current = p.attr[SLAYER_TASK_NPC_ATTR]
        val left = p.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        if (current != null && left > 0) {
            val name = tasks[current]?.display ?: "your current task"
            say(p, "You still have $left $name to slay. Get going!")
            return
        }
        // No free/random combat contracts until the Recruit Trials are finished: the quest hands out
        // the scripted ones (the rats above, then the supply run). Without this a recruit could pull a
        // random task before reaching the SLAY step, and completing it would advance the quest past the
        // tutorial rats — the exact bypass reported. Nudge them back to their current objective instead.
        if (RecruitTrials.inProgress(p)) {
            say(p, "Orders first, ${p.address}. ${RecruitTrials.step(p).objective}")
            return
        }
        val eligible = tasks.values.filter { p.combatLevel >= it.minCombat }
        if (eligible.isEmpty()) {
            say(p, "I've nothing for you right now.")
            return
        }
        val task = eligible[world.random(eligible.size - 1)]
        val amount = world.random(task.amount)
        p.attr[SLAYER_TASK_NPC_ATTR] = task.npcName
        p.attr[SLAYER_TASK_LEFT_ATTR] = amount
        p.attr[SLAYER_TASK_TOTAL_ATTR] = amount
        say(p, "Your task: slay <col=801700>$amount ${task.display}</col>. Good hunting.")
    }

    private fun reportTask(p: Player) {
        val current = p.attr[SLAYER_TASK_NPC_ATTR]
        val left = p.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        val task = current?.let { tasks[it] }
        if (task == null || left <= 0) {
            p.message("You don't have a Slayer task. See Turael in Lumbridge for one.")
        } else {
            p.message("Slayer task: <col=801700>$left ${task.display}</col> remaining. (streak ${p.attr[SLAYER_STREAK_ATTR] ?: 0})")
        }
    }

    private fun onKill(player: Player?, deadId: Int) {
        if (player == null) return
        val taskKey = player.attr[SLAYER_TASK_NPC_ATTR] ?: return
        val left = player.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        if (left <= 0) return
        val task = tasks[taskKey] ?: return
        // Match by the monster's cache name, not a single id: cows, skeletons, spiders &c. each
        // spawn under several npc ids that share one name, and every one of them must count.
        val target = taskName[taskKey] ?: return
        if (!npcName(deadId).equals(target, ignoreCase = true)) return // not the assigned monster

        player.addXp(Skills.SLAYER, task.xpPerKill)
        val remaining = left - 1
        if (remaining > 0) {
            player.attr[SLAYER_TASK_LEFT_ATTR] = remaining
            return
        }
        // Contract complete: clear it, bump streak, pay streak-scaled War Effort (the one war currency).
        player.attr.remove(SLAYER_TASK_NPC_ATTR)
        player.attr[SLAYER_TASK_LEFT_ATTR] = 0
        player.attr[SLAYER_TASK_TOTAL_ATTR] = 0 // hides the client slayer dial until the next task
        val streak = (player.attr[SLAYER_STREAK_ATTR] ?: 0) + 1
        player.attr[SLAYER_STREAK_ATTR] = streak
        val reward = WAR_EFFORT_BASE + (streak / 5) * STREAK_BONUS
        player.addPoints(PointKind.WAR_EFFORT, reward)
        player.message("<col=801700>Combat contract complete!</col> +$reward War Effort (streak $streak, total ${player.points(PointKind.WAR_EFFORT)}).")
        RecruitTrials.onSlayerTaskComplete(player) // advances the intro quest's SLAY step on completion
        ContractMenu.push(player) // refresh the contract board if the window is open (hidden line otherwise)
    }

    /** Reward shop priced in War Effort (sell-only sink). Items guarded so a missing
     *  cache key is simply skipped. (item key -> War Effort cost) */
    private fun buildRewardShop() {
        val wares = listOf(
            "item.shark" to 4,
            "item.prayer_potion4" to 8,
            "item.super_attack4" to 6,
            "item.super_strength4" to 6,
            "item.super_defence4" to 6,
            "item.super_combat_potion4" to 20,
            "item.rune_scimitar" to 30,
            "item.rune_full_helm" to 25,
            "item.amulet_of_power" to 20,
        ).mapNotNull { (key, cost) -> resolveOrNull(key)?.let { it to cost } }

        createShop(rewardShop, PointsCurrency(PointKind.WAR_EFFORT), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(wares.size, 1)) {
            wares.forEachIndexed { i, (id, cost) -> items[i] = ShopItem(item = id, amount = 1000, sellPrice = cost) }
        }
    }

    private fun resolves(npcKey: String): Boolean = try { getRSCM(npcKey); true } catch (e: Exception) { false }
    private fun resolveId(npcKey: String): Int = try { getRSCM(npcKey) } catch (e: Exception) { -1 }
    private fun resolveOrNull(itemKey: String): Int? = try { getRSCM(itemKey) } catch (e: Exception) { null }

    /** The cache display name of an npc id, lower-cased & trimmed, or null if it can't be resolved. */
    private fun npcName(npcId: Int): String? =
        try { getNpc(npcId).name.lowercase().trim() } catch (e: Exception) { null }

    private companion object {
        const val MASTER_RADIUS = 10 // how close a ::con action must be to Vannaka's post
        const val WAR_EFFORT_BASE = 8 // base War Effort for a completed combat contract
        const val STREAK_BONUS = 4 // extra War Effort per 5-contract streak milestone
        const val TUTORIAL_RAT_KEY = "npc.rat_2854" // the castle rats; their slayer task is the intro contract
        const val TUTORIAL_RAT_COUNT = 5
    }
}
