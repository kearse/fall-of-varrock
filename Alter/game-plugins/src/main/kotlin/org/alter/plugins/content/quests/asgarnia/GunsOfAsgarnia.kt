package org.alter.plugins.content.quests.asgarnia

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.minigames.blastfurnace.BlastFurnace
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.quests.framework.Reward
import org.alter.rscm.RSCM.getRSCM

/**
 * **The Guns of Asgarnia** — Asgarnia campaign (BREACH) quest 3, the *Artillery* thread
 * (`docs/quests/the-guns-of-asgarnia.md`). Falador's dwarf multicannons have been worn out,
 * destroyed and cannibalised over twelve years of war; Nulodion can still build them, but a battery
 * eats steel by the wagon and Keldagrim stopped promising metal to wars it could not control. The
 * player secures the surface end of the Keldagrim–Falador route, wins a *trial order*, smelts it on
 * the real Blast Furnace, and carries the first steel back to Nulodion — who finishes the first new
 * multicannon in years just as a Kinshra sabotage party arrives to test it.
 *
 * A framework quest: dialogue + state over what exists. Sir Amik, Nulodion and the Blast Furnace
 * Foreman stand where they always have; the Blast Furnace is the normal reusable system (restored
 * to `main` by this quest — `content/minigames/blastfurnace/`); the cannon is the reusable
 * war-artillery emplacement (`content/war/artillery/`), never a quest-only fake. The two fights
 * are a temporary personal spawn on the road ([KinshraRoute]) and a private copy of Nulodion's
 * yard ([WorkshopDefence]). No new NPC, map, item, ore, metal or industrial system.
 *
 * Gate: *At the White Wall* (Asgarnia quest 1) once a quest is registered under `at_the_white_wall`;
 * until then the deepest registered main-story quest (`a_kingdom_alone` → `first_reclamation` →
 * `the_north` → `recruit_trials`), so the chain never dead-ends on merge order. It does NOT require
 * *A Matter of Trolls* — the two solve different problems and may be played in either order; Sir
 * Amik's debrief recognises whichever is done.
 *
 * Journal varp [QuestJournal.GUNS_OF_ASGARNIA_VARP] (generic packing); native quest-tab row = the
 * relabelled **Dwarf Cannon** row (varp [QuestJournal.GUNS_OF_ASGARNIA_QUEST_VARP], complete 11).
 */
object GunsOfAsgarnia : QuestDefinition(
    key = "guns_of_asgarnia",
    displayName = "The Guns of Asgarnia",
    chainIndex = QuestBook.GUNS_OF_ASGARNIA,
    journalVarp = QuestJournal.GUNS_OF_ASGARNIA_VARP,
) {

    const val KEY = "guns_of_asgarnia"
    const val NAME = "The Guns of Asgarnia"

    // --- NPCs (all stock, all in their own places) ---------------------------------------------

    /** Sir Amik Varze — Falador castle, top floor of the west tower (world spawn 4771 @ 2960,3336,2). */
    const val AMIK = "npc.sir_amik_varze_4771"
    const val AMIK_NAME = "Sir Amik Varze"
    val AMIK_TILE = Tile(2960, 3336, 2)

    /** Nulodion — his hut at the Dwarven Mine's Ice Mountain entrance (world spawn 1400 @ 3011,3453). */
    const val NULODION = "npc.nulodion"
    const val NULODION_NAME = "Nulodion"
    val NULODION_TILE = Tile(3011, 3453, 0)

    /** The Blast Furnace Foreman — posted by the furnace plugin at 1942,4958 (his stock spot). */
    const val FOREMAN = BlastFurnace.FOREMAN_KEY
    const val FOREMAN_NAME = BlastFurnace.FOREMAN_NAME
    val FOREMAN_TILE: Tile = BlastFurnace.FOREMAN_TILE

    /** The cross-quest recognitions in Sir Amik's debrief (A Matter of Trolls, Asgarnia quest 2). */
    const val TROLLS_KEY = "a_matter_of_trolls"
    const val NORTHERN_FRONT_FLAG = "asgarnia.northern_front_secured"

    /** The design's `ASGARNIA_ARTILLERY_RESTORED` — set on completion, read by the campaign. */
    const val ARTILLERY_FLAG = "asgarnia.artillery_restored"

    private const val WAR_EFFORT = 50

    /** The trial order: what the Foreman hands over, and what the furnace must produce. */
    const val STEEL_GOAL = 10
    const val IRON_ORE = "item.iron_ore"
    const val COAL = "item.coal"
    const val BUCKET_OF_WATER = "item.bucket_of_water"
    const val STEEL_BAR = "item.steel_bar"

    /** Prerequisite fallback: the first of these keys that is REGISTERED gates the quest. */
    private val GATE_KEYS = listOf("at_the_white_wall", "a_kingdom_alone", "first_reclamation", "the_north", "recruit_trials")

    // --- world anchors (all existing, unchanged; region dumps r11829/r12085) ------------------

    /**
     * The Kinshra raiding cell: the clearing in the trees immediately WEST of Nulodion's yard
     * (x 2998-3006, z 3448-3455 — the pocket the surface route crosses between the cliffs and the
     * mine). Tiles verified walkable on the r11829 collision dump; the two 3x3 evergreens at
     * 2996,3448 / 2998,3446 / 3001,3452 are avoided.
     */
    val ROUTE_ANCHOR = Tile(3002, 3451, 0)
    val ROUTE_RAIDER_TILES = listOf(Tile(2999, 3450, 0), Tile(2999, 3453, 0), Tile(3000, 3455, 0), Tile(3005, 3450, 0), Tile(3005, 3453, 0))
    val ROUTE_CAPTAIN_TILE = Tile(3004, 3448, 0)
    const val ROUTE_CELL_SIZE = 6

    /** Nulodion's yard — the private copy the sabotage runs in (chunk-aligned, 4x4 chunks, plane 0). */
    val WORKSHOP_AREA = Area(3000, 3440, 3031, 3471)
    /** Where the fight puts you if you were not already in the yard, and where it leaves you after. */
    val WORKSHOP_LANDING = Tile(3014, 3446, 0)
    /** The new gun's stand: SW corner of the 3x3 (footprint 3015-3017 × 3444-3446, all clear). */
    val WORKSHOP_CANNON_TILE = Tile(3015, 3444, 0)
    /** Nulodion at his workbench in the yard; two Dwarven Mine guards beside him. */
    val WORKSHOP_NULODION_TILE = Tile(3013, 3445, 0)
    val WORKSHOP_GUARD_TILES = listOf(Tile(3011, 3445, 0), Tile(3019, 3445, 0))
    /** Wave one comes in from the yard's open west edge; wave two through the fence gaps to the north. */
    val WORKSHOP_WAVE1_TILES = listOf(Tile(3008, 3445, 0), Tile(3008, 3446, 0), Tile(3009, 3446, 0))
    val WORKSHOP_WAVE2_TILES = listOf(Tile(3012, 3449, 0), Tile(3015, 3449, 0), Tile(3018, 3449, 0))
    const val WORKSHOP_KNIGHTS = 6
    const val WORKSHOP_CANNONBALLS = 30

    // --- journal (the design's state table) -------------------------------------------------

    private const val J_START = "Falador's artillery has been depleted by twelve years of war. Speak with Sir Amik."
    private const val J_NULODION = "Ask Nulodion why Asgarnia cannot replace its dwarf multicannons."
    private const val J_KELDAGRIM = "Travel to Keldagrim and discuss restoring military steel shipments."
    private const val J_ROUTE = "Clear the Kinshra raiding cell disrupting the southern steel route."
    private const val J_AGREEMENT = "Return to the Blast Furnace Foreman."
    private const val J_FURNACE = "Produce 10 steel bars at the Blast Furnace for the first trial order."
    private const val J_SHIPMENT = "Take the first steel shipment to Nulodion."
    private const val J_CANNON = "Help Nulodion complete the first replacement multicannon."
    private const val J_SABOTAGE = "Defend the workshop and field-test the cannon against the Kinshra."
    private const val J_PAYOFF = "Speak with Nulodion about the first cannon."
    private const val J_REPORT = "Report the restored artillery supply to Sir Amik."
    const val J_DONE = "Keldagrim has resumed limited military steel shipments and Asgarnia can produce replacement multicannons again."

    // step ids
    const val START = "start"
    const val NULODION_STEP = "nulodion"
    const val KELDAGRIM = "keldagrim"
    const val ROUTE = "route"
    const val AGREEMENT = "agreement"
    const val FURNACE = "furnace"
    const val SHIPMENT = "shipment"
    const val CANNON = "cannon"
    const val SABOTAGE = "sabotage"
    const val PAYOFF = "payoff"
    const val REPORT = "report"

    override val prerequisites: List<Prerequisite> = listOf(
        Prerequisite.Custom("finish At the White Wall (until it is built: the main quest before it)") { p -> gateDone(p) },
    )

    /** Begins the moment the gate opens — the ARTILLERY lead appears in the journal by itself. */
    override val autoBegin = true

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            START, Objective.TalkTo(J_START, AMIK),
            anchor = AMIK_TILE, anchorNpc = AMIK,
            nudge = "Sir Amik Varze holds Falador castle - the top floor of the west tower (a Falador Teleport lands you in the courtyard).",
        ),
        QuestStep(
            NULODION_STEP, Objective.TalkTo(J_NULODION, NULODION),
            anchor = NULODION_TILE, anchorNpc = NULODION,
            nudge = "Nulodion's workshop is the hut at the Dwarven Mine's Ice Mountain entrance, north of Falador.",
        ),
        QuestStep(
            KELDAGRIM, Objective.TalkTo(J_KELDAGRIM, FOREMAN),
            anchor = FOREMAN_TILE, anchorNpc = FOREMAN,
            nudge = "The teleport portal's Mini-Games tab lists the Blast Furnace - Keldagrim's furnace room. The Foreman runs it.",
        ),
        QuestStep(
            ROUTE, Objective.Predicate(J_ROUTE) { p -> QuestEngine.counter(p, this) >= ROUTE_CELL_SIZE },
            anchor = ROUTE_ANCHOR,
            onEnter = { p -> KinshraRoute.ensure(p) },
            onLeave = { p -> KinshraRoute.despawn(p) },
            nudge = "The Kinshra cell is camped in the trees just west of Nulodion's yard, where the surface route reaches the mine.",
        ),
        QuestStep(
            AGREEMENT, Objective.TalkTo(J_AGREEMENT, FOREMAN),
            anchor = FOREMAN_TILE, anchorNpc = FOREMAN,
            nudge = "Back to the Blast Furnace Foreman (portal, Mini-Games, Blast Furnace).",
        ),
        QuestStep(
            FURNACE, Objective.Predicate(J_FURNACE) { p -> QuestEngine.counter(p, this) >= STEEL_GOAL },
            anchor = BlastFurnace.BELT_TILE,
            nudge = "Put the Foreman's iron ore and coal on the conveyor belt; take the bars from the dispenser with the bucket of water or ice gloves. Steel needs 30 Smithing. Keldagrim pays the coffer for this order.",
        ),
        QuestStep(
            SHIPMENT, Objective.TalkTo(J_SHIPMENT, NULODION),
            anchor = NULODION_TILE, anchorNpc = NULODION,
            nudge = "Carry all ten steel bars to Nulodion - in your pack, not the bank.",
        ),
        QuestStep(
            CANNON, Objective.TalkTo(J_CANNON, NULODION),
            anchor = NULODION_TILE, anchorNpc = NULODION,
            nudge = "Nulodion has the fittings. Speak to him and let him work.",
        ),
        QuestStep(
            SABOTAGE, Objective.Predicate(J_SABOTAGE) { p -> QuestEngine.counter(p, this) >= WORKSHOP_KNIGHTS },
            anchor = WORKSHOP_LANDING,
            onEnter = { p -> WorkshopDefence.start(p) },
            nudge = "The Kinshra followed the shipment. Speak to Nulodion at his workshop when you are ready to hold the yard - load the new gun with his cannonballs (Fire) and fight beside it.",
        ),
        QuestStep(
            PAYOFF, Objective.TalkTo(J_PAYOFF, NULODION),
            anchor = NULODION_TILE, anchorNpc = NULODION,
        ),
        QuestStep(
            REPORT, Objective.TalkTo(J_REPORT, AMIK),
            anchor = AMIK_TILE, anchorNpc = AMIK,
            nudge = "Sir Amik Varze - top floor of Falador castle's west tower.",
        ),
    )

    override val completionRewards: List<Reward> = listOf(
        Reward.WarEffort(WAR_EFFORT),
        Reward.Flag(ARTILLERY_FLAG),
    )

    /** 2 Quest Points (the design's reward). */
    override val questPoints: Int = 2

    override val completionMessage: String = "<col=801700>$J_DONE</col>"

    /** Native quest tab: the relabelled Dwarf Cannon row (`QuestTablePatch.PLAN`, sort "17 The Guns of Asgarnia"). */
    override val nativeTabVarp: Int? = QuestJournal.GUNS_OF_ASGARNIA_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.GUNS_OF_ASGARNIA_QUEST_COMPLETE

    override fun onComplete(p: Player) {
        strategicUpdate(p)
    }

    init {
        // One above quest priority: A Matter of Trolls begins at the same instant as this quest and
        // its START pointer also claims Sir Amik; his second problem must be heard first (the
        // Burthorpe pointer follows on the next click). See AMatterOfTrolls for the full ordering.
        talk(AMIK, START, NpcTalk.PRIORITY_QUEST + 1) { p -> amikStart(p) }
        talk(AMIK, REPORT, NpcTalk.PRIORITY_QUEST + 1) { p -> amikDebrief(p) }
        // Mid-quest: Sir Amik points at whatever is next rather than falling to a placeholder line.
        // Below quest priority AND below A Matter of Trolls' mid-quest nudge (PRIORITY_QUEST - 5),
        // which folds this pointer in while both quests are live.
        NpcTalk.register(AMIK, NpcTalk.PRIORITY_QUEST - 10) { p ->
            val step = QuestEngine.stepId(p, this)
            if (step != null && step != START && step != REPORT) { q -> amikMidQuest(q) } else null
        }

        talk(NULODION, NULODION_STEP) { p -> nulodionProblem(p) }
        talk(NULODION, KELDAGRIM) { p -> nulodionWaiting(p) }
        talk(NULODION, ROUTE) { p -> nulodionWaiting(p) }
        talk(NULODION, AGREEMENT) { p -> nulodionWaiting(p) }
        talk(NULODION, FURNACE) { p -> nulodionWaiting(p) }
        talk(NULODION, SHIPMENT) { p -> nulodionShipment(p) }
        talk(NULODION, CANNON) { p -> nulodionAssembly(p) }
        talk(NULODION, SABOTAGE) { p -> nulodionRegroup(p) }
        talk(NULODION, PAYOFF) { p -> nulodionPayoff(p) }

        talk(FOREMAN, KELDAGRIM) { p -> foremanContact(p) }
        talk(FOREMAN, ROUTE) { p -> foremanRouteReminder(p) }
        talk(FOREMAN, AGREEMENT) { p -> foremanAgreement(p) }
        talk(FOREMAN, FURNACE) { p -> foremanDuringOrder(p) }
        talk(FOREMAN, SHIPMENT) { p -> foremanTrialDone(p) }
    }

    // --- gate / status ----------------------------------------------------------------------

    /** The first registered gate key decides (At the White Wall once it exists; the story chain until then). */
    private fun gateDone(p: Player): Boolean {
        val chain = GATE_KEYS.firstNotNullOfOrNull { key -> QuestRegistry.byKey(key) } ?: return false
        return chain.complete(p)
    }

    fun trollsDone(p: Player): Boolean = QuestRegistry.isComplete(p, TROLLS_KEY) || Flags.has(p, NORTHERN_FRONT_FLAG)

    /** One-line status (`::guns`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>$NAME:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>$NAME - current objective:</col> ${QuestEngine.objectiveLine(p, this)}${progressSuffix(p)}"
        else -> "<col=801700>$NAME:</col> not started - it begins on its own once the Asgarnia campaign is open to you (At the White Wall; until that quest exists, the main story before it)."
    }

    private fun progressSuffix(p: Player): String = when (QuestEngine.stepId(p, this)) {
        ROUTE -> " (${QuestEngine.counter(p, this).coerceAtMost(ROUTE_CELL_SIZE)}/$ROUTE_CELL_SIZE)"
        FURNACE -> " (${QuestEngine.counter(p, this).coerceAtMost(STEEL_GOAL)}/$STEEL_GOAL)"
        SABOTAGE -> " (${QuestEngine.counter(p, this).coerceAtMost(WORKSHOP_KNIGHTS)}/$WORKSHOP_KNIGHTS)"
        else -> ""
    }

    /** The ASGARNIA — BREACH strategic update printed on completion (design §54). */
    fun strategicUpdate(p: Player) {
        val north = if (trollsDone(p)) "<col=4f9b4f>SECURED</col>" else "<col=ffae00>UNRESOLVED</col>"
        p.message("<col=801700>ASGARNIA - BREACH.</col> Northern Frontier: $north. Artillery Production: <col=4f9b4f>RESTORED</col>. Keldagrim Military Steel: <col=4f9b4f>LIMITED TRADE RESTORED</col>.")
        p.message("Kinshra Front: <col=ffae00>ACTIVE</col>. Temple Knight Intelligence: <col=ffae00>UNRESOLVED</col>. BREACH: <col=ffae00>INCOMPLETE</col> - Asgarnia cannot commit its guns to Varrock while the Kinshra front remains active.")
    }

    // --- items -----------------------------------------------------------------------------

    private fun id(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1)

    fun steelInPack(p: Player): Int = id(STEEL_BAR).let { if (it < 0) 0 else p.inventory.getItemCount(it) }

    /** The Foreman's trial kit: 10 iron ore, 10 coal and a bucket of water (overflow goes to the bank). */
    private fun giveTrialKit(p: Player) {
        Reward.giveItem(p, IRON_ORE, STEEL_GOAL)
        Reward.giveItem(p, COAL, STEEL_GOAL)
        Reward.giveItem(p, BUCKET_OF_WATER, 1)
        p.message("<col=801700>The Foreman hands over the trial order's materials: 10 iron ore, 10 coal and a bucket of water.</col> Anything that did not fit in your pack is in your bank.")
    }

    /** Bars taken from the dispenser while on the FURNACE step count toward the order (installed by the plugin). */
    fun onBarsTaken(p: Player, bar: BlastFurnace.Bar, n: Int) {
        if (bar.bar != STEEL_BAR || QuestEngine.stepId(p, this) != FURNACE) return
        val total = QuestEngine.addCounter(p, this, delta = n)
        if (total >= STEEL_GOAL) {
            QuestEngine.satisfy(p, this, FURNACE)
            p.message("<col=801700>The first trial order is complete.</col> Take the steel to Nulodion - the Foreman has something to say about it too.")
        } else {
            p.message("<col=801700>$NAME:</col> $total/$STEEL_GOAL steel bars for the trial order.")
        }
    }

    // --- dialogue helpers ---------------------------------------------------------------------

    private suspend fun QueueTask.amik(p: Player, text: String) = chatNpc(p, text, npc = id(AMIK), title = AMIK_NAME)
    private suspend fun QueueTask.nul(p: Player, text: String) = chatNpc(p, text, npc = id(NULODION), title = NULODION_NAME)
    private suspend fun QueueTask.fore(p: Player, text: String) = chatNpc(p, text, npc = id(FOREMAN), title = FOREMAN_NAME)
    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    // --- Sir Amik ---------------------------------------------------------------------------

    /** START (§5-7): the artillery problem. */
    private suspend fun QueueTask.amikStart(p: Player) {
        me(p, "You said Asgarnia had three problems.")
        amik(p, "I did.")
        me(p, "The worn-out weapons.")
        amik(p, "Ah.")
        amik(p, "That problem.")
        amik(p, "White Knights can take a wall.")
        amik(p, "Getting them through one is another matter.")
        me(p, "Cannons.")
        amik(p, "Dwarven multicannons.")
        amik(p, "We used to field considerably more of them.")
        me(p, "What happened?")
        amik(p, "Twelve years happened.")
        amik(p, "Some were destroyed.")
        amik(p, "Some wore out.")
        amik(p, "Some became parts for the ones that hadn't.")
        me(p, "Can't the dwarves build more?")
        amik(p, "They can.")
        me(p, "Then what's the problem?")
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, START) // mutate, then narrate
        amik(p, "Ask Nulodion.")
        amik(p, "You'll find him at the Dwarven Mine's entrance below Ice Mountain, north of the city. He builds the things.")
    }

    /** Mid-quest: a nudge toward whatever is next. */
    private suspend fun QueueTask.amikMidQuest(p: Player) {
        when (QuestEngine.stepId(p, this@GunsOfAsgarnia)) {
            NULODION_STEP -> amik(p, "Nulodion. The Dwarven Mine's entrance below Ice Mountain. He will tell you what I cannot.")
            KELDAGRIM, ROUTE, AGREEMENT -> {
                amik(p, "Keldagrim, then. I'd send a knight, but the dwarves have had twelve years of knights.")
                if (QuestEngine.stepId(p, this@GunsOfAsgarnia) == ROUTE) amik(p, "If it is the route they want secured, secure it. Falador will put regular patrols on it afterward - you have my word on that.")
            }
            FURNACE, SHIPMENT -> amik(p, "The dwarves agreed to a trial order? Then see it made and get it to Nulodion. One shipment is a beginning.")
            CANNON, SABOTAGE, PAYOFF -> amik(p, "Nulodion has the steel? Then let him work. Come back when there is a gun to speak of.")
            else -> amik(p, "Falador has problems enough. See to the one you're on.")
        }
    }

    /** REPORT (§49-53): the debrief. Completes the quest. */
    private suspend fun QueueTask.amikDebrief(p: Player) {
        amik(p, "Nulodion sent word.")
        amik(p, "He sounded pleased.")
        me(p, "Does he normally?")
        amik(p, "No.")
        amik(p, "That concerns me.")
        me(p, "Keldagrim agreed to resume limited steel shipments.")
        me(p, "Falador just has to keep the route protected.")
        amik(p, "That we can do.")
        me(p, "And Nulodion built the first replacement cannon.")
        amik(p, "One cannon doesn't break a front.")
        me(p, "No.")
        amik(p, "But it means there can be a second.")
        amik(p, "And a third.")
        val trolls = trollsDone(p)
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, REPORT) // completes the quest — mutate, then narrate
        if (trolls) {
            amik(p, "The Imperial Guard is moving south.")
            amik(p, "Our cannon foundries are working again.")
            me(p, "Sounds like we're getting somewhere.")
            amik(p, "We are.")
            amik(p, "Which usually means Tiffy is about to find a new problem.")
        } else {
            amik(p, "We have our guns back.")
            amik(p, "Now we need enough soldiers free to use them.")
            p.message("<col=801700>The northern frontier around Burthorpe still ties down the Imperial Guard.</col>")
        }
    }

    // --- Nulodion ---------------------------------------------------------------------------

    /** NULODION (§9-14): steel, Keldagrim, the assignment. */
    private suspend fun QueueTask.nulodionProblem(p: Player) {
        nul(p, "You want a cannon?")
        me(p, "Eventually, several.")
        p.message("Nulodion looks at you.")
        nul(p, "Of course you do.")
        me(p, "Falador needs them.")
        nul(p, "Falador has needed them for twelve years.")
        me(p, "Sir Amik said you can't replace them.")
        nul(p, "He said no such thing.")
        me(p, "He said-")
        nul(p, "We can build cannons.")
        nul(p, "We haven't forgotten which end points toward the enemy.")
        me(p, "So what's stopping you?")
        nul(p, "Steel.")
        me(p, "There's steel everywhere.")
        nul(p, "A sword takes a few bars.")
        nul(p, "A military battery eats them by the wagon.")
        nul(p, "Then it eats more when something breaks.")
        p.message("Nulodion gestures toward the workshop.")
        nul(p, "I can build you one cannon.")
        nul(p, "Perhaps two.")
        nul(p, "You want enough to matter in a war?")
        nul(p, "We need Keldagrim.")
        me(p, "Keldagrim survived?")
        nul(p, "Of course Keldagrim survived.")
        me(p, "You sound offended.")
        nul(p, "We live inside a mountain.")
        nul(p, "Before the Fall, steel moved south regularly.")
        nul(p, "Keldagrim produced it.")
        nul(p, "Asgarnia bought it.")
        nul(p, "Everyone complained about the price.")
        nul(p, "A functioning economy.")
        me(p, "And after Varrock?")
        nul(p, "Roads failed.")
        nul(p, "Shipments disappeared.")
        nul(p, "Kingdoms started hoarding what they could make themselves.")
        nul(p, "Keldagrim stopped promising metal to wars it couldn't control.")
        nul(p, "If Sir Amik wants cannon batteries again...")
        nul(p, "...someone needs to convince Keldagrim that sending military steel south is worth the trouble.")
        me(p, "You want me to negotiate with dwarves?")
        nul(p, "No.")
        nul(p, "I want you to negotiate with industrial dwarves.")
        me(p, "That's worse?")
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, NULODION_STEP) // mutate, then narrate
        nul(p, "Much.")
        nul(p, "The Blast Furnace. Keldagrim's furnace room - the Foreman there speaks for the works. The portal will carry you.")
    }

    /** KELDAGRIM / ROUTE / AGREEMENT / FURNACE: back too soon. */
    private suspend fun QueueTask.nulodionWaiting(p: Player) {
        when (QuestEngine.stepId(p, this@GunsOfAsgarnia)) {
            KELDAGRIM -> nul(p, "Keldagrim. The Blast Furnace Foreman. Go and argue with industrial dwarves; I've done my share.")
            ROUTE -> nul(p, "The Foreman wants the route cleared? Then it's Kinshra you're after, not me. They camp in the trees west of my fence - I hear them at night.")
            AGREEMENT -> nul(p, "Cleared them out? Good. Tell the Foreman - it's his word we need, not mine.")
            FURNACE -> nul(p, "A trial order. Ten bars. I asked for wagons and they gave you ten bars. Well - make them, and make them well.")
            else -> nul(p, "Steel. Keldagrim. Go.")
        }
    }

    /** SHIPMENT (§36-37): the hand-in — then straight into the assembly. */
    private suspend fun QueueTask.nulodionShipment(p: Player) {
        val have = steelInPack(p)
        if (have < STEEL_GOAL) {
            nul(p, "You actually got them to agree?")
            me(p, "To a trial order.")
            nul(p, if (have == 0) "Then where is it? Ten bars. In your hands, not a ledger." else "That's $have. The order was ten. In your hands - I don't work from a bank.")
            return
        }
        nul(p, "You actually got them to agree?")
        me(p, "To a trial order.")
        // Take the steel and advance back-to-back: no chat line between, so a closed dialogue can never
        // leave the player both paid and un-advanced (or advanced and still holding the bars).
        p.inventory.remove(id(STEEL_BAR), STEEL_GOAL)
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, SHIPMENT)
        p.message("You hand Nulodion the ten steel bars. He takes one and turns it over in the light.")
        nul(p, "Good steel.")
        me(p, "It's a steel bar.")
        nul(p, "And now you've insulted both Smithing and dwarves.")
        nulodionAssembly(p)
    }

    /** CANNON (§37-40): Nulodion builds. Ends with the alarm and the sabotage step. */
    private suspend fun QueueTask.nulodionAssembly(p: Player) {
        if (QuestEngine.stepId(p, this@GunsOfAsgarnia) != CANNON) return
        nul(p, "I've still got enough fittings here.")
        nul(p, "Gears.")
        nul(p, "Frame pieces.")
        nul(p, "A barrel that hasn't been patched six times.")
        p.message("Nulodion looks at the bars.")
        nul(p, "With this...")
        nul(p, "...we can finish one properly.")
        p.message("<col=801700>Nulodion works at the bench. Gears go onto the frame; the fresh steel becomes the pieces the old guns could no longer spare.</col>")
        wait(2)
        p.message("<col=801700>Nulodion completes Asgarnia's first newly produced multicannon in years.</col>")
        wait(1)
        // Mutate first: the alarm opens the private yard and the raiders are on their way.
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, CANNON)
        p.message("<col=cc2222>A dwarf on the fence shouts: \"Kinshra!\"</col>")
        me(p, "They followed the shipment.")
        nul(p, "Apparently.")
        p.message("Nulodion looks at the cannon.")
        me(p, "Good timing.")
        nul(p, "I was thinking the same thing.")
        nul(p, "The gun's dry - here, load it. Then hold the yard with me.")
    }

    /** SABOTAGE, back with Nulodion after a failed or abandoned defence: they come again. */
    private suspend fun QueueTask.nulodionRegroup(p: Player) {
        if (WorkshopDefence.isLive(p)) {
            nul(p, "Less talking. More shooting.")
            return
        }
        nul(p, "They're regrouping in the trees. The gun's still on its stand.")
        when (options(p, "I'm ready. Let them come.", "Not yet.", title = NULODION_NAME)) {
            1 -> {
                me(p, "I'm ready. Let them come.")
                if (!WorkshopDefence.start(p)) nul(p, "The yard's full of strangers just now. Give it a moment and ask again.")
            }
            else -> {
                me(p, "Not yet.")
                nul(p, "Then don't stand in the field of fire.")
            }
        }
    }

    /** PAYOFF (§46): what one cannon means. */
    private suspend fun QueueTask.nulodionPayoff(p: Player) {
        me(p, "One cannon.")
        nul(p, "One cannon today.")
        p.message("Nulodion looks toward the workshop.")
        nul(p, "Steel next week.")
        nul(p, "More after that.")
        me(p, "So we can replace the batteries Falador lost?")
        nul(p, "If Keldagrim keeps producing...")
        nul(p, "...and Falador keeps the route open...")
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, PAYOFF) // mutate, then narrate
        nul(p, "Yes.")
        nul(p, "Tell Sir Amik. He'll want to hear it from someone who isn't me.")
    }

    // --- the Blast Furnace Foreman -------------------------------------------------------------

    /** KELDAGRIM (§17-21): why Keldagrim stopped shipping, and what it wants. */
    private suspend fun QueueTask.foremanContact(p: Player) {
        me(p, "I've come from Falador.")
        fore(p, "My condolences.")
        me(p, "They want to restart military steel orders.")
        p.message("The Foreman stops.")
        fore(p, "No.")
        me(p, "That was quick.")
        fore(p, "We've had twelve years to consider it.")
        me(p, "You have the steel.")
        fore(p, "Yes.")
        me(p, "Falador has the money.")
        fore(p, "Presumably.")
        me(p, "So what's the problem?")
        fore(p, "The bit between here and Falador.")
        fore(p, "We can put steel underground.")
        fore(p, "We can move it south.")
        fore(p, "What happens when it reaches daylight?")
        me(p, "Kinshra.")
        fore(p, "Bandits.")
        fore(p, "Kinshra.")
        fore(p, "Rogues.")
        fore(p, "Anyone who sees twenty crates of steel and suddenly develops commercial ambition.")
        me(p, "So protect the shipment.")
        fore(p, "That's what Falador said about the last ones.")
        fore(p, "Clear the group operating near the southern receiving route.")
        fore(p, "Falador puts regular soldiers on it afterward...")
        fore(p, "...and I'll authorize a trial order.")
        me(p, "One shipment?")
        fore(p, "One.")
        me(p, "Generous.")
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, KELDAGRIM) // mutate, then narrate
        fore(p, "You haven't met our accountants.")
        fore(p, "The receiving route comes up beside the Dwarven Mine's Ice Mountain entrance - Nulodion's yard. They're camped in the trees west of it.")
    }

    private suspend fun QueueTask.foremanRouteReminder(p: Player) {
        fore(p, "The route. West of Nulodion's yard, in the trees. When it's clear, we'll talk about a trial order.")
    }

    /** AGREEMENT (§25-27, 30): the limited agreement and the trial order's materials. */
    private suspend fun QueueTask.foremanAgreement(p: Player) {
        fore(p, "Well?")
        me(p, "They won't be bothering the next shipment.")
        fore(p, "And after you leave?")
        me(p, "Falador stations soldiers on the route.")
        fore(p, "Sir Amik agreed?")
        me(p, "He wants cannons.")
        fore(p, "Fair point.")
        fore(p, "Keldagrim will resume limited military steel shipments to Asgarnia while the route remains protected.")
        fore(p, "One condition.")
        me(p, "Of course.")
        fore(p, "If you're going to promise Falador our steel...")
        fore(p, "...you can help make the first batch.")
        // Kit and advance back-to-back (dupe-proof: a re-talk lands on the FURNACE branch).
        giveTrialKit(p)
        QuestEngine.satisfy(p, this@GunsOfAsgarnia, AGREEMENT)
        fore(p, "Ten iron, ten coal - steel takes half the coal here - and a bucket for the bars, unless you own ice gloves. Ore on the belt, bars from the dispenser.")
        fore(p, "The coffer's on Keldagrim's account for this order. Don't get used to it.")
    }

    /** FURNACE: progress, and a re-issue if the materials are gone. */
    private suspend fun QueueTask.foremanDuringOrder(p: Player) {
        val made = QuestEngine.counter(p, this@GunsOfAsgarnia)
        fore(p, "$made of $STEEL_GOAL. The machine doesn't get faster because you stand there.")
        val ironId = id(IRON_ORE)
        val need = STEEL_GOAL - made
        val ironAround = p.inventory.getItemCount(ironId) + p.bank.getItemCount(ironId) + BlastFurnace.oreInMachine(p)
        if (need > 0 && ironAround <= 0) {
            fore(p, "Lost the ore? ... The accountants will hear about this.")
            Reward.giveItem(p, IRON_ORE, need)
            Reward.giveItem(p, COAL, need)
            if (p.inventory.getItemCount(id(BUCKET_OF_WATER)) == 0 && p.equipment.getItemCount(id(BlastFurnace.ICE_GLOVES)) == 0) Reward.giveItem(p, BUCKET_OF_WATER, 1)
            fore(p, "$need iron, $need coal. Once.")
        }
    }

    /** SHIPMENT (§33-34): the Foreman's word on the trial. */
    private suspend fun QueueTask.foremanTrialDone(p: Player) {
        fore(p, "Ten bars.")
        me(p, "Nulodion asked for considerably more than that.")
        fore(p, "Nulodion always asks for considerably more.")
        p.message("The Foreman gestures toward the furnace.")
        fore(p, "This isn't the shipment.")
        fore(p, "This proves there can be another shipment after it.")
        me(p, "So Keldagrim will resume production?")
        fore(p, "Limited production.")
        me(p, "For Asgarnia.")
        fore(p, "While Asgarnia keeps the route open.")
        me(p, "I'll take it.")
        fore(p, "Good.")
        fore(p, "Because that's the offer.")
    }

    // --- shared with the plugin -------------------------------------------------------------

    /** Nulodion's everyday lines — no quest branch claimed the click. */
    suspend fun QueueTask.nulodionIdle(p: Player) {
        if (QuestEngine.isComplete(p, this@GunsOfAsgarnia)) {
            nul(p, "Steel next week. More after that. Falador keeps the route open and I keep building.")
            me(p, "How many so far?")
            nul(p, "More than last week. Fewer than I'd like.")
            return
        }
        nul(p, "You want a cannon?")
        me(p, "Not today.")
        nul(p, "Nobody does. Then one day everybody does, all at once.")
    }
}
