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
     * authority §8), then BRIEF / HUNT / KNIGHT / REPORT / LADDER. Text unchanged from the
     * hand-written Sergeant dialogue it was lifted out of.
     */
    private suspend fun QueueTask.sergeantRogueTalk(p: Player) {
        val s = runCatching { getRSCM(SERGEANT) }.getOrDefault(-1)
        if (RogueProblem.offerable(p)) {
            chatNpc(p, "There's harder work than reporting in, ${p.address} — an assignment, if you want it. The rogues who bleed our roads, and the deserters who lead them. It's optional, mind: the war marches with or without it, and Vannaka's lessons don't wait on it.", npc = s, title = "Recruiting Sergeant")
            if (options(p, "Tell me about the rogues.", "Not today, sergeant.", title = "Recruiting Sergeant") == 1) {
                RogueProblem.begin(p)
            } else {
                chatNpc(p, "As you were, then. The offer stands whenever you want it.", npc = s, title = "Recruiting Sergeant")
                return
            }
        }
        when (RogueProblem.step(p)) {
            RogueProblem.Step.BRIEF -> {
                chatNpc(p, "Now you're blooded and ranked, ${p.address}, I've harder work. When Varrock fell, its rogues, muggers and highwaymen scattered — onto the roads west of Lumbridge and into the ruins of <col=801700>Fallen Varrock</col> itself. They bleed our supply roads dry.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "Worse: the deserters who lead them style themselves <col=801700>Rogue Knights</col>. A whole ladder of them, weakest to strongest, camped from the Lumbridge road to the deepest wilderness.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "First, thin the rank and file — ${RogueProblem.HUNT_GOAL} of the cutthroats. Any of the family counts, wherever you fell them: the jail camp west of Lumbridge, Draynor's outskirts and the road south of Port Sarim all crawl with them, and every one is safe ground — die there and your gear waits in a pile.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "Fallen Varrock itself is thicker with them, but fair warning: its streets are the wilderness — only the bank pockets are safe. Cut your teeth on the road camps first, and take nothing into Varrock you can't afford to lose. Follow the marker.", npc = s, title = "Recruiting Sergeant")
                chatNpc(p, "Prove that and I'll pay you a soldier's purse, then set you on the first knight of the ladder. Your Knighthood you'll EARN, rung by rung — every knight on that ladder guards coin and gear.", npc = s, title = "Recruiting Sergeant")
                chatPlayer(p, "Consider it done, sergeant.")
                RogueProblem.onSergeantBriefed(p)
            }
            RogueProblem.Step.HUNT -> {
                RogueHunt.payout(p) // keep paying the lifetime milestone bounties as they hunt
                chatNpc(p, "Keep at the hunt, ${p.address} — the road camps west are safe ground, Fallen Varrock pays richer at your own risk. ${RogueProblem.statusLine(p)} Then I'll name your first Rogue Knight.", npc = s, title = "Recruiting Sergeant")
            }
            RogueProblem.Step.KNIGHT -> {
                RogueHunt.payout(p)
                val target = RogueKnightLadder.activeDef(p)
                if (target != null) {
                    chatNpc(p, "The rank and file are thinned and your soldier's purse is paid — buy <col=ffae00>Soldier</col> from Duke Horacio if you haven't. Now for the ladder, ${p.address}: ${target.briefLine}", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "You'll find the cur at <col=801700>${target.camp.display}</col> — ${target.camp.directions} The marker will lead you; <col=ffae00>::knights</col> tracks the hunt.", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "Mind: the camp guards its own. Cut down <col=ffae00>${CampClearance.goal(target.camp)}</col> of its rogues first — only then will the knight take the field against you.", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "Expect to lose a fight or two before you take them — every knight on this ladder guards the gear that beats the next one. Dying is training. Going back is winning.", npc = s, title = "Recruiting Sergeant")
                } else {
                    chatNpc(p, "Your first Rogue Knight waits — <col=ffae00>::knights</col> shows the hunt, the marker leads the way.", npc = s, title = "Recruiting Sergeant")
                }
            }
            RogueProblem.Step.REPORT -> {
                chatNpc(p, "A named knight of the rogues' ladder, dead by your hand. THAT is the work of a Knight of Lumbridge in the making, ${p.address}.", npc = s, title = "Recruiting Sergeant")
                chatPlayer(p, "What now, sergeant?")
                chatNpc(p, "Now you climb — <col=801700>Rogue Hunting II</col>: it ends when EVERY camp on the ladder is broken, the Commander last. The ladder pays as you go: knight kills, their kits, camp spoils, my bounties. When your purse reaches ${"%,d".format(Title.KNIGHT.cost)} coins, Duke Horacio will sell you the Knighthood you're already earning — rune, a companion, the wilderness.", npc = s, title = "Recruiting Sergeant")
                RogueProblem.onReportedToSergeant(p)
            }
            RogueProblem.Step.LADDER -> {
                RogueHunt.payout(p) // the bounties are part of the climb's purse
                val ladder = RogueKnights.LADDER
                chatNpc(p, "The climb's the quest now, ${p.address}: ${RogueKnightLadder.rank(p)} of ${ladder.size} knights down. Break every camp on the ladder — the Commander last — and the realm will call the Rogue Problem solved.", npc = s, title = "Recruiting Sergeant")
                val target = RogueKnightLadder.assignedDef(p)
                if (target != null) {
                    chatNpc(p, "Your mark: ${target.briefLine}", npc = s, title = "Recruiting Sergeant")
                    chatNpc(p, "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions} The marker leads; <col=ffae00>::knights</col> tracks the climb.", npc = s, title = "Recruiting Sergeant")
                    if (!CampClearance.cleared(p, target.camp)) {
                        chatNpc(p, "The camp guards its own: ${CampClearance.statusLine(p, target.camp)}", npc = s, title = "Recruiting Sergeant")
                    }
                }
                chatNpc(p, "And keep buying your ranks off Duke Horacio as the spoils come in — a Knighthood pays for itself on this road, and its rune and companion will carry you up the harder rungs.", npc = s, title = "Recruiting Sergeant")
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
