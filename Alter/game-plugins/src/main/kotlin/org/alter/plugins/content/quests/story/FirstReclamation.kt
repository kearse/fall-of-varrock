package org.alter.plugins.content.quests.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.areas.lumbridge.npcs.GeneralZoPlugin
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.quests.framework.Reward
import org.alter.plugins.content.quests.framework.TalkScript
import org.alter.plugins.content.war.WarType
import org.alter.plugins.content.war.events.WarEvents
import org.alter.plugins.content.war.events.WarHooks
import org.alter.plugins.content.war.outposts.SouthernWatch
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **First Reclamation** — Main Story Quest 4 (spec: `docs/quests/first-reclamation.md`). The kingdom
 * takes something back: scout the stone circle south of Fallen Varrock, win a public **Grand March**
 * on the `varrock_outskirts` target (the story's *Reclamation Column* — the existing war machinery,
 * event-started through [WarEvents]), then raise the standard at the circle and establish the
 * **Southern Watch** ([SouthernWatch]) — the realm's first forward post. Ends on the realisation
 * that Misthalin cannot retake the city alone (the hand-off to *A Kingdom Alone*).
 *
 * Everything is a progression layer over what exists: General Zo, the outskirts battlefield and its
 * marauder / Black Knight / risen-dead lines, the Knights of Lumbridge, participation tracking and
 * the war payout. The only additions are dialogue, quest state, the outpost's shared spawns and the
 * personal travel unlock.
 *
 * State machine (step ids): brief → scout_south → scout_circle → scout_varrock → report → ready →
 * battle ⇄ retry → establish → debrief → DONE. `retry` sits in the list after `battle` but is only
 * ever reached by [QuestEngine.advanceTo] (a lost / un-shared outskirts op); a won op jumps straight
 * to `establish`. The battle is resolved from [WarHooks.onOperationEnded] — event-driven, so a
 * stale ledger entry from an earlier march on the same ground can never pass the step.
 */
object FirstReclamation : QuestDefinition(
    key = "first_reclamation",
    displayName = "First Reclamation",
    chainIndex = QuestBook.FIRST_RECLAMATION,
    journalVarp = QuestJournal.FIRST_RECLAMATION_VARP,
) {
    /** The quest this follows in the opening chain (its key is the contract with that quest's PR). */
    const val PREREQUISITE = "the_north"

    /** Meaningful participation: at least this share of the winning op's fighting (design: 1%). */
    const val MIN_SHARE = 1

    const val ZO = GeneralZoPlugin.ZO_NPC
    private const val ZO_TITLE = GeneralZoPlugin.ZO

    // Step ids.
    const val BRIEF = "brief"
    const val SCOUT_SOUTH = "scout_south"
    const val SCOUT_CIRCLE = "scout_circle"
    const val SCOUT_VARROCK = "scout_varrock"
    const val REPORT = "report"
    const val READY = "ready"
    const val BATTLE = "battle"
    const val RETRY = "retry"
    const val ESTABLISH = "establish"
    const val DEBRIEF = "debrief"

    /** Counter set when the road was cleared WITHOUT the player (won, share below [MIN_SHARE]). */
    private const val MISSED = "missed"

    // The three survey areas (region 12852 dump). Sequential, so a player walking north from
    // Lumbridge meets them in story order: the lower battlefield → the ring → the gate approach.
    /** The southern approach — the lower end of the outskirts battlefield where the road arrives. */
    val SOUTH_APPROACH = Area(3200, 3336, 3250, 3352)
    /** The stone circle (walls included). */
    val STONE_CIRCLE = SouthernWatch.RING
    /** The road just beyond the circle, in front of Varrock's south gate — before the city's own lines (z3384+). */
    val VARROCK_VIEW = Area(3206, 3377, 3218, 3384)

    override val prerequisites: List<Prerequisite> = listOf(Prerequisite.QuestComplete(PREREQUISITE))

    /** Begins on login / rank-up the moment The North is done; Zo's opener also begins it on talk. */
    override val autoBegin = true

    // Native quest-tab row: Romeo & Juliet's varp, relabelled (QuestTablePatch.PLAN / docs/quest-tab-handoff.md).
    override val nativeTabVarp: Int = QuestJournal.FIRST_RECLAMATION_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.FIRST_RECLAMATION_QUEST_COMPLETE

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            BRIEF, Objective.TalkTo("General Zo believes Lumbridge is ready to reclaim its first northern position. Report to him.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
        QuestStep(
            SCOUT_SOUTH, Objective.ReachArea("Travel north to the stone circle south of Fallen Varrock and survey the southern road.", SOUTH_APPROACH),
            anchor = Tile(3228, 3344, 0),
            onLeave = { p ->
                narrate(
                    p,
                    "The southern road to Varrock stretches ahead.",
                    "The city's walls rise in the distance.",
                    "This is as far north as Lumbridge regularly projects military force.",
                    "The road behind you leads back toward Lumbridge.",
                    "Holding this ground would give soldiers somewhere to regroup before approaching Varrock.",
                )
            },
            nudge = "Walk it - the roads north are part of the lesson. Rogue Knights and the realm's marches may find you on the way.",
        ),
        QuestStep(
            SCOUT_CIRCLE, Objective.ReachArea("Inspect the stone circle as a possible forward position.", STONE_CIRCLE),
            anchor = SouthernWatch.LANDING,
            onLeave = { p ->
                narrate(
                    p,
                    "The stone circle sits on defensible ground overlooking the southern road.",
                    "It could serve as a forward staging position.",
                )
            },
        ),
        QuestStep(
            SCOUT_VARROCK, Objective.ReachArea("Look north toward Fallen Varrock from the road beyond the circle.", VARROCK_VIEW),
            anchor = Tile(3212, 3381, 0),
            onLeave = { p ->
                narrate(
                    p,
                    "Fallen Varrock lies ahead.",
                    "Even from here, the enemy presence is obvious.",
                    "Taking the city itself will require far more than Lumbridge currently possesses.",
                )
            },
        ),
        QuestStep(
            REPORT, Objective.TalkTo("Report your findings to General Zo.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
            nudge = "The stone circle could support a forward position, but the southern approach must first be cleared.",
        ),
        QuestStep(
            READY, Objective.TalkTo("Tell General Zo when you are ready to begin the reclamation.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
        QuestStep(
            BATTLE, Objective.Manual("Fight beside the reclamation column and clear the Varrock southern road."),
            anchor = Tile(3213, 3376, 0),
            nudge = "<col=0000ff>::march</col> rallies you to the column. You need a real share of the fighting - and the column must win.",
        ),
        QuestStep(
            RETRY, Objective.TalkTo("The reclamation failed. Speak with General Zo and try again.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
        QuestStep(
            ESTABLISH, Objective.Manual("The road is clear. Return to the stone circle and establish the Southern Watch - raise the standard at its heart."),
            anchor = SouthernWatch.STANDARD_TILE,
        ),
        QuestStep(
            DEBRIEF, Objective.TalkTo("Report the successful reclamation to General Zo.", ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
    )

    override val completionRewards: List<Reward> = listOf(
        Reward.WarEffort(50),
        Reward.Custom("the Southern Watch - fast travel to the forward post (portal, General Zo, ::southernwatch)") { p -> SouthernWatch.unlock(p) },
    )

    override val completionMessage =
        "Lumbridge has established the Southern Watch near Fallen Varrock. We can take ground back, but General Zo believes Misthalin cannot reclaim the city alone."

    private val zoId: Int = runCatching { getRSCM(ZO) }.getOrDefault(-1)

    init {
        // Opener: a player eligible for the quest who talks to Zo begins it on the spot (autoBegin
        // otherwise waits for a login / rank-up). Just below quest priority so any OTHER live quest
        // step on Zo still wins the conversation.
        NpcTalk.register(ZO, NpcTalk.PRIORITY_QUEST - 1) { p ->
            if (!QuestEngine.canBegin(p, this)) return@register null
            val script: TalkScript = { pl -> if (QuestEngine.begin(pl, this@FirstReclamation)) brief(pl) }
            script
        }
        talk(ZO, BRIEF) { p -> brief(p) }
        talk(ZO, REPORT) { p -> report(p) }
        talk(ZO, READY) { p -> readyPrompt(p) }
        // battle + retry share one branch: rally to a live column on the southern road, else march again.
        NpcTalk.register(ZO, NpcTalk.PRIORITY_QUEST) { p ->
            val step = QuestEngine.stepId(p, this)
            if (step != BATTLE && step != RETRY) return@register null
            val script: TalkScript = { pl -> regroup(pl) }
            script
        }
        talk(ZO, DEBRIEF) { p -> debrief(p) }
        // Scouting and the standard: one pointer line, then Zo's everyday menu — not a cold
        // re-introduction to the soldier he has just sent north.
        NpcTalk.register(ZO, NpcTalk.PRIORITY_QUEST) { p ->
            val script: TalkScript? = when (QuestEngine.stepId(p, this)) {
                SCOUT_SOUTH, SCOUT_CIRCLE, SCOUT_VARROCK -> { pl -> zoRemind(pl, "The southern road — on foot. Look the circle over,<br>see the city from beyond it, then report to me.") }
                ESTABLISH -> { pl -> zoRemind(pl, "The road's ours. Get to the circle and raise<br>the standard at its heart.") }
                else -> null
            }
            script
        }
    }

    /** A mid-quest pointer from Zo, followed by his normal march/muster menu. */
    private suspend fun QueueTask.zoRemind(p: Player, line: String) {
        zo(p, line)
        NpcTalk.runDefault(this, p, zoId)
    }

    // ------------------------------------------------------------------ dialogue

    private suspend fun QueueTask.zo(p: Player, text: String) = chatNpc(p, text, npc = zoId, title = ZO_TITLE)
    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    /** BRIEF: picks up The North's last line ("A position… it's time you helped take one back") —
     *  usually spoken seconds earlier by this same NPC — instead of re-asking about Edgeville. */
    private suspend fun QueueTask.brief(p: Player) {
        zo(p, "I said a position. Here's the one I mean.")
        me(p, "Something we keep this time.")
        zo(p, "Looking at something everyone says is lost...<br>...and deciding it isn't.")
        me(p, "What are we taking back?")
        zo(p, "Not Varrock.")
        me(p, "I wasn't getting my hopes up.")
        zo(p, "Good. There is an old approach south of the city -<br>the stone circle. We've fought there before.<br>Marches reach it now and again. But every time<br>the fighting ends, we leave.")
        me(p, "And the enemy comes back.")
        zo(p, "Exactly. A march wins ground.<br>This time we're going to use it.")
        me(p, "So this is another march?")
        zo(p, "No. A march hits the enemy and returns.<br>This is a reclamation. We clear the southern<br>approach... then we establish a permanent forward<br>post behind the line.")
        me(p, "Permanent?")
        zo(p, "As permanent as anything gets this close to Varrock.")
        zo(p, "I don't send soldiers to ground their commander<br>hasn't seen.")
        me(p, "I'm not the commander.")
        zo(p, "Today you're the person I'm sending.<br>Try to enjoy the promotion.")
        QuestEngine.satisfy(p, this@FirstReclamation, BRIEF) // mutate before the last line
        zo(p, "Go north - on foot. Walk the southern road, look the<br>stone circle over, and see the city from beyond it.<br>Then come back and tell me what you saw.")
    }

    private suspend fun QueueTask.report(p: Player) {
        zo(p, "Well?")
        me(p, "The position is usable.<br>The city isn't.")
        zo(p, "Good assessment.")
        me(p, "That's it?")
        zo(p, "You looked at Fallen Varrock and didn't suggest<br>charging the gate. That already puts you ahead of<br>several officers I've known.")
        me(p, "What do we need?")
        zo(p, "Soldiers. Supplies. And enough people willing to<br>keep fighting after they see what's waiting north<br>of that circle.")
        me(p, "So now we attack?")
        zo(p, "Now we reclaim.")
        QuestEngine.satisfy(p, this@FirstReclamation, REPORT) // → READY
        readyPrompt(p)
    }

    /** READY: the player gives the word — the Reclamation Column sets out as a public Grand March. */
    private suspend fun QueueTask.readyPrompt(p: Player) {
        zo(p, "The Reclamation Column musters on your word.<br>Every soldier of the realm will hear the call and<br>may march with it - the more swords on that road,<br>the better.")
        when (options(p, "We're ready. Send the column.", "Not yet - I need to prepare.", title = ZO_TITLE)) {
            1 -> {
                me(p, "We're ready. Send the column.")
                launch(p)
            }
            else -> {
                me(p, "Not yet - I need to prepare.")
                zo(p, "Then prepare. The road won't get any friendlier.<br>Come back when you're ready and I'll give the order.")
            }
        }
    }

    /**
     * Start the reclamation: a public, sponsor-less GRAND MARCH on the Varrock outskirts through the
     * war's quest-facing surface ([WarEvents.startPublicOperation] — free, supply-free, everyone may
     * `::march`). A column already fighting THAT ground is joined instead; any other live op means
     * the reclamation waits (two ops never share ground). Advances to BATTLE before the last line.
     */
    private suspend fun QueueTask.launch(p: Player) {
        val world: World = p.world
        clearMissed(p)
        when (val r = WarEvents.startPublicOperation(world, WarType.GRAND_MARCH, SouthernWatch.TARGET_KEY)) {
            is WarEvents.StartResult.Started -> {
                Announce.broadcast(
                    world,
                    "<col=ffcc00>General Zo has ordered a reclamation push toward Fallen Varrock! The Knights of Lumbridge march on the southern road. All soldiers of the realm may join the attack - <col=0000ff>::march</col><col=ffcc00>.</col>",
                )
                logger.info { "[quests] First Reclamation: ${p.username} launched the Reclamation Column (${r.opKey})." }
                QuestEngine.advanceTo(p, this@FirstReclamation, BATTLE)
                zo(p, "The Reclamation Column sets out now. Rally to it -<br><col=0000ff>::march</col> - and don't come back until the<br>southern road is ours.")
            }
            is WarEvents.StartResult.Busy -> {
                val live = WarEvents.current()
                if (live != null && live.targetCityKey.equals(SouthernWatch.TARGET_KEY, ignoreCase = true)) {
                    QuestEngine.advanceTo(p, this@FirstReclamation, BATTLE)
                    zo(p, "The column is already fighting on the southern<br>road. Rally to it - <col=0000ff>::march</col> - and make yourself<br>useful. If it holds the road, that's our<br>reclamation.")
                } else {
                    val where = live?.displayName?.let { "The realm's column is out at $it" } ?: "Another operation holds that ground (${r.reason})"
                    zo(p, "$where.<br>The reclamation waits until the field is clear -<br>come back shortly and I'll give the order.")
                }
            }
            is WarEvents.StartResult.NoSuchTarget, is WarEvents.StartResult.NotPublic, WarEvents.StartResult.Failed -> {
                logger.warn { "[quests] First Reclamation: launch refused ($r)." }
                zo(p, "The order won't carry today - the muster's fouled.<br>Try me again shortly.")
            }
        }
    }

    /** BATTLE / RETRY at Zo: point at a live column on the road, or send the column again. */
    private suspend fun QueueTask.regroup(p: Player) {
        val live = WarEvents.current()
        if (live != null && live.targetCityKey.equals(SouthernWatch.TARGET_KEY, ignoreCase = true)) {
            zo(p, "The column is on the southern road right now.<br>Rally to it - <col=0000ff>::march</col>. The road isn't ours<br>until the line breaks.")
            return
        }
        if (QuestEngine.stepId(p, this@FirstReclamation) == RETRY) {
            if (QuestEngine.counter(p, this@FirstReclamation, MISSED) > 0) {
                zo(p, "The road was cleared - without you.")
                me(p, "I...")
                zo(p, "A reclamation isn't a victory you watch from<br>Lumbridge. I need you on that road, not behind it.")
            } else {
                zo(p, "We were pushed off the road.")
                me(p, "So that's it?")
                zo(p, "No.")
                me(p, "We just lost.")
                zo(p, "Yes.<br>And now we know what didn't work.")
            }
            zo(p, "We go again.")
        } else {
            zo(p, "The column's back and the road isn't ours yet.<br>We go again.")
        }
        launch(p)
    }

    private suspend fun QueueTask.debrief(p: Player) {
        me(p, "We took it.")
        zo(p, "We took the position.")
        me(p, "What's the difference?")
        zo(p, "About fifty yards and several thousand corpses.")
        me(p, "Right.")
        zo(p, "The roads around it are still hostile. Enemy patrols<br>will return. The dead certainly will. But now when<br>they do... we have somewhere to meet them.")
        zo(p, "The Southern Watch is holding.")
        me(p, "For now.")
        zo(p, "For now is how every kingdom starts.")
        me(p, "We're closer to Varrock.")
        zo(p, "Yes.")
        me(p, "So what's next?")
        zo(p, "...")
        me(p, "General?")
        zo(p, "You saw the city.")
        me(p, "I did.")
        zo(p, "How many soldiers do you think are in there?")
        me(p, "Too many.")
        zo(p, "How many do you think we have?")
        me(p, "...We can't take Varrock.")
        zo(p, "Not alone.")
        zo(p, "We can hold Lumbridge. We can clear roads. We can<br>establish posts. We can even bloody the enemy at<br>Varrock's doorstep.")
        zo(p, "But storming those walls? Misthalin doesn't have<br>enough soldiers. Not anymore.")
        QuestEngine.satisfy(p, this@FirstReclamation, DEBRIEF) // completes the quest — before the last lines
        me(p, "So we find more.")
        zo(p, "Exactly.")
        zo(p, "The Southern Watch is yours to use now. Ask me, or<br>the portal, and you'll stand at the circle.")
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The standard's **Capture** at the circle (installed on [SouthernWatch.onCapture]): on the
     * ESTABLISH step this raises the Lumbridge standard and moves the quest on. True = consumed.
     */
    fun raiseStandard(p: Player): Boolean {
        if (!QuestEngine.satisfy(p, this, ESTABLISH)) return false // mutate first — the lines are narration
        p.message("<col=ffae00>You raise Lumbridge's standard over the southern approach.</col>")
        p.message("<col=ffae00>For the first time in twelve years, the kingdom has established a forward position in the shadow of Fallen Varrock.</col>")
        return true
    }

    /**
     * A war op on the Varrock outskirts ended ([WarHooks.onOperationEnded] — after the ledger and the
     * payout). Every online player on the BATTLE step is resolved here: a win with a real share of
     * the fighting clears the road (→ ESTABLISH); a win they sat out sends them back to Zo with
     * [MISSED] set; a loss sends them back to Zo (→ RETRY). Any tier counts — Zo's Grand March or
     * the Knight-Captain's own scheduled march on the same ground.
     */
    fun onSouthernRoadResult(world: World, r: WarHooks.WarResult) {
        var cleared = false
        world.players.forEach { p ->
            if (p.index < 0 || !p.entityType.isHumanControlled) return@forEach
            if (QuestEngine.stepId(p, this) != BATTLE) return@forEach
            when {
                r.won && r.participated(p.username, MIN_SHARE) -> {
                    cleared = true
                    QuestEngine.advanceTo(p, this, ESTABLISH)
                    p.message("<col=4f9b4f>The southern road is yours. Return to the stone circle and raise the standard.</col>")
                }
                r.won -> {
                    QuestEngine.addCounter(p, this, MISSED, 1)
                    QuestEngine.advanceTo(p, this, RETRY)
                    p.message("<col=801700>The column cleared the southern road without you - General Zo will need you in the next push.</col>")
                }
                else -> {
                    QuestEngine.advanceTo(p, this, RETRY)
                    p.message("<col=801700>The reclamation column was driven back. Speak with General Zo to organize another attempt.</col>")
                }
            }
        }
        if (cleared) Announce.broadcast(world, "<col=ffcc00>The enemy line breaks. The southern approach to Varrock is clear.</col>")
    }

    private fun clearMissed(p: Player) {
        val n = QuestEngine.counter(p, this, MISSED)
        if (n > 0) QuestEngine.addCounter(p, this, MISSED, -n)
    }

    /** Survey narration — sent synchronously so it lands BEFORE the engine's next-objective line. */
    private fun narrate(p: Player, vararg lines: String) {
        lines.forEach { p.message("<col=5d4037>$it</col>") }
    }
}
