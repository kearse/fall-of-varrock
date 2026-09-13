package org.alter.plugins.content.quests.north

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.messageBox
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.areas.lumbridge.npcs.GeneralZoPlugin
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.quests.framework.Reward
import org.alter.plugins.content.war.address
import org.alter.rscm.RSCM.getRSCM

/**
 * **The North** — Main Story Quest 3 (`docs/quests/the-north.md`): exploration, atmosphere and
 * history. General Zo sends the player to Edgeville to see what the Fall of Varrock did to the
 * rest of the kingdom; Oziach — in his own hut, where he has always stood — remembers the day it
 * fell; the Wilderness ditch north of town is where Rogue Knights give way to other adventurers;
 * and the last dispatch Varrock ever sent comes back to Lumbridge.
 *
 * A framework quest ([QuestDefinition]): dialogue + state + two area triggers + one light-custom
 * item. No new NPC, map, enemy, mechanic, teleport or instance — every beat sits on something
 * that already exists (the integration audit is in the spec). Nothing is required of the player
 * beyond walking, reading and talking: no Rogue Knight kill, no crossing the ditch.
 *
 * Gate: the main quest before it. That is **First March** (Main Story Quest 2) once it is built
 * and registered under `first_march`; until then **The Last Free City** (`recruit_trials`), so
 * the chain never dead-ends. Auto-begins the moment the gate opens.
 *
 * Journal varp [QuestJournal.NORTH_VARP] (generic framework packing); native quest-tab row = the
 * relabelled Ernest the Chicken row, varp [QuestJournal.NORTH_QUEST_VARP] (`QuestTablePatch.PLAN`).
 *
 * Wiring (Oziach's Talk-to bind + everyday lines, the dispatch's Read option, `::north`) is in
 * [TheNorthPlugin]. General Zo's Talk-to is routed through `NpcTalk` by `GeneralZoPlugin`; this
 * quest claims his conversation only on its own two Zo steps, so his march/muster menu stays
 * reachable throughout.
 */
object TheNorth : QuestDefinition(
    key = "the_north",
    displayName = "The North",
    chainIndex = QuestBook.THE_NORTH,
    journalVarp = QuestJournal.NORTH_VARP,
) {

    const val ZO = GeneralZoPlugin.ZO_NPC
    const val OZIACH = "npc.oziach"

    /**
     * The Weathered Varrock Dispatch — Oziach's quest item, LIGHT CUSTOM: the stock "Old note"
     * def (id 25829 — "an old note found in an old ruin"; it carries the Read + Drop pack verbs
     * the quest needs, and nothing else here uses it) with a server-side override
     * (`data/cfg/items/itemOverrides/quests/TheNorth.yml`: untradeable, so it is always kept on
     * death and can never be traded or sold) and a cache rename for the client (the `dispatch`
     * action of the "Item def cache edit" workflow / `ItemDefTool`). It stays with the player
     * after the quest and can be re-read from the pack at any time — the lore journal. If it is
     * ever lost anyway, Oziach re-issues it (see [ensureDispatch]) — the "don't bother coming
     * back" line is a joke, not a mechanic.
     */
    const val DISPATCH = "item.old_note_25829"

    private const val FIRST_MARCH_KEY = "first_march"
    private const val LAST_FREE_CITY_KEY = "recruit_trials"

    private const val WAR_EFFORT = 15

    /** Quest counter: Oziach's after-reading beat (the reaction + lore + "take it to Zo") has run. */
    private const val REACTION = "reaction"

    // --- world anchors (all existing, unchanged) ----------------------------------------------

    /** Oziach's stock spawn (`npc_spawns.json`: 822 @ 3069,3517) — his hut at Edgeville's north-west edge. */
    val OZIACH_TILE = Tile(3069, 3517, 0)

    /** Edgeville town — the same box as PvpZones' safe carve-out (the whole town, up to the ditch). */
    val EDGEVILLE = Area(3067, 3488, 3098, 3522)

    /** Where the amulet of glory lands (`AmuletOfGloryPlugin`) — the journal's "travel" anchor. */
    val EDGEVILLE_ARRIVAL = Tile(3087, 3496, 0)

    /**
     * The inspection trigger: the strip of road immediately SOUTH of the Wilderness ditch at
     * Edgeville (the ditch sits at z 3521-3522; jumping it lands you on 3523). It starts east of
     * Oziach's hut (x ≤ 3071) so talking to him can never trip it, and it never asks the player
     * to cross. TUNE in-game if the ditch reads differently.
     */
    val BOUNDARY = Area(3074, 3516, 3104, 3520)
    val BOUNDARY_TILE = Tile(3088, 3519, 0)

    // --- journal (docs/quests/the-north.md, "Quest Journal") ----------------------------------

    private const val J_START = "General Zo wants me to understand what Varrock's fall did to northern Misthalin."
    private const val J_EDGEVILLE = "Travel to Edgeville."
    private const val J_CONTACT = "Find someone in Edgeville who remembers the Fall."
    private const val J_WILDERNESS = "Inspect the Wilderness boundary north of Edgeville."
    private const val J_RETURN_OZIACH = "Return to Oziach and tell him what I saw."
    private const val J_DISPATCH = "Read the Weathered Varrock Dispatch."
    private const val J_RETURN_ZO = "Take Oziach's dispatch to General Zo."
    const val J_DONE = "Varrock's fall broke more than a city. Misthalin lost roads, patrols and control of the north. General Zo intends to start taking that ground back."

    override val prerequisites: List<Prerequisite> = listOf(
        Prerequisite.Custom("finish the main quest before it (First March once it is built; The Last Free City until then)") { p -> previousQuestDone(p) },
    )

    /** Begins the moment the gate opens — login, rank-up, or the framework poll. */
    override val autoBegin = true

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            "brief", Objective.TalkTo(J_START, ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
            nudge = "General Zo has sent for me. There is more to this war than the roads around Lumbridge - I should speak with him about the north.",
        ),
        QuestStep(
            "edgeville", Objective.ReachArea(J_EDGEVILLE, EDGEVILLE),
            anchor = EDGEVILLE_ARRIVAL,
            onLeave = { p -> arrival(p) },
            nudge = "Any road or teleport will do — an amulet of glory lands in Edgeville. Rogue Knights may cross your path on the way; you need not fight them.",
        ),
        QuestStep(
            "contact", Objective.TalkTo(J_CONTACT, OZIACH),
            anchor = OZIACH_TILE, anchorNpc = OZIACH,
        ),
        QuestStep(
            "wilderness", Objective.ReachArea(J_WILDERNESS, BOUNDARY),
            anchor = BOUNDARY_TILE,
            onLeave = { p -> boundary(p) },
            nudge = "Walk to the ditch at the top of town. You do not have to cross it.",
        ),
        QuestStep(
            "return_oziach", Objective.TalkTo(J_RETURN_OZIACH, OZIACH),
            anchor = OZIACH_TILE, anchorNpc = OZIACH,
        ),
        QuestStep(
            "dispatch", Objective.Manual(J_DISPATCH),
            anchor = OZIACH_TILE, anchorNpc = OZIACH,
            nudge = "Read it from your pack, or let Oziach see you read it.",
        ),
        QuestStep(
            "return_zo", Objective.TalkTo(J_RETURN_ZO, ZO),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = ZO,
        ),
    )

    override val completionRewards: List<Reward> = listOf(Reward.WarEffort(WAR_EFFORT))

    /** 1 Quest Point (the design's reward) — counted by the registry-derived summary-tab total. */
    override val questPoints: Int = 1

    override val completionMessage: String =
        "<col=801700>$J_DONE</col> The dispatch is yours to keep — read it from your pack any time. Next: <col=801700>First Reclamation</col>."

    /** Native quest tab: the relabelled Ernest the Chicken row (`QuestTablePatch.PLAN`, sort "8 The North"). */
    override val nativeTabVarp: Int? = QuestJournal.NORTH_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.NORTH_QUEST_COMPLETE

    init {
        talk(ZO, "brief") { p -> zoBrief(p) }
        talk(ZO, "return_zo") { p -> zoDebrief(p) }
        talk(OZIACH, "contact") { p -> oziachContact(p) }
        talk(OZIACH, "wilderness") { p -> oziachGoNorth(p) }
        talk(OZIACH, "return_oziach") { p -> oziachReturn(p) }
        talk(OZIACH, "dispatch") { p -> oziachReadIt(p) }
        talk(OZIACH, "return_zo") { p -> oziachAfter(p) }
    }

    // --- gate / status ----------------------------------------------------------------------

    /** First March once it exists in the registry, else The Last Free City. */
    private fun previousQuestDone(p: Player): Boolean {
        val march = QuestRegistry.byKey(FIRST_MARCH_KEY)
        return if (march != null) march.complete(p) else QuestRegistry.isComplete(p, LAST_FREE_CITY_KEY)
    }

    /** One-line status (`::north`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>The North:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>The North — current objective:</col> ${QuestEngine.objectiveLine(p, this)}"
        else -> "<col=801700>The North:</col> not started — finish the main quest before it (The Last Free City; First March once it exists) and it begins on its own."
    }

    // --- the dispatch -----------------------------------------------------------------------

    private fun dispatchId(): Int = runCatching { getRSCM(DISPATCH) }.getOrDefault(-1)

    fun hasDispatch(p: Player): Boolean = dispatchId().let { it >= 0 && p.inventory.getItemCount(it) > 0 }

    private fun dispatchBanked(p: Player): Boolean = dispatchId().let { it >= 0 && p.bank.getItemCount(it) > 0 }

    private fun giveDispatch(p: Player) = Reward.giveItem(p, DISPATCH, 1)

    // --- area beats (chat narration — no cutscene, no window; the real place does the work) ----

    private fun arrival(p: Player) {
        p.message("<col=801700>Edgeville. Northern Misthalin.</col>")
        p.message("The Wilderness lies just beyond the town. Varrock lies to the east.")
    }

    private fun boundary(p: Player) {
        p.message("<col=801700>The road continues north into the Wilderness.</col>")
        p.message("Rogue Knights may attack travellers throughout the realm.")
        p.message("<col=cc2222>Beyond this line, other adventurers can attack you too.</col>")
        p.message("Everything learned fighting the Rogue Knights matters more on the other side.")
    }

    // --- dialogue helpers ---------------------------------------------------------------------

    private suspend fun QueueTask.zo(p: Player, text: String) =
        chatNpc(p, text, npc = runCatching { getRSCM(ZO) }.getOrDefault(-1), title = GeneralZoPlugin.ZO)

    private suspend fun QueueTask.oz(p: Player, text: String) =
        chatNpc(p, text, npc = runCatching { getRSCM(OZIACH) }.getOrDefault(-1), title = OZIACH_NAME)

    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    private const val OZIACH_NAME = "Oziach"

    // --- General Zo -------------------------------------------------------------------------

    /**
     * BRIEF: "Perspective." Zo sends the player north. Zo raises Edgeville himself — the quest
     * auto-begins, so nobody has mentioned the town to the player before this conversation and
     * there is nothing for the player to refer back to.
     */
    private suspend fun QueueTask.zoBrief(p: Player) {
        zo(p, "Good. I've an errand for you, ${p.address}.")
        me(p, "Another march?")
        zo(p, "No sword needed for this one. Go to Edgeville.")
        me(p, "What's there?")
        zo(p, "Perspective.")
        me(p, "That sounds ominous.")
        zo(p, "It usually is.")
        zo(p, "You've seen Lumbridge attacked. You've marched<br>with our Knights. You've even watched us win<br>a field.")
        zo(p, "If that's all you saw, you might start thinking<br>we're winning.")
        me(p, "We aren't?")
        zo(p, "We're surviving. There's a difference.")
        zo(p, "Go to Edgeville. Look at what remains between<br>us and Varrock.")
        zo(p, "Then come back and tell me what you think<br>we're actually fighting for.")
        while (true) {
            when (options(p, "That's the whole assignment?", "Why Edgeville?", "I'll go.", title = GeneralZoPlugin.ZO)) {
                1 -> {
                    me(p, "That's the whole assignment?")
                    zo(p, "Go. Look. Come back.<br>Not every lesson needs a sword.")
                }
                2 -> {
                    me(p, "Why Edgeville?")
                    zo(p, "Because it survived.")
                    me(p, "So did Lumbridge.")
                    zo(p, "Lumbridge still has a kingdom behind it.<br>Edgeville has the Wilderness behind it.")
                    zo(p, "And Fallen Varrock in front of it.")
                }
                else -> {
                    QuestEngine.satisfy(p, this@TheNorth, "brief") // mutate, then narrate
                    me(p, "I'll go.")
                    return
                }
            }
        }
    }

    /** RETURN_ZO: the debrief — what the player learned, the rogue problem, what comes next. Completes the quest. */
    private suspend fun QueueTask.zoDebrief(p: Player) {
        if (!hasDispatch(p)) {
            zo(p, "Oziach's dispatch — you don't have it on you.")
            zo(p, if (dispatchBanked(p)) "Fetch it from your bank. I want to read<br>the original." else "Go back to Edgeville and get it from him.<br>I want to read the original.")
            return
        }
        zo(p, "Oziach still had this?")
        me(p, "He wants it back.")
        zo(p, "Of course he does.")
        p.message("General Zo reads the dispatch.")
        zo(p, "'Varrock will hold.'")
        me(p, "It was the last message.")
        zo(p, "For Edgeville, yes.")
        p.message("General Zo hands the dispatch back to you.")
        zo(p, "So. What did you see?")
        when (options(p, "The north is lawless.", "Edgeville is still holding.", "The Wilderness is the least of our problems.", title = GeneralZoPlugin.ZO)) {
            1 -> me(p, "The north is lawless.")
            2 -> me(p, "Edgeville is still holding.")
            else -> me(p, "The Wilderness is the least of our problems.")
        }
        me(p, "Varrock didn't just lose a battle. When it fell,<br>everything around it started falling apart too.")
        zo(p, "Exactly.")
        zo(p, "Armies are obvious. Collapsed roads aren't.")
        zo(p, "Neither are farms that stop producing. Or patrols<br>that never come home. Or a hundred little warlords<br>deciding nobody can stop them.")
        me(p, "So the Rogue Knights are part of the war too?")
        zo(p, "Not every enemy wears Zemouregal's colours.<br>That doesn't make them harmless.")
        zo(p, "The longer this kingdom stays broken, the more<br>men discover they prefer it that way.")
        me(p, "So what do we do?")
        zo(p, "What we've been doing. One piece at a time.")
        me(p, "Another March?")
        zo(p, "No. A March wins a battlefield.")
        zo(p, "What I want next is something we can actually use.")
        me(p, "What?")
        QuestEngine.satisfy(p, this@TheNorth, "return_zo") // completes the quest — mutate, then narrate
        zo(p, "A position.")
        zo(p, "Something between Lumbridge and the north that<br>belongs to us when the fighting stops.")
        zo(p, "It's time you helped take one back.")
    }

    // --- Oziach -----------------------------------------------------------------------------

    /**
     * CONTACT: the first conversation — the Fall, the north, the Rogue Knights. Sends the player
     * to the ditch. Oziach is the first person in the game to say Zemouregal's name: all Lumbridge
     * has told the player so far is "twelve years ago, Varrock fell" (The Last Free City's debrief),
     * so the name comes from him and the player reacts to it — never the other way round.
     */
    private suspend fun QueueTask.oziachContact(p: Player) {
        oz(p, "What?")
        me(p, "General Zo sent me.")
        oz(p, "Then General Zo can come himself.")
        me(p, "He told me to see what happened to the north.")
        oz(p, "Did he? Then he wants you frightened.")
        me(p, "I don't think he said that.")
        oz(p, "Generals rarely do.")
        me(p, "Were you here when Varrock fell?")
        oz(p, "Aye.")
        oz(p, "I was here before it fell.<br>I was here while it fell.")
        oz(p, "And I've been here every miserable year since.")
        me(p, "What happened to the north?")
        oz(p, "Varrock was the capital. Every road, every patrol,<br>every coin in Misthalin ran through it.")
        oz(p, "Then Varrock fell. And everything that leaned<br>on it came down after.")
        oz(p, "The patrols stopped. The roads emptied. Merchants<br>changed routes. Farms were abandoned.")
        oz(p, "Every thug with a sword suddenly decided he was<br>a warlord.")
        me(p, "The Rogue Knights?")
        oz(p, "Some of them. Deserters. Mercenaries. Bandits.<br>Opportunists.")
        oz(p, "Call them whatever makes dying to one feel better.")
        me(p, "They're all over the roads.")
        oz(p, "Exactly.")
        me(p, "Are they the ones who took Varrock?")
        oz(p, "Zemouregal's dead? No. Most of them have nothing<br>to do with him. That would almost be simpler.")
        me(p, "Zemouregal?")
        oz(p, "The one whose dead walked into Varrock. Nobody in<br>Lumbridge gave you the name?")
        me(p, "They told me Varrock fell.")
        oz(p, "Aye. That's the short version.")
        me(p, "Then why are the Rogue Knights attacking everyone?")
        oz(p, "Because nobody stops them.")
        oz(p, "Varrock kept order through most of Misthalin.<br>Nobody has kept it since.")
        oz(p, "The people who prefer a world without rules<br>noticed.")
        QuestEngine.satisfy(p, this@TheNorth, "contact") // mutate, then narrate
        oz(p, "Come north.")
        oz(p, "There's something else Zo expects you<br>to understand.")
    }

    /** WILDERNESS: back too soon — point at the ditch again. */
    private suspend fun QueueTask.oziachGoNorth(p: Player) {
        oz(p, "North. The ditch at the top of town — go and look<br>at it. You needn't jump it.")
        oz(p, "Then come back and tell me what you saw.")
    }

    /** RETURN_OZIACH: the Wilderness, the Fall, Edgeville — then the last dispatch. */
    private suspend fun QueueTask.oziachReturn(p: Player) {
        me(p, "So that's the Wilderness.")
        oz(p, "That's the polite name.")
        me(p, "And the Rogue Knights stay south of it too.")
        oz(p, "Of course. Lines on maps only matter to people<br>who respect them.")
        me(p, "But north of that line, players can attack me.")
        oz(p, "Aye. Rogue Knight comes at you, you know what<br>he wants.")
        me(p, "And another adventurer?")
        oz(p, "Your guess is as good as mine.")
        oz(p, "Usually your armour.")
        me(p, "What does all this have to do with Varrock?")
        oz(p, "Everything.")
        oz(p, "Before Varrock fell, these roads belonged to<br>a kingdom.")
        oz(p, "Afterward? They belonged to whoever happened<br>to be standing on them.")
        me(p, "And Edgeville?")
        oz(p, "Edgeville stayed. Barely.")
        oz(p, "Refugees came through here for weeks.<br>Soldiers too. Some still had weapons.<br>Some didn't.")
        me(p, "Did anyone know Varrock was going to fall?")
        oz(p, "They knew they were in trouble.")
        // Hand over the dispatch and advance to DISPATCH back-to-back, with no chat line between:
        // every line is a point where the player can close the dialogue, and an item given before
        // the advance would be re-claimable by talking again.
        giveDispatch(p)
        QuestEngine.satisfy(p, this@TheNorth, "return_oziach")
        p.message("<col=801700>Oziach digs an old, weathered document out from under his counter and puts it in your hands.</col>")
        oz(p, "Last official message I ever got from Varrock.")
        oz(p, "Twelve years old. Still waiting for the next one.")
        readDispatch(p)
        QuestEngine.satisfy(p, this@TheNorth, "dispatch")
        afterReading(p)
    }

    /** DISPATCH: the chat was closed after the hand-over — read it with him now. */
    private suspend fun QueueTask.oziachReadIt(p: Player) {
        oz(p, "You've not read it yet? Read it.<br>Then we'll talk.")
        readDispatch(p)
        QuestEngine.satisfy(p, this@TheNorth, "dispatch")
        afterReading(p)
    }

    /** RETURN_ZO: the player came back — or read it from the pack away from him and never heard his say. */
    private suspend fun QueueTask.oziachAfter(p: Player) {
        if (QuestEngine.counter(p, this@TheNorth, REACTION) == 0) {
            afterReading(p)
            return
        }
        if (!ensureDispatch(p)) return
        oz(p, "It's Zo you want, not me. Take it to him.")
        lore(p, leave = "I'm going.")
        oz(p, "Go on, then.")
    }

    /** The dispatch, page by page. Also what the pack's Read option shows, forever. */
    private suspend fun QueueTask.readDispatch(p: Player) {
        messageBox(p, "<col=5a3a1e>To the northern watch:</col><br><br>Hold the roads. Do not send reinforcements south.")
        messageBox(p, "Zemouregal's dead have breached the outer approaches.<br>Arrav has been sighted among them.")
        messageBox(p, "<col=5a3a1e>Varrock will hold.</col>")
        p.message("<col=801700>No further orders arrived.</col>")
    }

    /** The reaction, the optional lore, and "take that back to Zo". Runs once (the REACTION counter). */
    private suspend fun QueueTask.afterReading(p: Player) {
        QuestEngine.addCounter(p, this@TheNorth, REACTION)
        me(p, "'Varrock will hold.'")
        oz(p, "Aye.")
        me(p, "It didn't.")
        oz(p, "No.")
        me(p, "What happened after this?")
        oz(p, "Nothing.")
        me(p, "Nothing?")
        oz(p, "No runners. No orders. No army. Just refugees.")
        oz(p, "At first there were thousands. Then hundreds.<br>Then dozens. Then nobody.")
        lore(p, leave = "I'll take this to Zo.")
        oz(p, "Take that back to Zo.")
        oz(p, "If he sent you here to understand the north,<br>give him the original.")
        me(p, "You kept this for twelve years and you're just<br>giving it to me?")
        oz(p, "I expect it back.")
        me(p, "Oh.")
        oz(p, "And if you lose it to some Rogue Knight on<br>the road, don't bother coming back.")
    }

    /** The optional lore branches (accepted history only — Stage 1 of the revelation ladder). */
    private suspend fun QueueTask.lore(p: Player, leave: String) {
        while (true) {
            when (options(p, "Who is Zemouregal?", "Who was Arrav?", "Why did you stay?", leave, title = OZIACH_NAME)) {
                1 -> {
                    me(p, "Who is Zemouregal?")
                    oz(p, "A Mahjarrat necromancer. Old. Powerful.<br>Fond of corpses.")
                    me(p, "Charming.")
                    oz(p, "You should meet him.")
                    me(p, "I'd rather not.")
                    oz(p, "Good instinct.")
                }
                2 -> {
                    me(p, "Who was Arrav?")
                    oz(p, "Once? A hero. Varrock's greatest, depending<br>which drunk you ask.")
                    me(p, "And now?")
                    oz(p, "The man people saw marching with<br>Zemouregal's dead.")
                    me(p, "Is he still alive?")
                    oz(p, "People have been arguing about that for<br>twelve years.")
                }
                3 -> {
                    me(p, "Why did you stay?")
                    oz(p, "Cheap property.")
                    me(p, "Seriously?")
                    oz(p, "No. Mind your business.")
                }
                else -> {
                    me(p, leave)
                    return
                }
            }
        }
    }

    /**
     * The dispatch must be in the pack to go to Zo. Banked: sends the player to fetch it. Gone:
     * re-issued with a grumble — the quest can't dead-end on a lost note.
     */
    private suspend fun QueueTask.ensureDispatch(p: Player): Boolean {
        if (hasDispatch(p)) return true
        if (dispatchBanked(p)) {
            oz(p, "You put my dispatch in a BANK? Twelve years it sat<br>under this counter. Go and fetch it.")
            return false
        }
        oz(p, "Lost it already? I said don't bother coming back.")
        me(p, "It's important.")
        oz(p, "...Aye. It is.")
        giveDispatch(p)
        oz(p, "The copy I made the day it came.<br>Lose this one and we're done.")
        return true
    }

    // --- shared with the plugin -------------------------------------------------------------

    /** Oziach's everyday lines — no quest branch claimed the click. */
    suspend fun QueueTask.oziachIdle(p: Player) {
        oz(p, "What?")
        if (QuestEngine.isComplete(p, this@TheNorth)) {
            if (hasDispatch(p)) {
                me(p, "I've still got your dispatch.")
                oz(p, "Zo said to keep it, did he?")
                me(p, "He did.")
                oz(p, "Of course he did. Then read it now and again.<br>Somebody should.")
                lore(p, leave = "I'll leave you to it.")
            } else {
                me(p, "Just passing through.")
                oz(p, "Then pass. I'm still waiting for the next one.")
            }
            return
        }
        me(p, "Nothing. Sorry.")
        oz(p, "Mind your business.")
    }

    /** The pack's Read option: the dispatch, any time; on the DISPATCH step it also clears the objective. */
    suspend fun QueueTask.readFromPack(p: Player) {
        readDispatch(p)
        if (QuestEngine.satisfy(p, this@TheNorth, "dispatch")) {
            p.message("Oziach will have something to say about it, if you go back — or take it straight to General Zo.")
        }
    }
}
