package org.alter.plugins.content.skills.slayer

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.SLAYER_INTRO_DONE_ATTR
import org.alter.game.model.attr.SLAYER_STREAK_ATTR
import org.alter.game.model.attr.SLAYER_TASK_LEFT_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.options
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.points
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

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
    private val masterTile = Triple(3219, 3215, 0) // end-game cluster south of the shop hub
    private val rewardShop = "War Rewards"

    /** Tasks whose npc actually exists in the cache, keyed by RSCM npc key. */
    private val tasks: Map<String, SlayerTask> = SlayerTasks.ALL
        .filter { resolves(it.npcName) }
        .associateBy { it.npcName }

    init {
        buildRewardShop()

        spawnNpc(master, x = masterTile.first, z = masterTile.second, height = masterTile.third, walkRadius = 0, direction = Direction.EAST)
        bindTalk()

        // Track every player kill against the killer's active task (cheap: non-player
        // killers and untasked players bail immediately).
        onAnyNpcDeath { onKill(player = (npc.attr[KILLER_ATTR]?.get() as? Player), deadId = npc.id) }

        onCommand("slayer", description = "Show your Slayer task") { reportTask(player) }
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
        chatNpc(p, "After a war-contract, ${p.address}? Or here to spend your points?")
        when (options(p, "Combat contract.", "Resource contract.", "What are my contracts?", "Reward shop.", "Nothing, thanks.")) {
            1 -> assignTask(p)
            2 -> assignResource(p)
            3 -> { reportTask(p); reportResource(p) }
            4 -> p.openShop(rewardShop)
            5 -> chatPlayer(p, "Nothing, thanks.")
        }
    }

    /** Resource (gathering) contract — the supply side of Vannaka's war-contracts (master design brief §2). */
    private suspend fun QueueTask.assignResource(p: Player) {
        val existing = ResourceContracts.current(p)
        if (existing != null) {
            chatNpc(p, "You've still <col=801700>${existing.second} ${existing.first.display}</col> to gather. Bring it in.")
            return
        }
        val assigned = ResourceContracts.assign(p)
        if (assigned == null) {
            chatNpc(p, "Nothing I can set you to right now — raise a gathering skill or two and come back.")
            return
        }
        val (task, amount) = assigned
        chatNpc(p, "Resource contract: gather <col=801700>$amount ${task.display}</col> — that's ${task.skill} work. You keep what you gather; I just need it done.")
        chatNpc(p, "Coin and War Effort when it's complete. Off you go.")
    }

    private fun reportResource(p: Player) {
        val cur = ResourceContracts.current(p)
        if (cur == null) p.message("You have no resource contract. Ask Vannaka for one.")
        else p.message("Resource contract: <col=801700>${cur.second} ${cur.first.display}</col> left (${cur.first.skill}).")
    }

    /** Intro-quest finale: the recruit returns to Vannaka after their trials for the reward (sent to
     *  the bank) and their first real, post-tutorial assignment. */
    private suspend fun QueueTask.recruitFinale(p: Player) {
        chatNpc(p, "Back already — and the contract done. You've passed every trial the realm set you, recruit.")
        chatNpc(p, "Here's your reward. I've sent it to your bank, so your pack stays clear for the fights ahead.")
        RecruitTrials.grantFinalReward(p) // steel finish + supplies to bank + completes the trials (War Effort)
        chatNpc(p, "From here, war-contracts are a steady trade. Come to me any time and pick your work: a <col=801700>combat contract</col> or a <col=801700>resource contract</col>.")
        chatNpc(p, "Combat sharpens your blade; resource contracts will walk you through every gathering skill the realm needs — mining, woodcutting, fishing and more. Either way, you're paid.")
        chatNpc(p, "Clear a contract, come back for your pay and the next. Here's your first proper one.")
        assignTask(p) // step is DONE now → a normal random contract, not the tutorial rats
        // Kick straight into the War-Prep chain: Quest 1 is Magic.
        warPrepMagicIntro(p)
    }

    /** War-Prep Quest 1 (Magic) intro — Vannaka pivots from the trials into preparing the recruit for
     *  raids, starting with Prayer/Protect-from-Magic. [WarPrepChain.begin] gifts the dragon bones and
     *  sets the church-altar objective. */
    private suspend fun QueueTask.warPrepMagicIntro(p: Player) {
        chatNpc(p, "One last thing, ${p.address}. Contracts keep you sharp, but a raider needs more than a blade — you'll need <col=801700>magic</col>, and the nerve to stand in front of it.")
        chatNpc(p, "The front's mages will melt a man who can't pray. So first, train your <col=801700>Prayer to ${WarPrepChain.PRAYER_TARGET}</col> — that's when you can call on <col=801700>Protect from Magic</col>. Here, take these.")
        WarPrepChain.begin(p) // gifts the dragon bones + sets the PRAYER objective/arrow
        chatNpc(p, "Dragon bones — <col=801700>offer</col> them on the <col=801700>church altar</col> in Lumbridge, far faster than burying. Follow the marker, and come back to me once Protect from Magic is yours.")
    }

    /** PRAYER step nudge: keep them at the altar; top up bones if they ran dry (bounded — see
     *  [WarPrepChain.topUpBones]), and past the cap drill the remaining Prayer into them directly. */
    private suspend fun QueueTask.warPrepPrayerNudge(p: Player) {
        when (WarPrepChain.topUpBones(p)) {
            WarPrepChain.TopUp.BONES ->
                chatNpc(p, "Out of bones already? Here's more — and mind them this time, they don't grow back. Offer them at the <col=801700>church altar</col> until your Prayer reaches <col=801700>${WarPrepChain.PRAYER_TARGET}</col>.")
            WarPrepChain.TopUp.DRILLED -> {
                chatNpc(p, "Lost ANOTHER stack? I'm not made of dragon bones, recruit. Kneel — we'll do this the army way.")
                chatNpc(p, "Vannaka drills the litany into you until your knees ache. Your <col=801700>Prayer</col> rises to <col=801700>${WarPrepChain.PRAYER_TARGET}</col>.")
            }
            WarPrepChain.TopUp.NOT_NEEDED ->
                chatNpc(p, "Train your Prayer to <col=801700>${WarPrepChain.PRAYER_TARGET}</col> at the church altar — offer those dragon bones on it. Then you can pray Protect from Magic, and you'll need it in the tower.")
        }
    }

    /** GEAR step: Prayer's ready — arm the recruit for the Wizard Tower and send them to the
     *  Void Knight who runs the assaults. */
    private suspend fun QueueTask.warPrepArm(p: Player) {
        chatNpc(p, "Prayer trained and Protect from Magic ready — good. You'll not walk into a tower of mages unarmed, though.")
        WarPrepChain.armForTower(p) // staff + robes + runes + noted prayer potions
        chatNpc(p, "Take this staff, these robes, and a proper stock of runes — <col=801700>air, water, earth and fire</col> by the hundreds, plus the combat runes. And a crate of <col=801700>prayer potions</col>, noted — sip them and <col=801700>Protect from Magic</col> never drops.")
        chatNpc(p, "The <col=801700>Wizard Tower</col> stands south-west, across the river. A <col=801700>Void Knight</col> holds the bridge to it — speak to him and he'll send you in. Fight up floor by floor and take the <col=801700>grimoire</col> from the Archmage at the top.")
        chatNpc(p, "Follow the marker to the Void Knight — and come back to me once the grimoire's power is yours.")
        WarPrepChain.onArmedForTower(p) // advance to TOWER
    }

    /** TOWER step nudge. */
    private suspend fun QueueTask.warPrepTowerNudge(p: Player) {
        chatNpc(p, "The grimoire won't fetch itself, ${p.address}. Follow the marker to the <col=801700>Void Knight</col> at the Wizard Tower bridge — he'll send you in. Clear it floor by floor and take it from the Archmage.")
    }

    /** RETURN step: back from the tower with the grimoire — Vannaka's debrief closes the quest. */
    private suspend fun QueueTask.warPrepDebrief(p: Player) {
        chatNpc(p, "Back — and I can smell the scorched robes from here. The grimoire's power is yours, ${p.address}: every spellbook the realm knows, at your call.")
        chatNpc(p, "Keep the tower in mind, too. The Void Knight will send you back in whenever you like, and those mages bleed <col=801700>runes</col> — there's no finer place to farm them.")
        chatNpc(p, "That's Magic dealt with. You've stood in front of spellfire and walked out — the war's <col=801700>raids</col> are opening to you.")
        WarPrepChain.onReportedToVannaka(p) // RETURN → DONE
    }

    /** Intro-quest: the recruit reports back to Vannaka after the rats — Vannaka rewards the combat
     *  contract (a steel platebody) and hands out the supply (mining) contract. */
    private suspend fun QueueTask.recruitMiningBrief(p: Player) {
        chatNpc(p, "Rats cleared — good work. Now hear this: my contracts aren't all blood and steel.")
        chatPlayer(p, "What else is there?")
        chatNpc(p, "<col=801700>Resource contracts.</col> The war runs on raw materials as much as it does on soldiers, so I hand out gathering work too — ore, logs, fish, whatever the realm's short on. Same pay: coin and War Effort.")
        chatPlayer(p, "What does the realm need ore for?")
        chatNpc(p, "The <col=801700>war</col>, soldier. Supplies arm the campaigns — and when we march on a city like Varrock, everyone who fed the war takes a cut of everything we drag out of it. Their rune, their riches, split among those who supplied and fought.")
        chatNpc(p, "That's how a soldier gets rich here. You're not ready to march yet... but you will be. For now — feed the war and earn your place.")
        chatNpc(p, "Here's your first resource contract: get to <col=801700>The Mire</col> — our skilling grounds south-east of the castle. Mine copper and tin, smelt a <col=801700>bronze bar</col> at the furnace, then hammer it into a <col=801700>bronze dagger</col> at the anvil.")
        chatNpc(p, "Hand that finished dagger to the <col=801700>Quartermaster</col> there — the Supply Officer. That's how supplies really reach the front: gathered, worked, then turned in. Then report back to me. Follow the marker.")
        RecruitTrials.onMiningAssigned(p) // steel platebody + tools reward, advance to the mining step
    }

    /** First-meeting introduction: who Vannaka is and how war-contracts work. Runs once. */
    private suspend fun QueueTask.ensureIntro(p: Player) {
        if (p.attr[SLAYER_INTRO_DONE_ATTR] == true) return
        p.attr[SLAYER_INTRO_DONE_ATTR] = true
        chatNpc(p, "So you're the recruit the Sergeant sent down. I'm Vannaka — I hand out the war-contracts.")
        chatPlayer(p, "War-contracts?")
        chatNpc(p, "Slayer work, soldier. The realm needs certain beasts killed, so I'll assign you a target and a number to put down. It keeps the frontier and the supply roads in check.")
        chatNpc(p, "Fulfil a contract and you're paid in <col=801700>War Effort</col> — the realm's coin of contribution. Combat work, gathering work, it all earns it.")
        chatNpc(p, "Spend War Effort at my reward shop yonder — better food, potions, gear. And the more you earn each day, the greater your edge in XP and drops.")
        chatNpc(p, "Run contracts back to back without slacking and your streak pays a bonus. The tougher the contract the better the pay — but the hardest are reserved for higher ranks.")
        chatNpc(p, "Enough talk. Let's find you a contract.")
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
        val current = p.attr[SLAYER_TASK_NPC_ATTR]
        val left = p.attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        if (current != null && left > 0) {
            val name = tasks[current]?.display ?: "your current task"
            chatNpc(p, "You still have $left $name to slay. Get going!")
            return
        }
        // Intro quest: a fixed, gentle first contract — 5 of the small rats around the castle.
        if (RecruitTrials.step(p) == RecruitTrials.Step.SLAY) {
            val rat = tasks[TUTORIAL_RAT_KEY]
            if (rat != null) {
                p.attr[SLAYER_TASK_NPC_ATTR] = rat.npcName
                p.attr[SLAYER_TASK_LEFT_ATTR] = TUTORIAL_RAT_COUNT
                chatNpc(p, "For your first contract, something simple: kill <col=801700>$TUTORIAL_RAT_COUNT ${rat.display}</col>. They scurry about just outside, around the castle. Off you go.")
                return
            }
        }
        val eligible = tasks.values.filter { p.combatLevel >= it.minCombat }
        if (eligible.isEmpty()) {
            chatNpc(p, "I've nothing for you right now.")
            return
        }
        val task = eligible[world.random(eligible.size - 1)]
        val amount = world.random(task.amount)
        p.attr[SLAYER_TASK_NPC_ATTR] = task.npcName
        p.attr[SLAYER_TASK_LEFT_ATTR] = amount
        chatNpc(p, "Your task: slay <col=801700>$amount ${task.display}</col>. Good hunting.")
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
        if (resolveId(taskKey) != deadId) return // not the assigned monster

        player.addXp(Skills.SLAYER, task.xpPerKill)
        val remaining = left - 1
        if (remaining > 0) {
            player.attr[SLAYER_TASK_LEFT_ATTR] = remaining
            return
        }
        // Contract complete: clear it, bump streak, pay streak-scaled War Effort (the one war currency).
        player.attr.remove(SLAYER_TASK_NPC_ATTR)
        player.attr[SLAYER_TASK_LEFT_ATTR] = 0
        val streak = (player.attr[SLAYER_STREAK_ATTR] ?: 0) + 1
        player.attr[SLAYER_STREAK_ATTR] = streak
        val reward = WAR_EFFORT_BASE + (streak / 5) * STREAK_BONUS
        player.addPoints(PointKind.WAR_EFFORT, reward)
        player.message("<col=801700>Combat contract complete!</col> +$reward War Effort (streak $streak, total ${player.points(PointKind.WAR_EFFORT)}).")
        RecruitTrials.onSlayerTaskComplete(player) // advances the intro quest's SLAY step on completion
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

    private companion object {
        const val WAR_EFFORT_BASE = 8 // base War Effort for a completed combat contract
        const val STREAK_BONUS = 4 // extra War Effort per 5-contract streak milestone
        const val TUTORIAL_RAT_KEY = "npc.rat_2854" // the castle rats; their slayer task is the intro contract
        const val TUTORIAL_RAT_COUNT = 5
    }
}
