package org.alter.plugins.content.war.recruit

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
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
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Last Free City** wiring (Main Story Quest 1 — `docs/quests/the-last-free-city.md`), on the
 * Recruit Trials chain in [RecruitTrials].
 *
 * Spawns Sergeant Damien by the Lumbridge gate, sounds the alarm on a new account's first login
 * (the east goblin camp is being probed), hands out the muster kit, and drives the chain:
 *  - FIGHT  — counted on the additive `onAnyNpcDeath` list. Every recruit who drew blood on the
 *             goblin is credited (not just the top-damage killer), so fighting BESIDE the Knights
 *             of Lumbridge can never strand a new player on their first objective.
 *  - RANK   — advanced by a one-line notify in `DukeHoracioPlugin`.
 *  - SLAY   — Vannaka's scripted goblin cleanup contract (`SlayerPlugin`).
 *  - SUPPLY — the Mire loop, polled from the pack by [RecruitTrials.TRIAL_TIMER].
 *  - DEBRIEF — Damien's finale: Varrock fell, the war lies north.
 *
 * Completing the chain grants the recruit's first War Effort, so they leave onboarding already on
 * the feudal ladder.
 */
class RecruitTrialsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val sergeant = "npc.sergeant_damien"
    private val sergeantTile = Triple(3217, 3220, 0) // just inside the Lumbridge gate, near the frontier road
    private val sergeantId = runCatching { getRSCM(sergeant) }.getOrDefault(-1)

    /** Goblin ids that count for the FIGHT step: the tutorial pack ([RecruitTrials.TUTORIAL_GOBLIN_NPC]),
     *  the plain `goblin`, and `goblin_2245` (kept for any straggler of the retired Lumbridge frontier
     *  goblin line). A cache-name fallback in [isGoblin] catches any other goblin variant regardless of id. */
    private val goblinIds = listOf(RecruitTrials.TUTORIAL_GOBLIN_NPC, "npc.goblin", "npc.goblin_2245")
        .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    init {
        spawnNpc(sergeant, x = sergeantTile.first, z = sergeantTile.second, height = sergeantTile.third, walkRadius = 0, direction = Direction.EAST)
        bindSergeant()
        spawnTutorialGoblins()

        // Let FirstLoginFlow run the Sergeant's alarm once onboarding (video → character style)
        // finishes — the dialogue is private/suspend/QueueTask-scoped, so we expose it as a callback.
        RecruitTrials.greet = { p -> p.queue { sergeantDialog(p) } }

        onLogin {
            // Brand-new account: start the chain. The Sergeant's alarm is deferred to the END of
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

        // FIGHT: additive death hook. Cheap — bails immediately for non-goblin kills. Credits every
        // recruit on the FIGHT step who damaged the goblin: at the east camp the Knights of
        // Lumbridge (and other players) routinely out-damage a fresh account, and kill credit
        // (KILLER_ATTR) goes to the top damage dealer — "help the knights put them down" must count
        // the help, or the opening objective stalls for exactly the player it exists for.
        onAnyNpcDeath {
            if (!isGoblin(npc.id)) return@onAnyNpcDeath
            npc.damageMap.playerDamage().keys.forEach { p ->
                if (p.isOnline && RecruitTrials.step(p) == RecruitTrials.Step.FIGHT) {
                    RecruitTrials.onGoblinKill(p)
                }
            }
        }

        // SLAY/SUPPLY state poll.
        onTimer(RecruitTrials.TRIAL_TIMER) { RecruitTrials.pollTick(player) }

        onCommand("trials", description = "Show your objective in The Last Free City") { reportTrials(player) }
    }

    private fun isGoblin(deadId: Int): Boolean {
        if (deadId in goblinIds) return true
        return runCatching { getNpc(deadId).name.contains("goblin", ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * Always-on tutorial goblin pack at the FIGHT objective — the east Lumbridge goblin camp
     * ([RecruitTrials.CAMP_CENTRE]), where the Knights of Lumbridge (`GoblinCampPlugin`) hold the
     * line against the ambient camp goblins. Those ambient goblins are presence-gated (they despawn
     * when nobody is around), so a SOLO new player's very first objective could dead-end with
     * nothing to kill. This small respawning pack is decoupled from the presence gate and always
     * present, spread around the camp's outer ring ([RecruitTrials.TUTORIAL_GOBLIN_TILES]) so it
     * mixes with the garrison without swarming a fresh account. The knights leave this pack alone
     * ([RecruitTrials.isTutorialGoblin]) so there is always something for the recruits to fight.
     *
     * The pack is the camp's normal level-2 goblin ([RecruitTrials.TUTORIAL_GOBLIN_NPC]) — NOT the
     * plain level-5 `npc.goblin`, whose aggressive camp def stacked the whole pack on a new account.
     */
    private fun spawnTutorialGoblins() {
        val key = RecruitTrials.TUTORIAL_GOBLIN_NPC
        if (runCatching { getRSCM(key) }.isFailure) {
            logger.warn { "The Last Free City: '$key' did not resolve; tutorial FIGHT pack not spawned." }
            return
        }
        RecruitTrials.TUTORIAL_GOBLIN_TILES.forEach { tile ->
            spawnNpc(key, tile, walkRadius = 2, direction = Direction.WEST)
        }
        logger.info { "The Last Free City: spawned ${RecruitTrials.TUTORIAL_GOBLIN_TILES.size} tutorial goblins ('$key') around the east camp (${RecruitTrials.CAMP_CENTRE.x},${RecruitTrials.CAMP_CENTRE.z})." }
    }

    private fun reportTrials(p: Player) {
        val step = RecruitTrials.step(p)
        if (step == RecruitTrials.Step.DONE) {
            p.message("<col=801700>${RecruitTrials.QUEST_NAME}:</col> complete. ${step.objective}")
            return
        }
        p.message("<col=801700>${RecruitTrials.QUEST_NAME} — current objective:</col> ${step.objective}")
        if (step == RecruitTrials.Step.FIGHT) {
            val kills = p.attr[org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR] ?: 0
            p.message("Goblins defeated: <col=801700>$kills/${RecruitTrials.GOBLIN_GOAL}</col>.")
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
            logger.warn { "Sergeant Damien '$sergeant' could not be bound; The Last Free City cannot be started by talking." }
            return
        }
        NpcTalk.register(sergeant, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> sergeantDialog(p) } }
    }

    private suspend fun QueueTask.damien(p: Player, text: String) =
        chatNpc(p, text, npc = sergeantId, title = "Sergeant Damien")

    private suspend fun QueueTask.sergeantDialog(p: Player) {
        when (RecruitTrials.step(p)) {
            RecruitTrials.Step.TALK -> alarm(p)
            RecruitTrials.Step.FIGHT -> {
                val kills = p.attr[org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR] ?: 0
                damien(p, "The camp's east of here, across the river — follow the marker. Help the knights put down ${RecruitTrials.GOBLIN_GOAL} goblins; you're at $kills. Move!")
            }
            RecruitTrials.Step.REPORT -> report(p)
            RecruitTrials.Step.RANK -> damien(p, "Duke Horacio's in the market, by the Slayer Master. You stood for Lumbridge today — he'll recognise it. Take him your coin and claim your rank.")
            RecruitTrials.Step.SLAY -> damien(p, "Vannaka signs the war-contracts — follow the marker. Some of the goblins scattered when the knights broke them; he'll have you hunt them down before they regroup.")
            RecruitTrials.Step.MINE_BRIEF -> damien(p, "Stragglers dealt with? Report back to Vannaka — the army's stores need refilling next.")
            RecruitTrials.Step.SUPPLY -> damien(p, "Every battle empties the stores. Vannaka's set you to The Mire, our skilling grounds south-east of the castle — follow the marker and mine some copper and tin to start.")
            RecruitTrials.Step.SMELT -> damien(p, "Got your ore? Smelt it into a bronze bar at the furnace in The Mire — follow the marker.")
            RecruitTrials.Step.SMITH -> damien(p, "A bar's no use to the front on its own. Hammer it into a bronze dagger at the anvil — follow the marker.")
            RecruitTrials.Step.DELIVER -> damien(p, "Now take that dagger to the Quartermaster in The Mire and hand it in for the war — follow the marker.")
            RecruitTrials.Step.RETURN -> damien(p, "Supplies handed in? Report back to Vannaka — he'll square you up. Then come and find me.")
            RecruitTrials.Step.DEBRIEF -> debrief(p)
            RecruitTrials.Step.DONE -> idle(p)
        }
    }

    /** TALK: the alarm. Damien musters the recruit straight into the east-camp fight (advances to FIGHT). */
    private suspend fun QueueTask.alarm(p: Player) {
        damien(p, "YOU! Over here!")
        damien(p, "The eastern post is being overrun. Goblins pushed through near the old camp. Our knights are holding them, but they need every pair of hands we've got.")
        chatPlayer(p, "I just got here!")
        damien(p, "Then you picked a bad day.")
        // Handout + step advance back-to-back, with NO suspending line between them: every chat line
        // is a point where the player can close the dialogue, and a kit given before the advance
        // would be re-claimable by talking again. Character customization already happened (the
        // first-login flow opens the Character Style window when the intro ends, then hands control
        // here), so the Sergeant musters the recruit straight into the fight.
        RecruitTrials.grantMusterKit(p)
        RecruitTrials.advanceTo(p, RecruitTrials.Step.FIGHT)
        damien(p, "Here. You'll need these.")
        damien(p, "Head east, across the river. You'll see the camp. Five goblins have pushed into the position — help the knights put them down.")
        chatPlayer(p, "You want me to fight them?")
        damien(p, "I want you to decide.")
        damien(p, "You can stay here and hope somebody else keeps Lumbridge standing... or you can stand with us.")
    }

    /** REPORT: it was a probe. The lineage breadcrumb, the muster roll, the Sergeant's pay. */
    private suspend fun QueueTask.report(p: Player) {
        damien(p, "You're alive.")
        damien(p, "Better than that. You held.")
        chatPlayer(p, "Was that the attack?")
        damien(p, "No. That was a probe.")
        damien(p, "They pushed fighters against the eastern post to see how quickly we'd respond. How many guards we'd move. Where the weak points were.")
        chatPlayer(p, "So they're coming back?")
        damien(p, "They always come back.")
        damien(p, "...You know, for a second out there you reminded me of someone.")
        chatPlayer(p, "Who?")
        damien(p, "Doesn't matter. We've got work to do.")
        damien(p, "Standing your ground once doesn't make you a soldier. But it earns you the chance to become one.")
        damien(p, "The army lost weapons and supplies today. If you want to keep helping, I'm putting you on the muster roll.")
        chatPlayer(p, "What do I need to do?")
        damien(p, "First, take your pay.")
        RecruitTrials.grantReportReward(p) // coin + bronze kit; REPORT → RANK (before the last line — mutate, then narrate)
        damien(p, "Then go and see Duke Horacio in the market. You stood for Lumbridge today. He'll recognise the service.")
    }

    /** DEBRIEF: the finale — Varrock fell twelve years ago; the war lies north. Completes the quest. */
    private suspend fun QueueTask.debrief(p: Player) {
        damien(p, "Look at you. This morning you were a Peasant.")
        damien(p, "Then the horns sounded.")
        damien(p, "You fought when you could've run. You hunted down what got through. You replaced what the army lost.")
        chatPlayer(p, "Is Lumbridge safe now?")
        damien(p, "No.")
        damien(p, "But it's still ours. Varrock couldn't say the same.")
        chatPlayer(p, "What happened there?")
        damien(p, "Twelve years ago, Varrock fell.")
        damien(p, "What remains of Misthalin has been fighting ever since to make sure the same thing doesn't happen here.")
        damien(p, "Today's attack wasn't meant to take Lumbridge. They were testing us. Someone wanted to know how quickly we'd bleed.")
        chatPlayer(p, "Then maybe we shouldn't wait for the next attack.")
        RecruitTrials.onDebriefed(p) // DEBRIEF → DONE: the quest completes here (mutate, then narrate)
        damien(p, "Maybe you're learning.")
        damien(p, "General Zo musters the columns that march north against the enemy — you'll find him in the castle courtyard. When you hear the call for the next March... answer it.")
        damien(p, "Until the horns sound again, Vannaka has drills for you. The front's mages will melt a soldier who can't pray — go and see him.")
    }

    /** DONE: the everyday Sergeant — Rogue Knight ladder quartermaster, bounty paymaster, signposts. */
    private suspend fun QueueTask.idle(p: Player) {
        // The Rogue Problem's beats (the optional assignment's offer, brief, hunt, knight,
        // report and ladder talk) live in RogueProblemPlugin as a quest-priority NpcTalk
        // branch — it claims the conversation while that quest is offerable or live, so
        // this default branch only ever sees a Sergeant with no quest to run.

        // The Rogue Knight ladder outlives the quest — the Sergeant stays its quartermaster.
        if (RogueKnightLadder.unlocked(p)) {
            when (options(p, "My Rogue Knight hunt", "Just reporting in", title = "Sergeant Damien")) {
                1 -> {
                    val target = RogueKnightLadder.activeDef(p)
                    if (target == null) {
                        damien(p, "You've cleared the whole ladder, ${p.address} — all ${org.alter.plugins.content.bots.knights.RogueKnights.LADDER.size} of them. The realm's deadliest blade. Any of them can be hunted again for their gear: <col=0000ff>::knights</col>.")
                    } else {
                        val farming = target.rank < RogueKnightLadder.rank(p)
                        if (farming) {
                            damien(p, "You're back on <col=801700>${target.name}</col> for the spoils — good hunting. ${RogueKnightLadder.statusLine(p)} (<col=0000ff>::huntnext</col> returns you to the ladder.)")
                        } else {
                            damien(p, "Your mark: ${target.briefLine}")
                            damien(p, "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions} The marker leads; <col=0000ff>::knights</col> lists the whole ladder, and any beaten knight can be farmed again.")
                            if (!CampClearance.cleared(p, target.camp)) {
                                damien(p, "The camp guards its own: ${CampClearance.statusLine(p, target.camp)}")
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
            damien(p, "Word travels, ${p.address} — ${RogueHunt.kills(p)} cutthroats of the fallen cities put down by your hand. The realm pays its hunters. Here's your bounty.")
            damien(p, "Keep at it. ${RogueHunt.statusLine(p)}")
            return
        }
        damien(p, "At ease, ${p.address}. You stood for Lumbridge when it counted — fought, ranked, slain and supplied. A true citizen-soldier. Make us proud.")
        // UX: the teleport portal was undiscoverable — nothing in the game ever mentioned it.
        damien(p, "One more thing every soldier should know: the <col=801700>glowing portal over the courtyard fountain</col> carries you to every front, skilling ground and arena the realm holds. Use it.")
        // The Rogue Problem is offered by the quest-priority branch (RogueProblemPlugin) once
        // War-Prep I is done; until then say WHAT is coming and WHY it isn't offered yet, so a
        // soldier who came for "rogue hunting" doesn't leave thinking the Sergeant has nothing.
        if (RogueProblem.step(p) == RogueProblem.Step.NONE) {
            damien(p, "There's harder work waiting for you — the rogues bleeding our roads — but not before Vannaka's magic drills, <col=801700>War-Prep I</col>, are behind you. Finish those and ask me again.")
        }
        if (RogueHunt.kills(p) == 0) {
            damien(p, "If you're hunting work: the rogue family crawls over the road camps west of Lumbridge and the ruins of <col=801700>Fallen Varrock</col> alike. The realm pays a bounty at every milestone of cutthroats you put down — report your tally to me. <col=0000ff>::rogues</col> tracks it.")
            damien(p, "Fair warning: Varrock's streets are the wilderness — the road camps are safe. Take nothing into the ruins you can't afford to lose; the tally, at least, is yours forever.")
        } else {
            damien(p, RogueHunt.statusLine(p))
        }
        chatPlayer(p, "I won't let the realm down, sergeant.")
    }
}
