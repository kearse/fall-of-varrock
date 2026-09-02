package org.alter.plugins.content.war.recruit

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.NEW_ACCOUNT_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.knights.CampClearance
import org.alter.plugins.content.bots.knights.RogueKnightLadder
import org.alter.plugins.content.mechanics.onboarding.FirstLoginFlow
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.bindTalk
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.roguehunt.RogueHunt
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Recruit Trials** wiring (master design brief §1) — the First 10 Minutes onboarding.
 *
 * Spawns the Recruiting Sergeant by the Lumbridge gate, frames the war on a new account's
 * first login, and drives the four-pillar chain in [RecruitTrials]:
 *  - FIGHT  — counted on the additive `onAnyNpcDeath` list (never clashes with the keyed
 *             goblin-death hook elsewhere).
 *  - RANK   — advanced by a one-line notify in `DukeHoracioPlugin`.
 *  - SLAY   — polled from the player's active Slayer task by [RecruitTrials.TRIAL_TIMER].
 *  - SUPPLY — polled from a supply-skill xp gain by the same timer.
 *
 * Completing the chain grants the recruit's first War Effort, so they leave onboarding
 * already on the feudal ladder.
 */
class RecruitTrialsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val sergeant = "npc.sergeant_damien"
    private val sergeantTile = Triple(3217, 3220, 0) // just inside the Lumbridge gate, near the frontier road
    private val sergeantId = runCatching { getRSCM(sergeant) }.getOrDefault(-1)

    /** Goblin ids that count for the FIGHT trial. The frontier front line is `goblin_2245`
     *  (CityFrontiers level 1); plain `goblin` is included too. A cache-name fallback in
     *  [isGoblin] catches any other goblin variant regardless of id. */
    private val goblinIds = listOf("npc.goblin", "npc.goblin_2245")
        .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    init {
        spawnNpc(sergeant, x = sergeantTile.first, z = sergeantTile.second, height = sergeantTile.third, walkRadius = 0, direction = Direction.EAST)
        bindSergeant()
        spawnTutorialGoblins()

        // Let FirstLoginFlow run the Sergeant's welcome once onboarding (video → character style)
        // finishes — the dialogue is private/suspend/QueueTask-scoped, so we expose it as a callback.
        RecruitTrials.greet = { p -> p.queue { sergeantDialog(p) } }

        onLogin {
            // Brand-new account: start the chain. The Sergeant's welcome is deferred to the END of
            // the first-login flow (FirstLoginFlow calls RecruitTrials.greet after the player confirms
            // their character), so it never runs behind the intro video or before customization.
            // If the player is NOT onboarding (older account predating the flow, still NEW_ACCOUNT)
            // greet immediately — the session-only flag keeps it to once.
            if (player.attr[NEW_ACCOUNT_ATTR] == true) {
                RecruitTrials.begin(player)
                if (!FirstLoginFlow.isOnboarding(player)) {
                    player.queue { sergeantDialog(player) }
                }
            }
            // Point the guidance arrow at the current objective and re-arm the SLAY/SUPPLY poll if needed.
            RecruitTrials.resumeOnLogin(player)
        }

        // FIGHT: additive death hook. Cheap — bails immediately for non-goblin kills or recruits
        // who aren't on the FIGHT step.
        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (RecruitTrials.step(killer) == RecruitTrials.Step.FIGHT && isGoblin(npc.id)) {
                RecruitTrials.onGoblinKill(killer)
            }
        }

        // SLAY/SUPPLY state poll.
        onTimer(RecruitTrials.TRIAL_TIMER) { RecruitTrials.pollTick(player) }

        onCommand("trials", description = "Show your Recruit Trials objective") { reportTrials(player) }
    }

    private fun isGoblin(deadId: Int): Boolean {
        if (deadId in goblinIds) return true
        return runCatching { getNpc(deadId).name.contains("goblin", ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * Always-on tutorial goblin pack at the FIGHT objective (the back woods, ~3193,3221 — matches
     * [RecruitTrials.FIGHT_TILE], where the marker points). The goblins there normally come from the
     * presence-gated CityFrontier ring, which despawns when nobody is around — so a SOLO new player's
     * very first objective could dead-end with nothing to kill. This small respawning pack is
     * decoupled from the war system and always present, so "kill $GOBLIN_GOAL goblins" is never
     * un-completable. Plainly spawned (passive — a safe first fight); they count via [isGoblin].
     */
    private fun spawnTutorialGoblins() {
        val key = listOf("npc.goblin", "npc.goblin_2245").firstOrNull { runCatching { getRSCM(it) }.isSuccess } ?: run {
            logger.warn { "RecruitTrials: no goblin npc resolved; tutorial FIGHT pack not spawned." }
            return
        }
        val cx = 3193; val cz = 3221 // matches RecruitTrials.FIGHT_TILE (the FIGHT marker target)
        val offsets = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -2 to 1, 2 to -1)
        offsets.forEach { (dx, dz) ->
            spawnNpc(key, x = cx + dx, z = cz + dz, height = 0, walkRadius = 1, direction = Direction.SOUTH)
        }
        logger.info { "RecruitTrials: spawned ${offsets.size} tutorial goblins ('$key') at the FIGHT objective ($cx,$cz)." }
    }

    private fun reportTrials(p: Player) {
        val step = RecruitTrials.step(p)
        if (step == RecruitTrials.Step.DONE) {
            p.message("<col=801700>Recruit Trials:</col> complete. You are a citizen-soldier of Lumbridge.")
            return
        }
        p.message("<col=801700>Recruit Trials — current objective:</col> ${step.objective}")
        if (step == RecruitTrials.Step.FIGHT) {
            val kills = p.attr[org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR] ?: 0
            p.message("Goblins killed: <col=801700>$kills/${RecruitTrials.GOBLIN_GOAL}</col>.")
        }
    }

    // --- Sergeant dialogue --------------------------------------------------------------

    /**
     * The Sergeant's Talk-to is routed through the quest framework's [NpcTalk]: this plugin owns
     * his everyday dialogue at the default priority; quests (The Rogue Problem today — see
     * `RogueProblemPlugin`; Block-2 quests tomorrow) register higher-priority branches without
     * editing this file. [bindTalk] is the defensive binder (cache verb pre-checked, never a
     * construction-time throw).
     */
    private fun bindSergeant() {
        if (!bindTalk(sergeant)) {
            logger.warn { "Recruiting Sergeant '$sergeant' could not be bound; trials cannot be started by talking." }
            return
        }
        NpcTalk.register(sergeant, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> sergeantDialog(p) } }
    }

    private suspend fun QueueTask.sergeantDialog(p: Player) {
        val s = sergeantId
        when (RecruitTrials.step(p)) {
            RecruitTrials.Step.TALK -> {
                chatNpc(p, "At ease, recruit. Lumbridge is at war, and we need every able body. I'll make a soldier of you yet.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "Goblins have slipped through our back defences into the woods behind the city. With the army at the front, you're the LAST line of defence between them and our people.", npc = s, title = "Recruiting Sergeant")
                chatPlayer(p, "What do you need me to do?")
                chatNpc(p, "Get into the back woods and kill ${RecruitTrials.GOBLIN_GOAL} of them — follow the marker. Hold that line and the city stands. Then report straight back to me.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "You've got the look of a soldier now — you're on the muster roll. Get to the front, recruit.", npc = s, title = "Recruiting Sergeant")
                // Character customization already happened before this dialogue (the first-login flow
                // opens the Character Style window when the intro video ends, then hands control here),
                // so the Sergeant just musters the recruit straight into the Trials (advances to FIGHT).
                RecruitTrials.advanceTo(p, RecruitTrials.Step.FIGHT)
            }
            RecruitTrials.Step.FIGHT -> {
                val kills = p.attr[org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR] ?: 0
                chatNpc(p, "The back woods are that way, soldier — follow the marker. Kill ${RecruitTrials.GOBLIN_GOAL} goblins; you're at $kills. The city's counting on you.", npc = s, title = "Recruiting Sergeant")
            }
            RecruitTrials.Step.REPORT -> {
                chatNpc(p, "You held them! The back woods are clear and the city's safe — for now. You're a credit to the realm, soldier.", npc = s, title = "Recruiting Sergeant")
                chatPlayer(p, "What now, sergeant?")
                chatNpc(p, "You've earned a soldier's pay. Take it to Duke Horacio in the market and buy your first rank — stop being a peasant.", npc = s, title = "Recruiting Sergeant")
                RecruitTrials.grantReportReward(p)
            }
            RecruitTrials.Step.RANK -> chatNpc(p, "Take that coin to Duke Horacio in the market — he stands by the Slayer Master. Buy your first rank from him.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.SLAY -> chatNpc(p, "A ranked soldier takes war-contracts. Follow the marker to Vannaka and take one.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.MINE_BRIEF -> chatNpc(p, "Rats down? Report back to Vannaka — he'll have a supply contract for you next.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.SUPPLY -> chatNpc(p, "An army marches on its supplies. Vannaka's set you to work in The Mire, our skilling grounds — follow the marker and mine some copper and tin to start.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.SMELT -> chatNpc(p, "Got your ore? Smelt it into a bronze bar at the furnace in The Mire — follow the marker.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.SMITH -> chatNpc(p, "A bar's no use to the front on its own. Hammer it into a bronze dagger at the anvil — follow the marker.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.DELIVER -> chatNpc(p, "Now take that dagger to the Quartermaster in The Mire and hand it in for the war — follow the marker.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.RETURN -> chatNpc(p, "Supplies handed in? Report back to Vannaka — he'll square you up.", npc = s, title = "Recruiting Sergeant")
            RecruitTrials.Step.DONE -> {
                // The Rogue Problem's beats (the optional assignment's offer, brief, hunt, knight,
                // report and ladder talk) live in RogueProblemPlugin as a quest-priority NpcTalk
                // branch — it claims the conversation while that quest is offerable or live, so
                // this default branch only ever sees a Sergeant with no quest to run.

                // The Rogue Knight ladder outlives the quest — the Sergeant stays its quartermaster.
                if (RogueKnightLadder.unlocked(p)) {
                    when (options(p, "My Rogue Knight hunt", "Just reporting in", title = "Recruiting Sergeant")) {
                        1 -> {
                            val target = RogueKnightLadder.activeDef(p)
                            if (target == null) {
                                chatNpc(p, "You've cleared the whole ladder, ${p.address} — all ${org.alter.plugins.content.bots.knights.RogueKnights.LADDER.size} of them. The realm's deadliest blade. Any of them can be hunted again for their gear: <col=ffae00>::knights</col>.", npc = s, title = "Recruiting Sergeant")
                            } else {
                                val farming = target.rank < RogueKnightLadder.rank(p)
                                if (farming) {
                                    chatNpc(p, "You're back on <col=801700>${target.name}</col> for the spoils — good hunting. ${RogueKnightLadder.statusLine(p)} (<col=ffae00>::huntnext</col> returns you to the ladder.)", npc = s, title = "Recruiting Sergeant")
                                } else {
                                    chatNpc(p, "Your mark: ${target.briefLine}", npc = s, title = "Recruiting Sergeant")
                                    chatNpc(p, "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions} The marker leads; <col=ffae00>::knights</col> lists the whole ladder, and any beaten knight can be farmed again.", npc = s, title = "Recruiting Sergeant")
                                    if (!CampClearance.cleared(p, target.camp)) {
                                        chatNpc(p, "The camp guards its own: ${CampClearance.statusLine(p, target.camp)}", npc = s, title = "Recruiting Sergeant")
                                    }
                                }
                            }
                            return
                        }
                        else -> {} // fall through to the milestone/idle chatter
                    }
                }
                // Rogue-hunting bounties (story-and-grind-design §4): the Sergeant is the milestone
                // paymaster, so every bounty moment routes the hunter back to him.
                val bounties = RogueHunt.payout(p)
                if (bounties.isNotEmpty()) {
                    chatNpc(p, "Word travels, ${p.address} — ${RogueHunt.kills(p)} cutthroats of the fallen cities put down by your hand. The realm pays its hunters. Here's your bounty.", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "Keep at it. ${RogueHunt.statusLine(p)}", npc = s, title = "Recruiting Sergeant")
                    return
                }
                chatNpc(p, "At ease, ${p.address}. You've fought, ranked, slain and supplied — a true citizen-soldier of Lumbridge. Make us proud.", npc = s, title = "Recruiting Sergeant")
                // UX: the teleport portal was undiscoverable — nothing in the game ever mentioned it.
                chatNpc(p, "One more thing every soldier should know: the <col=801700>glowing portal over the courtyard fountain</col> carries you to every front, skilling ground and arena the realm holds. Use it.", npc = s, title = "Recruiting Sergeant")
                if (RogueHunt.kills(p) == 0) {
                    chatNpc(p, "If you're hunting work: the rogue family crawls over the road camps west of Lumbridge and the ruins of <col=801700>Fallen Varrock</col> alike. The realm pays a bounty at every milestone of cutthroats you put down — report your tally to me. <col=ffae00>::rogues</col> tracks it.", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "Fair warning: Varrock's streets are the wilderness — the road camps are safe. Take nothing into the ruins you can't afford to lose; the tally, at least, is yours forever.", npc = s, title = "Recruiting Sergeant")
                } else {
                    chatNpc(p, RogueHunt.statusLine(p), npc = s, title = "Recruiting Sergeant")
                }
                chatPlayer(p, "I won't let the realm down, sergeant.")
            }
        }
    }
}
