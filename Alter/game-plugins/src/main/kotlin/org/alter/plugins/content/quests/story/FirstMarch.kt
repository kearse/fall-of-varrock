package org.alter.plugins.content.quests.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
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
import org.alter.plugins.content.war.CampaignTier
import org.alter.plugins.content.war.MarchTargets
import org.alter.plugins.content.war.WarType
import org.alter.plugins.content.war.events.WarEvents
import org.alter.plugins.content.war.events.WarHooks
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **First March** — Main Story Quest 2 (spec: `docs/quests/first-march.md`). *The Last Free City*
 * taught the player how the war comes to Lumbridge; this is where Lumbridge goes to the war. General
 * Zo explains the **March** — the realm's free, scheduled public warband ([org.alter.plugins.content.war.MarchPlugin])
 * — and sends the Knights of Lumbridge across the river at the goblin camp that probed the gate that
 * morning, as a public march anyone may join. The player rallies to the column (`::march`), fights in
 * the line until the camp breaks, and reports back. Zo's debrief hands straight off to *The North*
 * ("There's something I want you to see in the north").
 *
 * Everything is a progression layer over what exists: General Zo, the `goblin_camp` march target, the
 * Knights, participation tracking, the war payout. Additions are dialogue and quest state only.
 *
 * State machine (step ids): brief → ready ⇄ march → report → DONE. The battle is resolved from
 * [WarHooks.onOperationEnded] — event-driven, never polled — for **any** march-tier op that ends
 * (Zo's own column, the Knight-Captain's scheduled march, a Lord's operation): a win with a real
 * share of the fighting goes to `report`; a win the player sat out, or a loss, goes back to `ready`
 * with a counter set so Zo's next lines fit ("we go again" / "without you in the line").
 *
 * Gate: [PREREQUISITE] (The Last Free City). Auto-begins the moment it completes — Sergeant Damien's
 * debrief already points at Zo's marches, so the journal simply picks that up. *The North* gates on
 * this quest's key.
 */
object FirstMarch : QuestDefinition(
    key = "first_march",
    displayName = "First March",
    chainIndex = QuestBook.FIRST_MARCH,
    journalVarp = QuestJournal.FIRST_MARCH_VARP,
) {
    /** The quest this follows: The Last Free City (the legacy Recruit Trials chain's key). */
    const val PREREQUISITE = "recruit_trials"

    /** Meaningful participation: at least this share of the winning op's fighting (design: 1%). */
    const val MIN_SHARE = 1

    /** The ground Zo's column strikes: the goblin camp east of the castle ([MarchTargets.GOBLIN_CAMP]). */
    const val TARGET_KEY = "goblin_camp"

    const val ZO = GeneralZoPlugin.ZO_NPC
    private const val ZO_TITLE = GeneralZoPlugin.ZO

    // Step ids.
    const val BRIEF = "brief"
    const val READY = "ready"
    const val MARCH = "march"
    const val REPORT = "report"

    /** Counter set when the column won WITHOUT the player (share below [MIN_SHARE]). */
    private const val MISSED = "missed"
    /** Counter set when the column was driven back. */
    private const val DRIVEN_BACK = "driven_back"

    private const val WAR_EFFORT = 25

    /** The goblin camp's rally point (`MarchTargets.GOBLIN_CAMP.op.objectiveTile`) — the march step's anchor. */
    val CAMP_RALLY = Tile(3254, 3234, 0)

    // --- journal ------------------------------------------------------------------------------

    private const val J_BRIEF = "General Zo musters the columns that march against the enemy. Report to him in the castle courtyard."
    private const val J_READY = "Tell General Zo when I am ready to march with the Knights of Lumbridge."
    private const val J_MARCH = "Fight beside the Knights of Lumbridge in the march and see it through to victory."
    private const val J_REPORT = "Report the march to General Zo."
    const val J_DONE = "I marched with the Knights of Lumbridge and watched the realm win a field. The muster call sounds every half hour; now I know what it is asking."

    override val prerequisites: List<Prerequisite> = listOf(Prerequisite.QuestComplete(PREREQUISITE))

    /** Begins on login, rank-up or the framework poll the moment The Last Free City is done. */
    override val autoBegin = true

    // Native quest-tab row: Tree Gnome Village's varp, relabelled (QuestTablePatch.PLAN / docs/quest-tab-handoff.md).
    override val nativeTabVarp: Int = QuestJournal.FIRST_MARCH_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.FIRST_MARCH_QUEST_COMPLETE

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            BRIEF, Objective.TalkTo(J_BRIEF, ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
            nudge = "Sergeant Damien said to answer the call for the next March. General Zo stands beside Duke Horacio.",
        ),
        QuestStep(
            READY, Objective.TalkTo(J_READY, ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
        QuestStep(
            MARCH, Objective.Manual(J_MARCH),
            anchor = CAMP_RALLY,
            nudge = "<col=0000ff>::march</col> rallies you to the column wherever it is. Stay in the battle line - you need a real share of the fighting, and the column must win.",
        ),
        QuestStep(
            REPORT, Objective.TalkTo(J_REPORT, ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
    )

    override val completionRewards: List<Reward> = listOf(Reward.WarEffort(WAR_EFFORT))

    /** 1 Quest Point — counted by the registry-derived summary-tab total. */
    override val questPoints: Int = 1

    override val completionMessage: String =
        "<col=801700>$J_DONE</col> Answer the muster call whenever you hear it. Next: <col=801700>The North</col>."

    private val zoId: Int = runCatching { getRSCM(ZO) }.getOrDefault(-1)

    init {
        // One above quest priority: this quest auto-begins for every existing player who finished
        // The Last Free City before it landed, some of them with a later quest's Zo step live at
        // the same time (First Reclamation's battle, A Kingdom Alone's question). NpcTalk breaks a
        // priority tie by registration order — plugin load order — so Main Story Quest 2 claims
        // Zo deterministically instead; it is short, and its result hook coexists with theirs.
        talk(ZO, BRIEF, PRIORITY) { p -> brief(p) }
        talk(ZO, READY, PRIORITY) { p -> readyPrompt(p) }
        talk(ZO, MARCH, PRIORITY) { p -> regroup(p) }
        talk(ZO, REPORT, PRIORITY) { p -> debrief(p) }
    }

    private const val PRIORITY = NpcTalk.PRIORITY_QUEST + 1

    // --- status -------------------------------------------------------------------------------

    /** One-line status (`::firstmarch`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>First March:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>First March — current objective:</col> ${QuestEngine.objectiveLine(p, this)}"
        else -> "<col=801700>First March:</col> not started — finish The Last Free City and it begins on its own."
    }

    // --- dialogue helpers ---------------------------------------------------------------------

    private suspend fun QueueTask.zo(p: Player, text: String) = chatNpc(p, text, npc = zoId, title = ZO_TITLE)
    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    // --- General Zo ---------------------------------------------------------------------------

    /** BRIEF: what a March is, and why the goblin camp. Flows straight into the ready prompt. */
    private suspend fun QueueTask.brief(p: Player) {
        me(p, "Sergeant Damien sent me. He said you muster the columns.")
        zo(p, "He says that about everyone he's finished with.")
        zo(p, "You held the east camp.")
        me(p, "I did.")
        zo(p, "Then you've seen what a defence looks like. Men in a line, waiting to be hit.")
        zo(p, "Now see the other half.")
        me(p, "The other half?")
        zo(p, "The March. Every half hour the Knight-Captain<br>leads ten Knights of Lumbridge out of this<br>courtyard to hit something that deserves it.<br>A camp. A road. A rogue position.")
        zo(p, "Anyone may march with them. No rank required.")
        zo(p, "The goblins that probed the gate this morning came from a camp across the river. If nobody visits, they'll be back at the gate by the end of the week.")
        zo(p, "So we visit.")
        while (true) {
            when (options(p, "What happens on a march?", "Do the knights need me?", "Understood.", title = ZO_TITLE)) {
                1 -> {
                    me(p, "What happens on a march?")
                    zo(p, "The column forms up here and walks. When it reaches the ground it fights until the enemy breaks — or the column does.")
                    zo(p, "Whoever fought shares the spoils. The realm pays its soldiers.")
                    zo(p, "Type <col=0000ff>::march</col> while the column is out and you'll be sent to it.")
                }
                2 -> {
                    me(p, "Do the knights need me?")
                    zo(p, "Ten knights alone get driven back more often than I'd like to admit. Marches fail.")
                    zo(p, "When you don't march with them, they die. So yes.")
                }
                else -> {
                    QuestEngine.satisfy(p, this@FirstMarch, BRIEF) // → READY; mutate, then narrate
                    me(p, "Understood.")
                    readyPrompt(p)
                    return
                }
            }
        }
    }

    /** READY: the player gives the word — the column sets out as a public march. After a setback, Zo says so first. */
    private suspend fun QueueTask.readyPrompt(p: Player) {
        when {
            QuestEngine.counter(p, this@FirstMarch, MISSED) > 0 -> {
                zo(p, "The column won that ground without you.")
                me(p, "I...")
                zo(p, "A march isn't something you watch from the courtyard. I need you in the line, not behind it.")
            }
            QuestEngine.counter(p, this@FirstMarch, DRIVEN_BACK) > 0 -> {
                zo(p, "We were driven back.")
                me(p, "So that's it?")
                zo(p, "No. That's a march. Now we know what didn't work, and we go again.")
            }
        }
        zo(p, "The column musters on your word. Every soldier of the realm will hear the call and may march with it.")
        when (options(p, "I'm ready. Send the column.", "Not yet - I need to prepare.", title = ZO_TITLE)) {
            1 -> {
                me(p, "I'm ready. Send the column.")
                launch(p)
            }
            else -> {
                me(p, "Not yet - I need to prepare.")
                zo(p, "Then prepare. Eat something. Sharpen something. Come back and I'll give the order.")
            }
        }
    }

    /**
     * Send the column: a public, sponsor-less MARCH on the goblin camp through the war's quest-facing
     * surface ([WarEvents.startPublicOperation] — free, supply-free, everyone may `::march`). If the
     * realm's column is already in the field — on the camp or anywhere else — the player is pointed
     * at it instead: any march-tier op counts for this quest. Advances to MARCH before the last line.
     */
    private suspend fun QueueTask.launch(p: Player) {
        val world: World = p.world
        clearSetbacks(p)
        when (val r = WarEvents.startPublicOperation(world, WarType.MARCH, TARGET_KEY)) {
            is WarEvents.StartResult.Started -> {
                Announce.broadcast(
                    world,
                    "<col=4f9b4f>General Zo has ordered a march on <col=ffae00>${r.display}</col><col=4f9b4f>! The Knights of Lumbridge set out from the castle courtyard. Any soldier may fight beside the column: <col=0000ff>::march</col><col=4f9b4f>.</col>",
                )
                logger.info { "[quests] First March: ${p.username} sent the column to $TARGET_KEY (${r.opKey})." }
                QuestEngine.advanceTo(p, this@FirstMarch, MARCH)
                zo(p, "The column sets out now. Rally to it — <col=0000ff>::march</col> — and stay in the line until the camp breaks.")
            }
            is WarEvents.StartResult.Busy -> {
                val live = WarEvents.current()
                if (live != null) {
                    QuestEngine.advanceTo(p, this@FirstMarch, MARCH)
                    if (live.targetCityKey.equals(TARGET_KEY, ignoreCase = true)) {
                        zo(p, "The column is already at the camp. Rally to it — <col=0000ff>::march</col> — and make yourself useful. If it breaks the camp with you in the line, that's your first march.")
                    } else {
                        zo(p, "The Knight-Captain's column is already out — at ${live.displayName}. Rally to it: <col=0000ff>::march</col>. A march is a march; if it wins with you in the line, that's your first.")
                        if (MarchTargets.byKey(live.targetCityKey)?.let { MarchTargets.isPvpGround(it) } == true) {
                            zo(p, "That's wilderness ground — other adventurers can attack you there. If you'd rather not, wait for the column to return and I'll send it to the camp.")
                        }
                    }
                } else {
                    zo(p, "Another operation holds that ground (${r.reason}). The column waits until the field is clear — come back shortly and I'll give the order.")
                }
            }
            is WarEvents.StartResult.NoSuchTarget, is WarEvents.StartResult.NotPublic, WarEvents.StartResult.Failed -> {
                logger.warn { "[quests] First March: launch refused ($r)." }
                zo(p, "The order won't carry today — the muster's fouled. Try me again shortly.")
            }
        }
    }

    /** MARCH at Zo: point at the live column, or — the column's back and the step never resolved — go again. */
    private suspend fun QueueTask.regroup(p: Player) {
        val live = WarEvents.current()
        if (live != null) {
            zo(p, "The column is in the field right now — at ${live.displayName}. <col=0000ff>::march</col>. Get in the line.")
            return
        }
        zo(p, "The column's back and you're standing in my courtyard. We go again.")
        launch(p)
    }

    /** REPORT: the debrief — what the March is, and the handoff to The North. Completes the quest. */
    private suspend fun QueueTask.debrief(p: Player) {
        me(p, "The field's ours.")
        zo(p, "For now. Goblins breed faster than we march.")
        me(p, "Then why bother?")
        zo(p, "Because every march we don't make, they make instead. You saw the gate this morning.")
        zo(p, "That's the March. Free, frequent, and it never waits for anyone.")
        zo(p, "You'll hear the muster call every half hour for as long as you're a soldier of this realm. Now you know what it's asking.")
        me(p, "Do I have to answer every one?")
        zo(p, "No. But the ones you skip, the knights walk alone.")
        QuestEngine.satisfy(p, this@FirstMarch, REPORT) // completes the quest — mutate, then narrate
        zo(p, "You've seen Lumbridge attacked. Now you've marched with our Knights and watched us win a field.")
        zo(p, "Don't let it go to your head. There's something I want you to see in the north.")
    }

    // --- hooks --------------------------------------------------------------------------------

    /**
     * A march-tier op ended ([WarHooks.onOperationEnded] — after the ledger and the payout; the plugin
     * filters to MARCH / GRAND_MARCH tiers). Every online player on the MARCH step is resolved here:
     * a win with a real share of the fighting → REPORT; a win they sat out → READY with [MISSED];
     * a loss → READY with [DRIVEN_BACK]. Which ground it was fought over doesn't matter — a march is
     * a march.
     */
    fun onMarchResult(world: World, r: WarHooks.WarResult) {
        if (r.tier != CampaignTier.MARCH && r.tier != CampaignTier.GRAND_MARCH) return
        world.players.forEach { p ->
            if (p.index < 0 || !p.entityType.isHumanControlled) return@forEach
            if (QuestEngine.stepId(p, this) != MARCH) return@forEach
            when {
                r.won && r.participated(p.username, MIN_SHARE) -> {
                    QuestEngine.advanceTo(p, this, REPORT)
                    p.message("<col=4f9b4f>The column holds the field. Report to General Zo.</col>")
                }
                r.won -> {
                    QuestEngine.addCounter(p, this, MISSED, 1)
                    QuestEngine.advanceTo(p, this, READY)
                    p.message("<col=801700>The knights won ${r.displayName} without you in the line - General Zo will want you in the next march.</col>")
                }
                else -> {
                    QuestEngine.addCounter(p, this, DRIVEN_BACK, 1)
                    QuestEngine.advanceTo(p, this, READY)
                    p.message("<col=801700>The column was driven back. Speak with General Zo to march again.</col>")
                }
            }
        }
    }

    private fun clearSetbacks(p: Player) {
        listOf(MISSED, DRIVEN_BACK).forEach { name ->
            val n = QuestEngine.counter(p, this, name)
            if (n > 0) QuestEngine.addCounter(p, this, name, -n)
        }
    }
}
