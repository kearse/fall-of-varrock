package org.alter.plugins.content.war.roguehunt

import dev.openrune.cache.CacheManager.getNpc
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.bots.knights.CampClearance
import org.alter.plugins.content.bots.knights.RogueKnightLadder
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.hunt.TargetMarker
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * Wiring for [RogueProblem] (the Act II "Rogue Problem" quest). Resumes the per-player state on
 * login, drives the poll timer, counts quest-scoped rogue kills on the additive death list (cheap —
 * bails instantly for non-rogue kills, mirroring [RogueHuntPlugin]), serves `::rogueproblem`, and
 * owns the quest's Recruiting Sergeant dialogue as a quest-priority [NpcTalk] branch (the
 * Sergeant's click is bound once by `RecruitTrialsPlugin`; this branch claims it while the
 * assignment is offerable or live, and passes otherwise).
 *
 * The quest is *offered* by the Recruiting Sergeant once War-Prep I is done — it is OPTIONAL and
 * nothing auto-starts it (design authority §8).
 */
class RogueProblemPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { RogueProblem.resumeOnLogin(player) }

        // The Sergeant speaks the quest's beats only for a recruit who has finished the trials and
        // is either being offered the assignment or is on one of its guided steps.
        NpcTalk.register(SERGEANT, NpcTalk.PRIORITY_QUEST) { p ->
            val live = RogueProblem.step(p).let { it != RogueProblem.Step.NONE && it != RogueProblem.Step.DONE }
            if (RecruitTrials.step(p) == RecruitTrials.Step.DONE && (live || RogueProblem.offerable(p))) {
                { player -> sergeantRogueTalk(player) }
            } else {
                null
            }
        }

        onTimer(RogueProblem.TIMER) { RogueProblem.pollTick(player) }

        // The HUNT step's guidance (mirrors the ladder's marker): the arrow hunts LIVE rogues —
        // the nearest tier rogue of a safe road camp when one's in reach, the nearest safe camp
        // from afar — never a bare map tile, so it always leads to something the hunter can kill.
        TargetMarker.register(TargetMarker.PRIORITY_HUNT) { p ->
            if (RogueProblem.step(p) != RogueProblem.Step.HUNT) null else huntMark(p)
        }

        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (RogueProblem.step(killer) == RogueProblem.Step.HUNT && RogueHunt.isRogue(npcName(npc.id))) {
                RogueProblem.onRogueKill(killer)
            }
        }

        onCommand("rogueproblem", description = "Open your Rogue Hunting quest in the Quest Journal") {
            player.message(RogueProblem.statusLine(player))
            val idx = if (RogueProblem.step(player).ordinal >= RogueProblem.Step.KNIGHT.ordinal)
                QuestBook.ROGUE_HUNTING_II else QuestBook.ROGUE_HUNTING_I
            QuestBook.open(player, idx)
        }
    }

    private fun npcName(id: Int): String? =
        runCatching { getNpc(id).name }.getOrNull()

    /**
     * The Recruiting Sergeant's Rogue Problem beats — the OPTIONAL assignment's offer (design
     * authority §8), then BRIEF / HUNT / KNIGHT / REPORT / LADDER. Lines are hand-wrapped at
     * ≤ ~48 visible characters, four rows per box — the chat box cuts off anything longer.
     */
    private suspend fun QueueTask.sergeantRogueTalk(p: Player) {
        val s = runCatching { getRSCM(SERGEANT) }.getOrDefault(-1)
        if (RogueProblem.offerable(p)) {
            chatNpc(p, "There's harder work, if you want it, ${p.address}:<br>the rogues who bleed our roads, and the deserters<br>who lead them. Optional — the war marches with<br>or without it.", npc = s, title = "Sergeant Damien")
            // A veteran who already challenged the knights directly must still reach the ladder
            // chatter from here — this quest-priority branch claims every click while the offer
            // stands, so the default branch's "My Rogue Knight hunt" never gets a turn.
            val direct = RogueKnightLadder.unlocked(p)
            val choice = if (direct) {
                options(p, "Tell me about the rogues.", "My Rogue Knight hunt.", "Not today, sergeant.", title = "Sergeant Damien")
            } else {
                options(p, "Tell me about the rogues.", "I'd rather fight the Rogue Knights directly.", "Not today, sergeant.", title = "Sergeant Damien")
            }
            when (choice) {
                1 -> RogueProblem.begin(p)
                2 -> {
                    if (direct) {
                        RogueKnightLadder.sergeantLines(p).forEach { chatNpc(p, it, npc = s, title = "Sergeant Damien") }
                    } else {
                        // Design authority §9: veteran PKers skip the lessons and challenge the
                        // Rogues directly. No quest, no hunt — the ladder simply opens.
                        chatNpc(p, "Straight to the deserters, is it? Fair enough —<br>the ladder doesn't care how you found it.<br>Fourteen knights, weakest to strongest. Every camp<br>guards its own: thin the rogues, then the knight.", npc = s, title = "Sergeant Damien")
                        RogueKnightLadder.optIn(p)
                        chatNpc(p, "The assignment's purse is here if you want it.<br>Now go earn your War Effort.", npc = s, title = "Sergeant Damien")
                    }
                    return
                }
                else -> {
                    chatNpc(p, "As you were, then.<br>The offer stands whenever you want it.", npc = s, title = "Sergeant Damien")
                    return
                }
            }
        }
        when (RogueProblem.step(p)) {
            RogueProblem.Step.BRIEF -> {
                chatNpc(p, "When Varrock fell, its rogues, muggers and<br>highwaymen scattered — onto the roads west of<br>Lumbridge and into the ruins of <col=801700>Fallen Varrock</col>.<br>They bleed our supply roads dry.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "Worse: the deserters leading them call themselves<br><col=801700>Rogue Knights</col>. A ladder of them, weakest to<br>strongest, camped from the Lumbridge road to the<br>deepest wilderness.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "First, thin the rank and file — <col=ffae00>${RogueProblem.HUNT_GOAL}</col> of the<br>cutthroats. Any of the family counts, wherever<br>you fell them.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "The jail camp west of here, Draynor's outskirts<br>and the road south of Port Sarim all crawl with<br>them — safe ground, all of it. Die there and<br>your gear waits in a pile.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "Fallen Varrock is thicker with them — but its<br>streets are the wilderness, and only the bank<br>pockets are safe. Cut your teeth on the road camps<br>first. Follow the marker.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "Do that and I'll pay you a soldier's purse, then<br>set you on the first knight of the ladder. Every<br>knight on it guards coin and gear — your<br>Knighthood, rung by rung.", npc = s, title = "Sergeant Damien")
                chatPlayer(p, "Consider it done, sergeant.")
                RogueProblem.onSergeantBriefed(p)
            }
            RogueProblem.Step.HUNT -> {
                RogueHunt.payout(p) // keep paying the lifetime milestone bounties as they hunt
                chatNpc(p, "Keep at the hunt, ${p.address} — the road camps west<br>are safe; Varrock pays richer, at your own risk.<br>${RogueProblem.statusLine(p)}", npc = s, title = "Sergeant Damien")
            }
            RogueProblem.Step.KNIGHT -> {
                RogueHunt.payout(p)
                val target = RogueKnightLadder.activeDef(p)
                if (target != null) {
                    chatNpc(p, "The rank and file are thinned and your purse is<br>paid — buy <col=ffae00>Soldier</col> off the Duke if you haven't.<br>Now for the ladder.", npc = s, title = "Sergeant Damien")
                    chatNpc(p, "Your mark: ${target.briefLine}", npc = s, title = "Sergeant Damien")
                    chatNpc(p, "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions}<br>The marker leads; <col=0000ff>::knights</col> tracks the hunt.", npc = s, title = "Sergeant Damien")
                    chatNpc(p, "Mind: the camp guards its own. Cut down <col=ffae00>${CampClearance.goal(target.camp)}</col> of its<br>rogues first — only then will the knight take<br>the field against you.", npc = s, title = "Sergeant Damien")
                    chatNpc(p, "Expect to lose a fight or two. Every knight on<br>this ladder guards the gear that beats the next.<br>Dying is training. Going back is winning.", npc = s, title = "Sergeant Damien")
                } else {
                    chatNpc(p, "Your first Rogue Knight waits — <col=0000ff>::knights</col> shows<br>the hunt, the marker leads the way.", npc = s, title = "Sergeant Damien")
                }
            }
            RogueProblem.Step.REPORT -> {
                chatNpc(p, "A named knight of the ladder, dead by your hand.<br>That's a Knight of Lumbridge in the making.", npc = s, title = "Sergeant Damien")
                chatPlayer(p, "What now, sergeant?")
                chatNpc(p, "Now you climb — <col=801700>Rogue Hunting II</col>. It ends when<br>every camp on the ladder is broken, the Commander<br>last. The ladder pays as you go: knight kills,<br>their kits, camp spoils, my bounties.", npc = s, title = "Sergeant Damien")
                chatNpc(p, "At ${"%,d".format(Title.KNIGHT.cost)} coins, Duke Horacio will sell you the<br>Knighthood you're already earning — rune,<br>a companion, the wilderness.", npc = s, title = "Sergeant Damien")
                RogueProblem.onReportedToSergeant(p)
            }
            RogueProblem.Step.LADDER -> {
                RogueHunt.payout(p) // the bounties are part of the climb's purse
                val ladder = RogueKnights.LADDER
                chatNpc(p, "The climb's the quest now: ${RogueKnightLadder.rank(p)} of ${ladder.size} knights down.<br>Break every camp — the Commander last — and the<br>realm will call the Rogue Problem solved.", npc = s, title = "Sergeant Damien")
                val target = RogueKnightLadder.assignedDef(p)
                if (target != null) {
                    chatNpc(p, "Your mark: ${target.briefLine}", npc = s, title = "Sergeant Damien")
                    chatNpc(p, "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions}<br>The marker leads; <col=0000ff>::knights</col> tracks the climb.", npc = s, title = "Sergeant Damien")
                    if (!CampClearance.cleared(p, target.camp)) {
                        chatNpc(p, "The camp guards its own: ${CampClearance.statusLine(p, target.camp)}", npc = s, title = "Sergeant Damien")
                    }
                }
                // The rank nudge only while it applies — a Knight has nothing left to buy here.
                if (p.title.ordinal < Title.KNIGHT.ordinal) {
                    chatNpc(p, "Keep buying your ranks off Duke Horacio as the<br>spoils come in — a Knight's rune and companion<br>carry you up the harder rungs.", npc = s, title = "Sergeant Damien")
                }
            }
            else -> {} // NONE (declined) / DONE — the branch never claims these
        }
    }

    private companion object {
        const val SERGEANT = "npc.sergeant_damien"
    }

    /** The nearest live safe-road tier rogue to [p] (named knights excluded — the boss is the
     *  KNIGHT step's prize), falling back to the nearest safe camp's center from afar. */
    private fun huntMark(p: Player): TargetMarker.Mark {
        val camps = RogueKnights.CAMPS.filter { it.safe }
        var best: PkBot? = null
        var bestDist = Int.MAX_VALUE
        BotManager.active.forEach { bot ->
            if (bot.index < 0 || bot.isDead()) return@forEach
            if (bot.attr[KNIGHT_KEY_ATTR] != null) return@forEach
            if (camps.none { it.key == bot.zoneKey }) return@forEach
            val dist = bot.tile.getDistance(p.tile)
            if (dist < bestDist) {
                bestDist = dist
                best = bot
            }
        }
        val nearestCamp = camps.minByOrNull { it.center.getDistance(p.tile) }?.center
        return TargetMarker.Mark(entity = best, fallback = nearestCamp)
    }
}
