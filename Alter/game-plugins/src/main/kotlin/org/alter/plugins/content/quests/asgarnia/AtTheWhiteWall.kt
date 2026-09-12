package org.alter.plugins.content.quests.asgarnia

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
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
import org.alter.rscm.RSCM.getRSCM

/**
 * **At the White Wall** — Asgarnia regional campaign (BREACH), Quest 1 (`docs/quests/at-the-white-wall.md`).
 *
 * The regional opener: why Falador does not simply send its army to Varrock. The player reaches
 * Falador's NORTH gate, is stopped at a White Knight checkpoint, fights off a Kinshra raid on it,
 * meets Sir Amik Varze (his stock spawn, top floor of the White Knights' Castle) who explains the
 * three pressures on Asgarnia — the Kinshra front, the trolls at Burthorpe, the collapsed
 * artillery supply — then Sir Tiffy Cashien (his park bench) who sends them back to read the
 * ground, and finally reports to Amik, who points them at Burthorpe.
 *
 * A framework quest ([QuestDefinition]): dialogue + state + a shared-world scripted skirmish
 * ([WhiteWallCheckpoint]) + three area observations. Everything sits on the existing world:
 * unchanged Falador, stock Amik/Tiffy/White Knights/Black Knights, stock combat. No new NPC, map,
 * enemy, instance or mechanic (the integration audit is in the spec).
 *
 * Gate: **A Kingdom Alone** (`a_kingdom_alone`, Main Story Quest 5 — its completion opens the four
 * regional objectives, BREACH among them). Auto-begins the moment the gate opens.
 *
 * Journal varp [QuestJournal.WHITE_WALL_VARP] (generic framework packing); native quest-tab row =
 * the relabelled **Recruitment Drive** row (varp 657 — that quest's own start NPC is Sir Amik).
 *
 * Wiring (Talk-to binds + everyday lines, the checkpoint timer, damage-share kill credit,
 * `::whitewall`) is in [AtTheWhiteWallPlugin]; the garrison and the raid in [WhiteWallCheckpoint].
 */
object AtTheWhiteWall : QuestDefinition(
    key = "at_the_white_wall",
    displayName = "At the White Wall",
    chainIndex = QuestBook.AT_THE_WHITE_WALL,
    journalVarp = QuestJournal.WHITE_WALL_VARP,
) {

    /** Stock White Knight (1798) — the checkpoint garrison AND Falador's castle knights share it. */
    const val WHITE_KNIGHT = WhiteWallCheckpoint.WHITE_KNIGHT

    /** Sir Amik Varze — the id the world spawns place on the castle's top floor (`npc_spawns.json`: 4771 @ 2960,3336,2). */
    const val AMIK = "npc.sir_amik_varze_4771"

    /** Sir Tiffy Cashien — his Falador Park bench (`npc_spawns.json`: 4687 @ 2997,3373,0). */
    const val TIFFY = "npc.sir_tiffy_cashien"

    /** The quest before this one: A Kingdom Alone (Main Story Quest 5) opens the regional phase. */
    const val KINGDOM_ALONE_KEY = "a_kingdom_alone"

    /** Native quest tab: Recruitment Drive's progress varp + completion value (dbrow 118, quest id 86). */
    private const val NATIVE_TAB_VARP = 657
    private const val NATIVE_TAB_COMPLETE = 2

    private const val WAR_EFFORT = 25
    const val RAIDERS_TO_DEFEAT = 5

    // --- step ids (persisted — never rename) --------------------------------------------------
    const val S_TRAVEL = "travel"
    const val S_CHECKPOINT = "checkpoint"
    const val S_DEFEND = "defend"
    const val S_AMIK = "amik"
    const val S_TIFFY = "tiffy"
    const val S_FRONT = "front"
    const val S_REPORT = "report"

    // --- world anchors (all existing, unchanged) ----------------------------------------------

    /** The checkpoint: the road immediately outside Falador's north gate (the gate opening is x2964-2967, z3392-3394). */
    val GATE: Tile get() = WhiteWallCheckpoint.CENTRE

    /** "Travel to Asgarnia": the north-gate approach, inside and outside the wall. */
    val APPROACH = Area(2948, 3380, 2984, 3414)

    val AMIK_TILE = Tile(2960, 3336, 2)
    val TIFFY_TILE = Tile(2997, 3373, 0)

    /** FRONT observation 1 — the White Knight position: the checkpoint itself. */
    val OBS_LINE = Area(2957, 3395, 2973, 3400)

    /** FRONT observation 2 — the supply line: the gate road inside the wall. */
    val OBS_SUPPLY = Area(2961, 3383, 2970, 3391)

    /** FRONT observation 3 — the Kinshra position, seen from the last safe ground beyond the fence. */
    val OBS_KINSHRA = Area(2956, 3406, 2974, 3414)
    val OBS_KINSHRA_TILE = Tile(2965, 3410, 0)

    private const val C_LINE = "obs_line"
    private const val C_SUPPLY = "obs_supply"
    private const val C_KINSHRA = "obs_kinshra"
    private val OBSERVATIONS = listOf(C_LINE, C_SUPPLY, C_KINSHRA)

    // --- journal (docs/quests/at-the-white-wall.md, "Quest journal") -------------------------

    private const val J_START = "Falador may possess the military capability needed to breach Fallen Varrock. Travel to Asgarnia."
    private const val J_CHECKPOINT = "Speak with the White Knights guarding the Falador approach."
    private const val J_ATTACK = "Help the White Knights repel the Kinshra attack."
    private const val J_AMIK = "Speak with Sir Amik Varze in Falador."
    private const val J_TIFFY = "Sir Amik says Sir Tiffy Cashien wants to speak with me. Find him in Falador."
    private const val J_FRONT = "Inspect the White Knight-Kinshra front and determine why Falador cannot commit troops east."
    private const val J_REPORT = "Report what I learned to Sir Amik."
    const val J_DONE = "Falador has an army, but no army to spare. The Kinshra keep the White Knights pinned while the Imperial Guard is tied down in the north. Sir Amik has directed me toward Burthorpe."

    override val prerequisites: List<Prerequisite> = listOf(Prerequisite.QuestComplete(KINGDOM_ALONE_KEY))

    /** Begins the moment A Kingdom Alone completes — login, rank-up, or the framework poll. */
    override val autoBegin = true

    override val questPoints: Int = 1

    override val nativeTabVarp: Int? = NATIVE_TAB_VARP
    override val nativeTabComplete: Int = NATIVE_TAB_COMPLETE

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            S_TRAVEL, Objective.ReachArea(J_START, APPROACH),
            anchor = GATE,
            nudge = "Falador's NORTH gate — the one facing the Kinshra. Any road or teleport into Falador will do; walk out to the north gate.",
        ),
        QuestStep(
            S_CHECKPOINT, Objective.TalkTo(J_CHECKPOINT, WHITE_KNIGHT),
            // The stock White Knight id is shared with Falador's castle knights: the arrow locks
            // onto a knight AT the checkpoint only (the client journal highlights the same four).
            anchor = GATE, anchorNpc = WHITE_KNIGHT,
            anchorNpcFilter = { n -> n.tile.isWithinRadius(GATE, TALK_RADIUS) },
            nudge = "The checkpoint is the four White Knights on posts across the road just OUTSIDE the north gate — speak to any of them. The knights inside the castle are not the ones you want.",
        ),
        QuestStep(
            S_DEFEND,
            Objective.KillNpcs(J_ATTACK, count = RAIDERS_TO_DEFEAT, filter = { _, npc -> WhiteWallCheckpoint.isRaider(npc) }),
            anchor = GATE,
            onEnter = { p -> WhiteWallCheckpoint.alarm(p) },
            onLeave = { p -> WhiteWallCheckpoint.onDefenderDone(p) },
            nudge = "Kinshra raiders are pushing on the checkpoint. Any raider you draw blood on counts, even if a White Knight finishes it; the raid keeps coming while you stand at the gate.",
        ),
        QuestStep(
            S_AMIK, Objective.TalkTo(J_AMIK, AMIK),
            anchor = AMIK_TILE, anchorNpc = AMIK,
            nudge = "Sir Amik Varze is on the top floor of the White Knights' Castle, in the middle of Falador.",
        ),
        QuestStep(
            S_TIFFY, Objective.TalkTo(J_TIFFY, TIFFY),
            anchor = TIFFY_TILE, anchorNpc = TIFFY,
            nudge = "Sir Tiffy Cashien sits on his bench in Falador Park, east of the castle.",
        ),
        QuestStep(
            S_FRONT, Objective.Predicate(J_FRONT) { p -> observe(p) },
            anchor = GATE,
            nudge = "Three places to look, in any order: the White Knight line at the north-gate checkpoint, the supply road just inside the gate, and the ground north of the fence beyond the checkpoint.",
        ),
        QuestStep(
            S_REPORT, Objective.TalkTo(J_REPORT, AMIK),
            anchor = AMIK_TILE, anchorNpc = AMIK,
        ),
    )

    override val completionRewards: List<Reward> = listOf(Reward.WarEffort(WAR_EFFORT))

    override val completionMessage: String =
        "<col=801700>$J_DONE</col> The Asgarnia campaign has begun. Next: <col=801700>A Matter of Trolls</col> — Burthorpe."

    // --- dialogue scripts on the shared White Knight id (proximity-gated, see init) -----------

    private val checkpointScript: TalkScript = { p -> checkpointHalt(p) }
    private val defendScript: TalkScript = { p -> knightDuringRaid(p) }
    private val afterFightScript: TalkScript = { p -> knightAfterFight(p) }

    init {
        // The White Knight id is shared by every castle knight in Falador: the quest claims the
        // conversation only at the checkpoint itself, so the castle knights keep their own lines.
        NpcTalk.register(WHITE_KNIGHT, NpcTalk.PRIORITY_QUEST) { p ->
            if (!WhiteWallCheckpoint.isNear(p, TALK_RADIUS)) {
                null
            } else {
                when (QuestEngine.stepId(p, this)) {
                    S_CHECKPOINT -> checkpointScript
                    S_DEFEND -> defendScript
                    S_AMIK -> afterFightScript
                    else -> null
                }
            }
        }
        talk(AMIK, S_AMIK) { p -> amikMeeting(p) }
        talk(AMIK, S_REPORT) { p -> amikReport(p) }
        talk(TIFFY, S_TIFFY) { p -> tiffyMeeting(p) }
    }

    private const val TALK_RADIUS = 12

    // --- status / credit ----------------------------------------------------------------------

    /** One-line status (`::whitewall`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>At the White Wall:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>At the White Wall — current objective:</col> ${QuestEngine.objectiveLine(p, this)}"
        else -> "<col=801700>At the White Wall:</col> not started — finish A Kingdom Alone and it begins on its own."
    }

    /**
     * Damage-share credit for the raid: a DEFEND-step player who drew blood on a raider that a
     * White Knight (or another player) finished. Mirrors `QuestEngine.onNpcKilled` exactly; the
     * engine's own hook already counts the top-damage killer, so the plugin never routes them here.
     */
    fun creditRaiderKill(p: Player) {
        val step = QuestEngine.step(p, this) ?: return
        if (step.id != S_DEFEND) return
        val goal = (step.objective as? Objective.KillNpcs)?.count ?: RAIDERS_TO_DEFEAT
        val n = QuestEngine.addCounter(p, this)
        if (n >= goal) {
            QuestEngine.advance(p, this)
        } else {
            p.message("<col=801700>$displayName:</col> $n/$goal.")
        }
    }

    // --- the FRONT: three observations, polled -----------------------------------------------

    /** True once all three observation areas have been stood in (each narrates once, on first entry). */
    private fun observe(p: Player): Boolean {
        if (p.tile.height != 0) return false
        look(
            p, OBS_LINE, C_LINE,
            "White Knights hold a fortified position facing the Kinshra approach.",
            "The soldiers appear prepared for another attack.",
        )
        look(
            p, OBS_SUPPLY, C_SUPPLY,
            "Food, armour and weapons are continually being moved toward the front.",
            "Maintaining the line consumes resources even when no major battle is taking place.",
        )
        look(
            p, OBS_KINSHRA, C_KINSHRA,
            "The Kinshra position does not appear prepared to assault Falador's walls directly.",
            "They only need enough strength here to prevent the White Knights from leaving.",
        )
        val seen = OBSERVATIONS.count { QuestEngine.counter(p, this, it) > 0 }
        if (seen < OBSERVATIONS.size) return false
        p.message("<col=801700>The Kinshra do not need to conquer Falador. By keeping the White Knights occupied, they prevent Asgarnia from helping reclaim Varrock.</col>")
        return true
    }

    private fun look(p: Player, area: Area, counter: String, vararg lines: String) {
        if (QuestEngine.counter(p, this, counter) > 0 || !area.contains(p.tile)) return
        QuestEngine.addCounter(p, this, counter)
        lines.forEach { p.message("<col=801700>$it</col>") }
        val left = OBSERVATIONS.count { QuestEngine.counter(p, this, it) == 0 }
        if (left > 0) p.message("($left more to inspect.)")
    }

    // --- dialogue helpers ---------------------------------------------------------------------

    private fun id(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1)

    private suspend fun QueueTask.knight(p: Player, text: String) = chatNpc(p, text, npc = id(WHITE_KNIGHT), title = KNIGHT_NAME)
    private suspend fun QueueTask.amik(p: Player, text: String) = chatNpc(p, text, npc = id(AMIK), title = AMIK_NAME)
    private suspend fun QueueTask.tiffy(p: Player, text: String) = chatNpc(p, text, npc = id(TIFFY), title = TIFFY_NAME)
    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    private const val KNIGHT_NAME = "White Knight"
    private const val AMIK_NAME = "Sir Amik Varze"
    private const val TIFFY_NAME = "Sir Tiffy Cashien"

    // --- the checkpoint -----------------------------------------------------------------------

    /** CHECKPOINT: "Halt." — and the raid. Advances to DEFEND before the alarm lines. */
    private suspend fun QueueTask.checkpointHalt(p: Player) {
        knight(p, "Halt.")
        me(p, "I'm here from Lumbridge.")
        knight(p, "Business?")
        when (options(p, "I need to speak with Sir Amik.", "I'm here about Varrock.", "Just visiting.", title = KNIGHT_NAME)) {
            1 -> {
                me(p, "I need to speak with Sir Amik.")
                knight(p, "So does half of Asgarnia. About what?")
                me(p, "Varrock.")
            }
            2 -> me(p, "I'm here about Varrock.")
            else -> {
                me(p, "Just visiting.")
                knight(p, "Nobody visits. Not any more. What's it about?")
                me(p, "...Varrock.")
            }
        }
        knight(p, "Varrock?")
        p.message("The White Knight looks you over.")
        knight(p, "You've come a long way to ask for soldiers we don't have.")
        me(p, "Falador looks like it has plenty.")
        knight(p, "Then you've been here thirty seconds.")
        QuestEngine.satisfy(p, this@AtTheWhiteWall, S_CHECKPOINT) // → DEFEND: the raid begins (mutate, then narrate)
        knight(p, "Movement!")
        knight(p, "Kinshra! Hold the gate!")
    }

    /** DEFEND: the knight has no time for talk. */
    private suspend fun QueueTask.knightDuringRaid(p: Player) {
        val n = QuestEngine.counter(p, this@AtTheWhiteWall)
        knight(p, "Kinshra! Don't let them through!")
        if (n > 0) knight(p, "That's $n of them down to you. Keep at it!")
    }

    /** AMIK: the raid is broken — "You should've led with the sword." Also queued when DEFEND clears. */
    suspend fun QueueTask.knightAfterFight(p: Player) {
        knight(p, "You said you wanted to speak with Sir Amik?")
        me(p, "Yes.")
        knight(p, "You should've led with the sword.")
        me(p, "I did eventually.")
        knight(p, "Go on. Castle.")
        knight(p, "He'll want to hear why someone from Lumbridge is fighting Kinshra outside his walls.")
    }

    // --- Sir Amik Varze -----------------------------------------------------------------------

    /** AMIK: the core of the quest — "I have an army. I do not have an army to spare." — the three pressures, and Tiffy. */
    private suspend fun QueueTask.amikMeeting(p: Player) {
        amik(p, "Lumbridge.")
        me(p, "That's me.")
        amik(p, "I meant the crest on the dispatch.")
        me(p, "Oh.")
        p.message("Sir Amik looks at you.")
        amik(p, "Though I've already heard about the gate.")
        amik(p, "Apparently you made yourself useful.")
        me(p, "I try.")
        me(p, "Lumbridge is preparing for Varrock.")
        p.message("Sir Amik's expression changes.")
        amik(p, "Preparing?")
        me(p, "We've established a forward position south of the city.")
        me(p, "The Southern Watch.")
        amik(p, "I've heard.")
        me(p, "We need an army capable of breaking through Varrock's defences.")
        me(p, "And General Zo thinks Falador has one.")
        p.message("Sir Amik is silent for a moment.")
        amik(p, "He's right.")
        me(p, "So you'll help?")
        amik(p, "No.")
        amik(p, "I have an army.")
        amik(p, "I do not have an army to spare.")
        me(p, "I just watched your Knights repel the Kinshra.")
        amik(p, "And tomorrow they'll attack somewhere else.")
        amik(p, "We reinforce that position. They probe another.")
        amik(p, "We move soldiers. They move theirs.")
        me(p, "So they're trying to capture Falador?")
        amik(p, "Eventually, perhaps.")
        amik(p, "But they don't need to.")
        amik(p, "Every White Knight standing on this front is a White Knight who cannot march east.")
        amik(p, "Every crate of food here does not reach another battlefield.")
        amik(p, "Every replacement weapon goes to a soldier defending Asgarnia.")
        me(p, "They're keeping you here.")
        amik(p, "Yes.")
        amik(p, "They don't need Falador.")
        amik(p, "They need Falador busy.")
        // The three pressures.
        amik(p, "Three problems keep Asgarnia out of Misthalin's war. You've already met the first.")
        amik(p, "The Kinshra.")
        amik(p, "As long as this front holds our attention, the White Knights stay here.")
        amik(p, "Burthorpe is worse.")
        me(p, "Trolls?")
        amik(p, "Trolls.")
        amik(p, "Our Imperial Guard keeps the northern approaches from collapsing.")
        amik(p, "If I pull them south...")
        amik(p, "...the trolls come down from the mountains.")
        amik(p, "And even if I had the soldiers...")
        amik(p, "...we've spent twelve years wearing out the weapons needed to support them.")
        me(p, "You can't replace them?")
        amik(p, "Not quickly enough.")
        amik(p, "Our dwarves still know how to build them.")
        amik(p, "What we no longer have is the industrial supply to replace them at wartime scale.")
        // Tiffy.
        amik(p, "Before I send you anywhere, there's someone else who wants a word.")
        me(p, "Who?")
        amik(p, "Tiffy.")
        me(p, "Should I know who that is?")
        p.message("Sir Amik pauses.")
        QuestEngine.satisfy(p, this@AtTheWhiteWall, S_AMIK) // → TIFFY (mutate, then narrate)
        amik(p, "You'll understand why I'm hesitating after you meet him.")
        amik(p, "Falador Park. The bench. He'll have found you before you find him.")
    }

    /** REPORT: what the player learned; the handoff to Burthorpe. Completes the quest. */
    private suspend fun QueueTask.amikReport(p: Player) {
        amik(p, "What did you learn?")
        me(p, "They're not trying to beat you.")
        amik(p, "No?")
        me(p, "They're making sure you can't leave.")
        p.message("Sir Amik nods.")
        QuestEngine.satisfy(p, this@AtTheWhiteWall, S_REPORT) // completes the quest — mutate, then narrate
        amik(p, "Exactly.")
        me(p, "So breaking the front means more than defending Falador.")
        amik(p, "It means Asgarnia becomes useful again.")
        me(p, "Then let's break it.")
        amik(p, "With what soldiers?")
        me(p, "The ones outside?")
        amik(p, "Those are the soldiers holding it.")
        me(p, "Right.")
        p.message("Sir Amik gestures north.")
        amik(p, "We begin somewhere else.")
        amik(p, "Burthorpe.")
        amik(p, "The Imperial Guard has spent years watching the mountain passes.")
        amik(p, "If the northern frontier stabilises...")
        amik(p, "...those soldiers can come south.")
        me(p, "And then we hit the Kinshra?")
        amik(p, "Then we'll have enough men to start thinking about it.")
    }

    // --- Sir Tiffy Cashien --------------------------------------------------------------------

    /** TIFFY: the Fallen Varrock question, the first Adventurer breadcrumb, "go look at the front". */
    private suspend fun QueueTask.tiffyMeeting(p: Player) {
        tiffy(p, "Ah.")
        me(p, "Sir Tiffy?")
        tiffy(p, "Usually.")
        me(p, "Sir Amik said you wanted to speak with me.")
        p.message("Sir Tiffy studies you. The pause is a little longer than it should be.")
        tiffy(p, "Yes.")
        tiffy(p, "Yes, I suppose I did.")
        tiffy(p, "You've been near Varrock?")
        me(p, "Closer than I'd like.")
        tiffy(p, "Quite.")
        tiffy(p, "Did you notice anything unusual?")
        when (options(p, "There are undead everywhere.", "There are strange ruins and old structures.", "Define unusual.", title = TIFFY_NAME)) {
            1 -> me(p, "There are undead everywhere.")
            2 -> me(p, "There are strange ruins and old structures.")
            else -> me(p, "Define unusual.")
        }
        tiffy(p, "Stone where stone ought not be.")
        tiffy(p, "Old markings.")
        tiffy(p, "Magic behaving strangely.")
        tiffy(p, "Anything that looked older than the city around it.")
        me(p, "Why?")
        tiffy(p, "Oh, no reason.")
        me(p, "That sounded exactly like there was a reason.")
        p.message("Sir Tiffy studies you again.")
        tiffy(p, "You remind me of someone.")
        me(p, "Sergeant Damien said something like that.")
        tiffy(p, "Did he?")
        p.message("Sir Tiffy says nothing for a moment.")
        me(p, "Who?")
        tiffy(p, "Someone who asked too many questions.")
        me(p, "That's not an answer.")
        tiffy(p, "Precisely.")
        tiffy(p, "Before you start solving Sir Amik's problems...")
        tiffy(p, "...I'd suggest you understand the first one.")
        me(p, "The Kinshra?")
        QuestEngine.satisfy(p, this@AtTheWhiteWall, S_TIFFY) // → FRONT (mutate, then narrate)
        tiffy(p, "Go look at the front.")
        tiffy(p, "Not the soldiers. The ground.")
        me(p, "What am I looking for?")
        tiffy(p, "Why nobody moves.")
    }

    // --- everyday lines (default-priority branches, registered by the plugin) -----------------

    /** A White Knight with no quest beat to run: the checkpoint's own lines, or the castle's. */
    suspend fun QueueTask.knightIdle(p: Player) {
        if (!WhiteWallCheckpoint.isNear(p, TALK_RADIUS)) {
            // A castle knight while the quest wants the checkpoint: send the player to the gate
            // rather than leave them wondering which White Knight the journal means.
            when (QuestEngine.stepId(p, this@AtTheWhiteWall)) {
                S_TRAVEL, S_CHECKPOINT -> {
                    knight(p, "Looking for the checkpoint? Not here, citizen. Out through the NORTH gate — the garrison holds the road just beyond it.")
                    knight(p, "Speak to any of the knights on the posts there. They will want to know your business.")
                    return
                }
                S_DEFEND -> {
                    knight(p, "The Kinshra are at the north gate! Get back to the checkpoint and help the garrison hold it!")
                    return
                }
            }
            knight(p, "Falador holds, citizen. The Kinshra keep us busy at the north gate and the trolls keep the Imperial Guard busy at Burthorpe.")
            knight(p, "It has been twelve years. We are still standing.")
            return
        }
        when {
            QuestEngine.isComplete(p, this@AtTheWhiteWall) -> {
                knight(p, "Sir Amik's sent you to Burthorpe? West along the wall, then north past the crossroads.")
                knight(p, "Mind the trolls. The Imperial Guard will be glad of you.")
            }
            QuestEngine.stepId(p, this@AtTheWhiteWall) == S_FRONT -> {
                knight(p, "Tiffy sent you to look at the ground? He does that.")
                knight(p, "The line's here. The road behind us carries what keeps it fed. The Kinshra sit beyond the fence and never come closer than they need to.")
            }
            QuestEngine.started(p, this@AtTheWhiteWall) -> {
                knight(p, "Sir Amik. Castle. Top floor. He knows about the gate already; news moves faster than Kinshra.")
            }
            else -> {
                knight(p, "Halt. The north road is closed while the Kinshra press us.")
                knight(p, "State your business, or move on.")
            }
        }
    }

    /** Sir Amik with no quest beat to run. */
    suspend fun QueueTask.amikIdle(p: Player) {
        when {
            QuestEngine.isComplete(p, this@AtTheWhiteWall) -> {
                amik(p, "Burthorpe. The Imperial Guard has held the passes for twelve years; make the north quiet and they can come south.")
                amik(p, "Go.")
            }
            QuestEngine.stepId(p, this@AtTheWhiteWall) == S_FRONT -> {
                amik(p, "Tiffy's sent you to look at the ground, has he? Then look.")
                amik(p, "Come back when you've understood why nobody moves.")
            }
            QuestEngine.stepId(p, this@AtTheWhiteWall) == S_TIFFY -> {
                amik(p, "Tiffy. Falador Park, the bench. You'll know him when he starts talking.")
            }
            QuestEngine.started(p, this@AtTheWhiteWall) -> {
                amik(p, "My knights at the north gate will want a word with anyone from Lumbridge before I do.")
            }
            else -> {
                amik(p, "The White Knights hold Asgarnia. If Lumbridge has business with Falador, bring it through the gate like everyone else.")
            }
        }
    }

    /** Sir Tiffy with no quest beat to run. */
    suspend fun QueueTask.tiffyIdle(p: Player) {
        tiffy(p, "Ah.")
        when {
            QuestEngine.isComplete(p, this@AtTheWhiteWall) -> {
                tiffy(p, "Stone where stone ought not be. Keep your eyes open on the road north, would you?")
                tiffy(p, "Tiffy business. Off you go.")
            }
            QuestEngine.stepId(p, this@AtTheWhiteWall) == S_FRONT -> {
                tiffy(p, "The ground. Not the soldiers.")
                tiffy(p, "The line at the gate, the road behind it, the field beyond the fence. Then Amik.")
            }
            else -> {
                tiffy(p, "Usually. Nothing to see here — off you go.")
            }
        }
    }
}
