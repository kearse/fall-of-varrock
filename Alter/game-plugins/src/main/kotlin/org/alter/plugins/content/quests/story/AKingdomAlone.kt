package org.alter.plugins.content.quests.story

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.areas.lumbridge.npcs.GeneralZoPlugin
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.war.address
import org.alter.rscm.RSCM.getRSCM

/**
 * **A Kingdom Alone** — Main Story Quest 5 (`docs/quests/a-kingdom-alone.md`).
 *
 * Short, dialogue-only, strategically important: after First Reclamation proved lost ground can
 * be taken back, Duke Horacio and General Zo lay out why Lumbridge cannot retake Varrock alone and
 * the four problems the surviving kingdoms must solve first. Completing it opens the regional
 * campaign phase — the four [StrategicObjective]s begin at once ([StrategicObjectives.open]).
 *
 * Nothing new: Duke Horacio (3220,3211) and General Zo (3220,3210) already stand side by side in the
 * Lumbridge command area, so the joint scene is one conversation at the Duke with Zo's lines
 * spoken through his own portrait. No combat, no items, no map, no reward screen — the reward is
 * the unlock (and 2 quest points).
 *
 * Every transition is satisfied right before the LAST line of its beat (mutate-before-narrate);
 * a closed chat box re-runs the beat, never skips it.
 */
object AKingdomAlone : QuestDefinition(
    key = "a_kingdom_alone",
    displayName = "A Kingdom Alone",
    chainIndex = QuestBook.A_KINGDOM_ALONE,
    journalVarp = QuestJournal.KINGDOM_ALONE_VARP,
) {
    const val KEY = "a_kingdom_alone"

    const val DUKE = "npc.duke_horacio"
    const val ZO = GeneralZoPlugin.ZO_NPC

    /** Duke Horacio's post in the command area (one tile north of General Zo). */
    val DUKE_TILE = Tile(3220, 3211, 0)

    // Step ids (persisted strings — the client entry's ordinals are their 1-based indices).
    const val REPORT = "report"
    const val ZO_STEP = "zo"
    const val DUKE_STEP = "duke"
    const val STRATEGY = "strategy"

    override val prerequisites: List<Prerequisite> = listOf(Prerequisite.QuestComplete("first_reclamation"))

    /** The journal reads "Duke Horacio wants a report" the moment First Reclamation ends. */
    override val autoBegin: Boolean = true

    override val nativeTabVarp: Int = QuestJournal.KINGDOM_ALONE_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.KINGDOM_ALONE_QUEST_COMPLETE
    override val questPoints: Int = 2

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            REPORT,
            Objective.TalkTo("Duke Horacio wants a report on the kingdom's position after establishing the Southern Watch.", DUKE),
            anchor = DUKE_TILE, anchorNpc = DUKE,
        ),
        QuestStep(
            ZO_STEP,
            Objective.TalkTo("Speak with General Zo about what would be required to attack Fallen Varrock.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
        QuestStep(
            DUKE_STEP,
            Objective.TalkTo("Report General Zo's assessment to Duke Horacio.", DUKE),
            anchor = DUKE_TILE, anchorNpc = DUKE,
        ),
        QuestStep(
            STRATEGY,
            Objective.TalkTo("Discuss the surviving kingdoms and the four problems preventing an assault on Varrock.", DUKE),
            anchor = DUKE_TILE, anchorNpc = DUKE,
        ),
    )

    override val completionMessage: String =
        "Lumbridge cannot reclaim Varrock alone. I can now work across Gielinor to solve BREACH, SECURE, UNDERSTAND and SUSTAIN in preparation for a future assault."

    /** The largest unlock in the early campaign: the regional phase opens, all four objectives at once. */
    override fun onComplete(p: Player) {
        p.message("<col=801700>The war for Varrock is no longer only Misthalin's war.</col> Four strategic problems now stand between the surviving kingdoms and a sustained assault on Fallen Varrock.")
        StrategicObjectives.open(p)
        p.message("<col=801700>Regional campaigns unlocked:</col> BREACH (Asgarnia), SECURE (Morytania), UNDERSTAND (Wilderness, then the Desert), SUSTAIN (Kandarin and the War Effort). <col=801700>::strategy</col> tracks them; the Council of Gielinor convenes once all four are solved.")
    }

    // ---- dialogue ---------------------------------------------------------------------------------

    private val dukeId: Int get() = getRSCM(DUKE)
    private val zoId: Int get() = getRSCM(ZO)

    private suspend fun QueueTask.duke(p: Player, text: String) = chatNpc(p, text, npc = dukeId, title = "Duke Horacio")
    private suspend fun QueueTask.zo(p: Player, text: String) = chatNpc(p, text, npc = zoId, title = GeneralZoPlugin.ZO)
    private suspend fun QueueTask.you(p: Player, text: String) = chatPlayer(p, text)

    init {
        // START → ZO: the Duke hears the standard is flying, and sends the player to Zo.
        talk(DUKE, REPORT) { p ->
            duke(p, "I heard the standard is flying at the Southern Watch.")
            you(p, "It is.")
            duke(p, "Then you've done something this kingdom hasn't managed in twelve years.")
            you(p, "Taken ground back.")
            duke(p, "Yes.")
            duke(p, "...And now we discover whether we can keep doing it.")
            duke(p, "Zo has the numbers for an assault on Varrock.<br>Get them from him — he's beside me.")
            QuestEngine.satisfy(p, AKingdomAlone, REPORT)
            duke(p, "I suspect you won't enjoy them.")
            you(p, "I'm getting used to that.")
        }

        // ZO → DUKE: the question, the reality, and what the Southern Watch did and did not prove.
        // Not a re-run of First Reclamation's debrief ("we can't take Varrock — not alone"): the
        // Duke has just sent the player back for the arithmetic behind that answer.
        talk(ZO, ZO_STEP) { p ->
            zo(p, "The Duke sent you back to me.")
            you(p, "He says you've done the arithmetic on Varrock.")
            zo(p, "I have. You won't like it.")
            zo(p, "Think we can take it?")
            when (options(p, "Not with what we have.", "Give me enough Knights.", "We won't know until we try.", title = GeneralZoPlugin.ZO)) {
                1 -> { you(p, "Not with what we have."); zo(p, "No. We can't.") }
                2 -> { you(p, "Give me enough Knights."); zo(p, "That's the problem."); zo(p, "We don't have enough Knights.") }
                else -> { you(p, "We won't know until we try."); zo(p, "We would know. Once.") }
            }
            zo(p, "Lumbridge has done better than anyone expected.")
            zo(p, "We survived. We rebuilt a fighting force. We reopened roads.")
            zo(p, "We established a position beneath Varrock itself.")
            you(p, "That sounds like progress.")
            zo(p, "It is.")
            zo(p, "But a forward post and a city are very different things.")
            zo(p, "If we march on Varrock today... we cannot reliably break its defences.")
            zo(p, "We cannot guarantee Misthalin remains secure while our army is north.")
            zo(p, "We don't actually understand what destroyed the city.")
            zo(p, "And even if we solve all three... we cannot keep an army that size supplied for long.")
            zo(p, "...We would lose.")
            you(p, "So the Southern Watch was pointless?")
            zo(p, "No.")
            zo(p, "It proved something.")
            you(p, "What?")
            zo(p, "That the enemy can be pushed back.")
            QuestEngine.satisfy(p, AKingdomAlone, ZO_STEP)
            zo(p, "What it did not prove... is that Lumbridge can finish the job alone.")
            zo(p, "Go and tell the Duke. He'll want to hear it from you.")
        }

        // ZO step: the Duke points one tile south instead of opening the rank ladder on a player he
        // has just sent to Zo — then his everyday menu, so ranks stay reachable.
        talk(DUKE, ZO_STEP) { p ->
            duke(p, "Zo is beside me, ${p.address}. Ask him for the numbers.")
            NpcTalk.runDefault(this, p, dukeId)
        }

        // DUKE → STRATEGY: the report, then straight into the strategy discussion (same conversation).
        talk(DUKE, DUKE_STEP) { p ->
            you(p, "Zo says we don't have what we need.")
            duke(p, "He's right.")
            you(p, "So what now?")
            QuestEngine.satisfy(p, AKingdomAlone, DUKE_STEP)
            duke(p, "We stop pretending Misthalin is the only kingdom left in Gielinor.")
            strategy(p)
        }

        // STRATEGY → DONE (re-entry for a player who closed the chat mid-discussion).
        talk(DUKE, STRATEGY) { p ->
            duke(p, "As I was saying: Misthalin is not the only kingdom left in Gielinor.")
            strategy(p)
        }
    }

    /**
     * The surviving kingdoms, why there is no simple alliance quest, the four problems (short —
     * never lore dumps), "which one first?", and the closing exchange. Completes the quest right
     * before the Duke's last line.
     */
    private suspend fun QueueTask.strategy(p: Player) {
        // The entire world did not fall.
        duke(p, "Falador still stands.")
        duke(p, "Kandarin still trades.")
        duke(p, "People still live beyond the Salve.")
        duke(p, "The Wilderness still keeps secrets older than Varrock's ruin.")
        duke(p, "And the desert remembers things our scholars have forgotten.")
        you(p, "So we ask them for help?")
        duke(p, "Eventually.")
        duke(p, "But don't make the mistake of thinking every kingdom is sitting around waiting to save us.")
        duke(p, "Falador has wars of its own. Morytania has reasons to distrust anything marching toward its border.")
        duke(p, "Trade routes have broken. Old wounds have opened.")
        duke(p, "And anyone who lived through the Fall has learned to be suspicious of Varrock.")
        you(p, "So nobody is coming.")
        duke(p, "Not yet.")
        you(p, "Then what do I do?")
        duke(p, "Find out what each problem actually requires.")
        duke(p, "Then solve it.")

        // BREACH — Asgarnia.
        duke(p, "Varrock won't fall because we bring more swords. Someone has to get those swords through its defences.")
        duke(p, "Falador has soldiers, engineers and weapons we don't. Start there if you want to know how we break the city open.")
        p.message("<col=801700>BREACH — Asgarnia:</col> ${Breach.problem} Lead: ${Breach.lead}.")

        // SECURE — Morytania.
        zo(p, "If our army marches north, something else notices.")
        you(p, "Morytania.")
        zo(p, "Among others.")
        zo(p, "We cannot fight for Varrock while wondering whether another war is opening behind us.")
        p.message("<col=801700>SECURE — Morytania:</col> ${Secure.problem} Lead: ${Secure.lead}.")

        // UNDERSTAND — Wilderness → Desert. The most unsettling one; it reveals nothing.
        you(p, "We already know what happened. Zemouregal attacked. Arrav led the dead. Varrock fell.")
        zo(p, "We know what people saw.")
        you(p, "What's the difference?")
        zo(p, "That's what worries me.")
        p.message("<col=801700>UNDERSTAND — Wilderness / Desert:</col> ${Understand.problem} Lead: ${Understand.lead}.")

        // SUSTAIN — Kandarin + the War Effort.
        zo(p, "You've already seen what one battle does to our stores.")
        you(p, "The Quartermaster made that fairly clear.")
        zo(p, "Now imagine feeding thousands. Arrows. Food. Medicine. Replacement armour. Horses. Transport.")
        zo(p, "For weeks.")
        you(p, "That sounds expensive.")
        zo(p, "That's the first sensible thing you've said all day.")
        p.message("<col=801700>SUSTAIN — Kandarin / War Effort:</col> ${Sustain.problem} Lead: ${Sustain.lead}.")

        // The player does not choose one.
        you(p, "Which one do I do first?")
        duke(p, "Whichever opportunity opens first.")
        you(p, "That's not very helpful.")
        zo(p, "Neither is a war.")
        duke(p, "Work where you can. Falador. Morytania. The Wilderness. Kandarin.")
        duke(p, "Progress in one may open opportunities in another. We need all four problems solved before we commit to Varrock.")

        // Final exchange.
        you(p, "So that's it? Fix half of Gielinor?")
        zo(p, "Only the useful half.")
        duke(p, "General.")
        zo(p, "What?")
        you(p, "And when all of this is done?")
        duke(p, "Then we stop asking whether Varrock can be reclaimed.")
        QuestEngine.satisfy(p, AKingdomAlone, STRATEGY) // → complete: opens the regional phase
        duke(p, "...And decide how.")
    }
}
