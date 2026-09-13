package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.messageBox
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.mechanics.Flags
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
import org.alter.plugins.content.quests.framework.TalkScript
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Old Wounds** — Asgarnia (BREACH), Quest 4 (`docs/quests/old-wounds.md`): the campaign's
 * intelligence quest and the player's first *deliberate* Wilderness mission. Sir Tiffy Cashien has
 * noticed Kinshra officers and couriers going north and not coming back; the player searches the
 * Dark Warriors' Fortress for their orders, meets Lord Daquarius (not a boss — a rival who is
 * looking for the same thing), follows an old Temple Knight reference to **Survey Site Seven — the
 * First Scar** (the ring of ruined pillars south-east of the fortress), reads a buried field
 * report, and brings both documents back. The military half of the orders is what makes BREACH
 * possible; the other half is the first crack in the accepted history of the Fall.
 *
 * A framework quest ([QuestDefinition]): dialogue + state + area triggers + one crate search + two
 * light-custom documents + two owner-bound Daquarius scenes ([LordDaquarius]). Everything sits on
 * the existing world: Tiffy on his park bench, the unchanged fortress and its Dark Warriors, the
 * real Wilderness with real PvP (never instanced, never protected), an existing ruin for the Scar.
 * No new NPC, map, enemy, mechanic, instance or object.
 *
 * Gate: A Matter of Trolls AND The Guns of Asgarnia (Quests 2 and 3). Whichever of those keys is
 * registered is required; if neither has landed yet the gate falls back down the chain so no PR
 * merge order can dead-end the campaign. Auto-begins the moment the gate opens.
 *
 * Journal varp [QuestJournal.OLD_WOUNDS_VARP] (generic framework packing); native quest-tab row =
 * the relabelled **Wanted!** row (varp 1051 — that quest's own start NPC is Sir Tiffy).
 *
 * Every transition is written BEFORE the last line of its beat (mutate-before-narrate); the crate
 * and the cache are dupe-proof (an item already held or banked is never issued twice) and
 * re-issue when the document is genuinely lost, so the quest cannot dead-end on a lost note.
 * Wiring (Talk-to binds, the crate hook, the Read verbs, the scene sweep, `::oldwounds`) is in
 * [OldWoundsPlugin].
 */
object OldWounds : QuestDefinition(
    key = "old_wounds",
    displayName = "Old Wounds",
    chainIndex = QuestBook.OLD_WOUNDS,
    journalVarp = QuestJournal.OLD_WOUNDS_VARP,
) {
    const val KEY = "old_wounds"

    /** Sir Tiffy Cashien — his Falador Park bench (`npc_spawns.json`: 4687 @ 2997,3373,0). */
    const val TIFFY = "npc.sir_tiffy_cashien"
    const val TIFFY_NAME = "Sir Tiffy Cashien"
    val TIFFY_TILE = Tile(2997, 3373, 0)

    /** The two Asgarnia quests this follows (their keys are the contract with those PRs). */
    const val TROLLS_KEY = "a_matter_of_trolls"
    const val GUNS_KEY = "guns_of_asgarnia"
    const val WHITE_WALL_KEY = "at_the_white_wall"

    /** Flags the sibling quests set (read only for the campaign board — never required). */
    const val NORTHERN_FRONT_FLAG = "asgarnia.northern_front_secured"
    const val ARTILLERY_FLAG = "asgarnia.artillery_restored"

    /** Flags this quest sets on completion (the design's ASGARNIA_INTELLIGENCE_SECURED / FIRST_SCAR_DISCOVERED). */
    const val INTELLIGENCE_FLAG = "asgarnia.intelligence_secured"
    const val FIRST_SCAR_FLAG = "first_scar.discovered"

    /**
     * The **Kinshra Field Orders** — LIGHT CUSTOM: the stock "Orders note" def (28431 — it already
     * carries the Read pack verb) with a server-side override (`itemOverrides/quests/OldWounds.yml`:
     * untradeable, always kept on death) and a cache rename for the client (the `oldwounds` action of
     * the "Item def cache edit" workflow). Sir Tiffy keeps them at the report.
     */
    const val ORDERS = "item.orders_note"

    /**
     * The **Damaged Temple Knight Report** — same treatment on the stock "Intel report" def (761,
     * Read verb). The player keeps it after the quest: the second entry in the lore journal.
     */
    const val REPORT = "item.intel_report"

    /** The crate in the fortress hall (the stock searchable crate 354 at 3026,3628) that holds the orders. */
    const val ORDERS_CRATE = "object.crate_354"
    val ORDERS_CRATE_TILE = Tile(3026, 3628, 0)

    private const val WAR_EFFORT = 75

    // --- step ids (persisted — never rename) --------------------------------------------------
    const val S_BRIEF = "brief"
    const val S_FORTRESS = "fortress"
    const val S_ORDERS = "orders"
    const val S_REPORT_TIFFY = "report_tiffy"
    const val S_FIRST_SCAR = "first_scar"
    const val S_SCAR_STONE = "scar_stone"
    const val S_SCAR_ROCK = "scar_rock"
    const val S_SCAR_CACHE = "scar_cache"
    const val S_REPORT_FOUND = "report_found"
    const val S_RETURN = "return_tiffy"

    /** Counters: the two Daquarius scenes have played (they can be replayed by re-reading on site until they have). */
    private const val C_DAQ_FORTRESS = "daq_fortress"
    private const val C_DAQ_SCAR = "daq_scar"

    // --- world anchors (all existing, unchanged; region dumps 12088/12089) --------------------

    /** The Dark Warriors' Fortress interior (both floors; the hall doors are on the south face at z3626). */
    val FORTRESS = Area(3020, 3622, 3038, 3642)
    val FORTRESS_HALL = Tile(3029, 3628, 0)
    private val DAQ_FORTRESS_TILE = Tile(3029, 3630, 0)
    private val DAQ_FORTRESS_EXIT = Tile(3033, 3627, 0)

    /**
     * **Survey Site Seven — the First Scar**: the ring of ruined pillars and rubble south-east of the
     * fortress (locs 34795-34798 / 34803-34804 at 3056-3066 × 3587-3595, Wilderness level 9). An
     * existing landmark, untouched: the name is Temple Knight terminology, not a crater.
     */
    val RUINS = Area(3053, 3584, 3068, 3599)
    val RUINS_LANDING = Tile(3060, 3593, 0)

    /** Inspection 1 — the discoloured stone: the standing pillars on the ring's south side. */
    val SCAR_STONE = Area(3058, 3586, 3062, 3588)
    val SCAR_STONE_TILE = Tile(3060, 3586, 0)

    /** Inspection 2 — the warped rock: the mass of stone at the ring's centre (3059-3061 × 3590-3592). */
    val SCAR_ROCK = Area(3058, 3589, 3062, 3593)
    val SCAR_ROCK_TILE = Tile(3060, 3593, 0)

    /** Inspection 3 — the old marker: the rubble on the north-west side (loc 34803 at 3057,3594) hides the cache. */
    val SCAR_CACHE = Area(3056, 3593, 3058, 3595)
    val SCAR_CACHE_TILE = Tile(3057, 3593, 0)

    private val DAQ_SCAR_TILE = Tile(3060, 3597, 0)
    private val DAQ_SCAR_EXIT = Tile(3060, 3600, 0)

    // --- journal (docs/quests/old-wounds.md, "Quest journal") --------------------------------

    private const val J_START = "Sir Tiffy believes the Kinshra are conducting unusual operations in the Wilderness."
    private const val J_FORTRESS = "Search the Dark Warriors' Fortress for information about Kinshra activity."
    private const val J_ORDERS = "Recover the Kinshra Field Orders."
    private const val J_REPORT_TIFFY = "Take the stolen orders to Sir Tiffy."
    private const val J_FIRST_SCAR = "Investigate the old Temple Knight site known as the First Scar."
    private const val J_INVESTIGATE = "Examine the strange remains at the First Scar."
    private const val J_REPORT_FOUND = "Read the damaged Temple Knight report."
    private const val J_RETURN = "Take the report back to Sir Tiffy."
    const val J_DONE = "The First Scar predates Varrock's fall. Asgarnia now has the intelligence required to attack the Kinshra front."

    override val prerequisites: List<Prerequisite> = listOf(
        Prerequisite.Custom("finish A Matter of Trolls and The Guns of Asgarnia") { p -> gateMet(p) },
    )

    /** Begins the moment both problems are solved — login, the poll, or Tiffy's opener on talk. */
    override val autoBegin: Boolean = true

    /** Native quest tab: the relabelled Wanted! row (`QuestTablePatch.PLAN`, sort "18 Old Wounds"). */
    override val nativeTabVarp: Int = QuestJournal.OLD_WOUNDS_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.OLD_WOUNDS_QUEST_COMPLETE

    /** 2 Quest Points (the design's reward) — counted by the registry-derived summary-tab total. */
    override val questPoints: Int = 2

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            S_BRIEF, Objective.TalkTo(J_START, TIFFY),
            anchor = TIFFY_TILE, anchorNpc = TIFFY,
            nudge = "Sir Tiffy Cashien wants a word in Falador Park - his bench east of the castle.",
        ),
        QuestStep(
            S_FORTRESS, Objective.ReachArea(J_FORTRESS, FORTRESS),
            anchor = FORTRESS_HALL,
            onLeave = { p -> insideFortress(p) },
            nudge = "The Dark Warriors' Fortress stands in the Wilderness north-west of Edgeville (about level 14). Other players can attack you there - take only what you're prepared to lose. The Dark Warriors are ordinary enemies; the intelligence is what matters.",
        ),
        QuestStep(
            S_ORDERS, Objective.Manual(J_ORDERS),
            anchor = ORDERS_CRATE_TILE,
            nudge = "Search the crate in the fortress hall.",
        ),
        QuestStep(
            S_REPORT_TIFFY, Objective.TalkTo(J_REPORT_TIFFY, TIFFY),
            anchor = TIFFY_TILE, anchorNpc = TIFFY,
            nudge = "The Kinshra are searching the Wilderness for something called the First Scar, and their orders mention old Temple Knight records concerning Lucien. Sir Tiffy needs to see this.",
        ),
        QuestStep(
            S_FIRST_SCAR, Objective.ReachArea(J_FIRST_SCAR, RUINS),
            anchor = RUINS_LANDING,
            onLeave = { p -> arriveAtScar(p) },
            nudge = "Survey Site Seven: the ring of ruined pillars on open ground south-east of the Dark Warriors' Fortress, Wilderness level 9. Get there before the Kinshra recover whatever remains.",
        ),
        QuestStep(
            S_SCAR_STONE, Objective.ReachArea(J_INVESTIGATE, SCAR_STONE),
            anchor = SCAR_STONE_TILE,
            onLeave = { p -> inspectStone(p) },
            nudge = "The standing stones on the ring's south side.",
        ),
        QuestStep(
            S_SCAR_ROCK, Objective.ReachArea(J_INVESTIGATE, SCAR_ROCK),
            anchor = SCAR_ROCK_TILE,
            onLeave = { p -> inspectRock(p) },
            nudge = "The mass of rock at the ring's centre.",
        ),
        QuestStep(
            S_SCAR_CACHE, Objective.ReachArea(J_INVESTIGATE, SCAR_CACHE),
            anchor = SCAR_CACHE_TILE,
            onLeave = { p -> findCache(p) },
            nudge = "The rubble on the ring's north-west side.",
        ),
        QuestStep(
            S_REPORT_FOUND, Objective.Manual(J_REPORT_FOUND),
            anchor = SCAR_ROCK_TILE,
            nudge = "Read it from your pack.",
        ),
        QuestStep(
            S_RETURN, Objective.TalkTo(J_RETURN, TIFFY),
            anchor = TIFFY_TILE, anchorNpc = TIFFY,
            nudge = "Something about the report sounds disturbingly similar to accounts from the Fall of Varrock.",
        ),
    )

    override val completionRewards: List<Reward> = listOf(
        Reward.WarEffort(WAR_EFFORT),
        Reward.Flag(INTELLIGENCE_FLAG),
        Reward.Flag(FIRST_SCAR_FLAG),
    )

    override val completionMessage: String =
        "<col=801700>$J_DONE</col> Asgarnia now has the manpower, artillery and intelligence needed to break the Kinshra front. " +
            "Sir Amik will send word when Falador is ready to move east."

    override fun onComplete(p: Player) {
        campaignBoard(p).forEach { p.message(it) }
    }

    init {
        // Opener: an eligible player who talks to Tiffy begins the quest on the spot (autoBegin
        // otherwise waits for the poll). Just below quest priority so any OTHER live quest step on
        // Tiffy (At the White Wall's) still wins the conversation.
        NpcTalk.register(TIFFY, NpcTalk.PRIORITY_QUEST - 1) { p ->
            if (!QuestEngine.canBegin(p, this)) return@register null
            val script: TalkScript = { pl -> if (QuestEngine.begin(pl, this@OldWounds)) brief(pl) }
            script
        }
        talk(TIFFY, S_BRIEF) { p -> brief(p) }
        talk(TIFFY, S_REPORT_TIFFY) { p -> report(p) }
        talk(TIFFY, S_RETURN) { p -> debrief(p) }
        // Mid-quest: Tiffy points the way instead of dropping to his everyday lines.
        NpcTalk.register(TIFFY, NpcTalk.PRIORITY_QUEST) { p ->
            val script: TalkScript? = when (QuestEngine.stepId(p, this)) {
                S_FORTRESS, S_ORDERS -> { pl -> remindFortress(pl) }
                S_FIRST_SCAR, S_SCAR_STONE, S_SCAR_ROCK, S_SCAR_CACHE -> { pl -> remindScar(pl) }
                S_REPORT_FOUND -> { pl -> remindRead(pl) }
                else -> null
            }
            script
        }
    }

    // --- gate / status ----------------------------------------------------------------------

    /**
     * Both A Matter of Trolls and The Guns of Asgarnia — whichever of them is registered. If
     * neither has landed yet, fall back down the chain (the first REGISTERED key wins) so the
     * campaign never dead-ends on PR merge order.
     */
    private fun gateMet(p: Player): Boolean {
        val required = listOf(TROLLS_KEY, GUNS_KEY).filter { QuestRegistry.byKey(it) != null }
        if (required.isNotEmpty()) return required.all { QuestRegistry.isComplete(p, it) }
        val fallback = listOf(WHITE_WALL_KEY, "a_kingdom_alone", "first_reclamation", "the_north", "recruit_trials")
        val first = fallback.firstOrNull { QuestRegistry.byKey(it) != null } ?: return false
        return QuestRegistry.isComplete(p, first)
    }

    /** One-line status (`::oldwounds`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>Old Wounds:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>Old Wounds — current objective:</col> ${QuestEngine.objectiveLine(p, this)}"
        else -> "<col=801700>Old Wounds:</col> not started — finish A Matter of Trolls and The Guns of Asgarnia and Sir Tiffy will send for you."
    }

    /** The design's campaign journal, derived live from the quests' flags — printed at completion and by `::oldwounds`. */
    fun campaignBoard(p: Player): List<String> {
        fun solved(flag: String, key: String) = Flags.has(p, flag) || QuestRegistry.isComplete(p, key)
        val front = solved(NORTHERN_FRONT_FLAG, TROLLS_KEY)
        val guns = solved(ARTILLERY_FLAG, GUNS_KEY)
        val intel = QuestEngine.isComplete(p, this)
        fun mark(ok: Boolean, yes: String, no: String) = if (ok) "<col=4f9b4f>$yes</col>" else "<col=ffae00>$no</col>"
        val breach = when {
            front && guns && intel -> "<col=4f9b4f>READY</col> - speak with Sir Amik Varze"
            else -> "<col=ffae00>INCOMPLETE</col>"
        }
        return listOf(
            "<col=801700>ASGARNIA - BREACH</col>",
            " Northern Frontier: ${mark(front, "SECURED", "UNKNOWN")}   Artillery Production: ${mark(guns, "RESTORED", "UNKNOWN")}",
            " Kinshra Intelligence: ${mark(intel, "SECURED", "UNKNOWN")}   The First Scar: ${mark(Flags.has(p, FIRST_SCAR_FLAG), "DISCOVERED", "UNKNOWN")}",
            " BREACH: $breach",
        )
    }

    // --- the documents ----------------------------------------------------------------------

    private fun itemId(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1)

    private fun carrying(p: Player, key: String): Boolean = itemId(key).let { it >= 0 && p.inventory.getItemCount(it) > 0 }
    private fun banked(p: Player, key: String): Boolean = itemId(key).let { it >= 0 && p.bank.getItemCount(it) > 0 }
    private fun holds(p: Player, key: String): Boolean = carrying(p, key) || banked(p, key)

    fun hasOrders(p: Player): Boolean = carrying(p, ORDERS)
    fun hasReport(p: Player): Boolean = carrying(p, REPORT)

    private fun give(p: Player, key: String) = Reward.giveItem(p, key, 1)

    private fun take(p: Player, key: String) {
        val id = itemId(key)
        if (id >= 0) runCatching { p.inventory.remove(id, 1) }
    }

    /**
     * The crate in the fortress hall ([CrateSearch] hook — every stock searchable crate routes here
     * first; only THE crate at [ORDERS_CRATE_TILE] is ours). On ORDERS: hand over the orders (never
     * twice — a copy already in the pack or the bank is not re-issued), move the quest on, then
     * read them and meet Daquarius. Later, if the orders were genuinely lost before Tiffy saw them,
     * the crate yields another copy. Returns true when the search was ours.
     */
    fun searchCrate(p: Player, obj: GameObject): Boolean {
        if (!obj.tile.sameAs(ORDERS_CRATE_TILE)) return false // Tile has no equals override — compare by coordinates
        when (QuestEngine.stepId(p, this)) {
            S_ORDERS -> {
                if (!holds(p, ORDERS)) {
                    give(p, ORDERS)
                    p.message("<col=801700>Under the straw at the bottom of the crate: a sheaf of orders under the Kinshra seal.</col>")
                } else {
                    p.message("You already have the Kinshra field orders. Read them.")
                }
                QuestEngine.satisfy(p, this, S_ORDERS) // → REPORT_TIFFY: mutate, then narrate
                p.queue { readOrders(p, fromCrate = true) }
                return true
            }
            S_REPORT_TIFFY -> {
                if (!holds(p, ORDERS)) {
                    give(p, ORDERS)
                    p.message("<col=801700>Another copy of the orders lies under the straw. Kinshra clerks are nothing if not thorough.</col>")
                } else {
                    p.message("Nothing else of interest. Sir Tiffy will want to see the orders you already have.")
                }
                return true
            }
            else -> {
                if (QuestEngine.isComplete(p, this) || QuestEngine.started(p, this)) {
                    p.message("Straw, splinters and a Kinshra quartermaster's tally. Nothing you need.")
                    return true
                }
                return false // not our player: the crate behaves as it always did
            }
        }
    }

    /** The Kinshra Field Orders, page by page (also the pack's Read option, any time). */
    private suspend fun QueueTask.showOrders(p: Player) {
        messageBox(p, "<col=5a3a1e>KINSHRA FIELD ORDERS</col><br>Western companies maintain present positions.<br>Reserve strength is to remain concealed behind<br>the northern works.")
        messageBox(p, "Artillery crews are not to redeploy without<br>direct authorization.<br>Supply convoys will continue through the<br>western approach.")
        messageBox(p, "Survey detachments will continue north through<br>the fortress.<br>All recovered records bearing the Temple Knight<br>seal are to be delivered unopened.")
        messageBox(p, "Priority remains the site designated<br><col=8b0000>FIRST SCAR</col>.<br>Searches concerning Lucien are authorized by<br>Lord Daquarius personally.")
    }

    /** ORDERS → the read, the reaction, and — inside the fortress — Daquarius. */
    private suspend fun QueueTask.readOrders(p: Player, fromCrate: Boolean) {
        showOrders(p)
        if (fromCrate || QuestEngine.stepId(p, this@OldWounds) == S_REPORT_TIFFY) {
            me(p, "Lucien?")
            p.message("<col=801700>The Kinshra are searching the Wilderness for something called the First Scar. Their orders specifically mention old Temple Knight records concerning Lucien. I should report this to Sir Tiffy.</col>")
        }
        if (QuestEngine.stepId(p, this@OldWounds) == S_REPORT_TIFFY &&
            QuestEngine.counter(p, this@OldWounds, C_DAQ_FORTRESS) == 0 &&
            FORTRESS.contains(p.tile) && p.tile.height == 0
        ) {
            daquariusAtTheFortress(p)
        }
    }

    /** The pack's Read option on the orders: the text, forever; on the way to Tiffy it also replays a missed Daquarius scene. */
    suspend fun QueueTask.readOrdersFromPack(p: Player) = readOrders(p, fromCrate = false)

    /** The damaged Temple Knight report, page by page (the pack's Read option, forever — the lore journal). */
    private suspend fun QueueTask.showReport(p: Player) {
        messageBox(p, "<col=5a3a1e>FIELD REPORT - SITE SEVEN</col><br>The disturbance has ended.<br>No conventional magical source has been identified.<br>Witness accounts remain inconsistent.")
        messageBox(p, "Several reported hearing movement or voices where<br>no persons were present.<br>Magnetic and navigational instruments failed<br>inside the affected area.")
        messageBox(p, "Stone recovered from the centre displays<br>deformation inconsistent with heat or impact.<br>Similar disturbances have been noted during<br>operations concerning Lucien.")
        messageBox(p, "Further investigation recommended.<br><br><col=8b0000>Recommendation denied.</col><br><col=8b0000>Site sealed. Records restricted.</col>")
    }

    /** REPORT_FOUND → the read, the realisation, and — on site — Daquarius again. */
    suspend fun QueueTask.readReportFromPack(p: Player) {
        showReport(p)
        val cleared = QuestEngine.satisfy(p, this@OldWounds, S_REPORT_FOUND) // → RETURN: mutate, then narrate
        if (cleared) {
            me(p, "Voices.")
            me(p, "Warped stone.")
            me(p, "Magic nobody could identify...")
            p.message("<col=801700>Something about the report sounds disturbingly similar to accounts from the Fall of Varrock.</col>")
        }
        if (QuestEngine.stepId(p, this@OldWounds) == S_RETURN &&
            QuestEngine.counter(p, this@OldWounds, C_DAQ_SCAR) == 0 &&
            RUINS.contains(p.tile) && p.tile.height == 0
        ) {
            daquariusAtTheScar(p)
        }
    }

    // --- area beats (chat narration — the real place does the work) ------------------------

    private fun insideFortress(p: Player) {
        narrate(
            p,
            "The Dark Warriors' Fortress. The Dark Warriors hold the walls as they always have.",
            "Fresh boot-tracks in the mud, a Kinshra pennant left on a spear wall, ration crates that are not the fortress's own.",
            "Whatever the Kinshra left behind is inside. The hall is the place to start.",
        )
    }

    private fun arriveAtScar(p: Player) {
        narrate(
            p,
            "Survey Site Seven.",
            "Ruined pillars stand in a broken ring on open ground. The Temple Knights called this place the First Scar.",
            "It does not look like much. That is probably why nobody stopped here in twelve years.",
        )
    }

    private fun inspectStone(p: Player) {
        narrate(p, "The stone is discoloured, but there is no sign of ordinary fire.")
    }

    private fun inspectRock(p: Player) {
        narrate(p, "Some of the surrounding rock appears warped rather than broken.")
        p.queue { me(p, "What happened here?") }
    }

    /** SCAR_CACHE cleared: the old field cache under the rubble — the report — then the read. */
    private fun findCache(p: Player) {
        narrate(p, "You shift the rubble. Beneath it, wrapped in rotted oilcloth, is an old Temple Knight field cache.")
        if (!holds(p, REPORT)) {
            give(p, REPORT)
            p.message("<col=801700>Inside: a damaged Temple Knight report, the seal long broken.</col>")
        }
        p.queue { readReportFromPack(p) }
    }

    /**
     * The plugin sweep: a player back on RETURN who lost the report (neither pack nor bank) and
     * stands at the cache again is issued another — the ground, not Tiffy, is where it came from.
     */
    fun sweep(world: World) {
        world.players.forEach { p ->
            if (p.index < 0 || !p.entityType.isHumanControlled) return@forEach
            if (QuestEngine.stepId(p, this) != S_RETURN) return@forEach
            if (!SCAR_CACHE.contains(p.tile) || p.tile.height != 0 || holds(p, REPORT)) return@forEach
            give(p, REPORT)
            p.message("<col=801700>You dig through the rubble again. The cache held more than one copy - the Temple Knights filed in triplicate even here.</col>")
        }
    }

    // --- dialogue helpers ---------------------------------------------------------------------

    private suspend fun QueueTask.tiffy(p: Player, text: String) =
        chatNpc(p, text, npc = runCatching { getRSCM(TIFFY) }.getOrDefault(-1), title = TIFFY_NAME)

    private suspend fun QueueTask.daq(p: Player, text: String) =
        chatNpc(p, text, npc = LordDaquarius.id(), title = LordDaquarius.NAME)

    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    /** Narration — sent synchronously so it lands BEFORE the engine's next-objective line. */
    private fun narrate(p: Player, vararg lines: String) {
        lines.forEach { p.message("<col=5d4037>$it</col>") }
    }

    // --- Sir Tiffy --------------------------------------------------------------------------

    /** BRIEF: what Tiffy has noticed, the fortress, and the Wilderness warning. */
    private suspend fun QueueTask.brief(p: Player) {
        tiffy(p, "Ah! There you are.")
        me(p, "Were you looking for me?")
        tiffy(p, "I was indeed.")
        tiffy(p, "I understand you've been solving several of<br>Asgarnia's more inconvenient military problems.")
        me(p, "The trolls.")
        tiffy(p, "Yes.")
        me(p, "The cannons.")
        tiffy(p, "Quite.")
        me(p, "So what's next?")
        tiffy(p, "That depends.")
        tiffy(p, "Do you prefer your enemies heavily armed...<br>...or behaving strangely?")
        me(p, "Which one is worse?")
        tiffy(p, "The strange ones, usually.")
        tiffy(p, "The Kinshra front hasn't collapsed.<br>But something has changed.")
        me(p, "What?")
        tiffy(p, "Movement.<br>Small groups. Officers. Couriers.<br>Not toward Falador.")
        p.message("Sir Tiffy pauses.")
        tiffy(p, "North.")
        me(p, "The Wilderness?")
        tiffy(p, "Precisely.")
        me(p, "Maybe they're getting reinforcements.")
        tiffy(p, "Then one would generally expect the<br>reinforcements to come back.")
        tiffy(p, "They aren't.")
        p.message("Sir Tiffy produces a sheaf of intercepted messages.")
        tiffy(p, "We've seen Kinshra detachments moving through<br>the Dark Warriors' Fortress.")
        tiffy(p, "Not enough for an offensive.<br>Far too many for sightseeing.")
        me(p, "What are they doing there?")
        tiffy(p, "That...")
        tiffy(p, "...is the inconvenient part.")
        me(p, "You don't know.")
        tiffy(p, "I prefer the phrase 'not yet'.")
        tiffy(p, "One other matter.")
        me(p, "The Wilderness.")
        tiffy(p, "Excellent. You're learning.")
        p.message("Sir Tiffy becomes slightly more serious.")
        tiffy(p, "The people you've fought on the roads follow rules.<br>Even the unpleasant ones.")
        tiffy(p, "Beyond the Wilderness ditch, other adventurers<br>do not.")
        tiffy(p, "Take only what you're prepared to lose.")
        me(p, "And you're sending me there alone.")
        tiffy(p, "Technically, I'm sending you there discreetly.")
        me(p, "That doesn't make it better.")
        QuestEngine.satisfy(p, this@OldWounds, S_BRIEF) // → FORTRESS: mutate, then the last line
        tiffy(p, "It makes the paperwork considerably easier.")
    }

    private suspend fun QueueTask.remindFortress(p: Player) {
        tiffy(p, "The Dark Warriors' Fortress, dear fellow. North of the<br>ditch, west of the road. Whatever the Kinshra are doing<br>there, they wrote it down. They always do.")
        tiffy(p, "And - discreetly.")
    }

    private suspend fun QueueTask.remindScar(p: Player) {
        tiffy(p, "Survey Site Seven. The old ring of pillars south-east<br>of the Dark Warriors' Fortress, in the lower Wilderness.")
        tiffy(p, "Before the Kinshra, if you'd be so kind.")
    }

    private suspend fun QueueTask.remindRead(p: Player) {
        tiffy(p, "You found something? Then read it, and bring me<br>every word. I'll want the original.")
    }

    /** REPORT_TIFFY: the military half, the strange half, Lucien, the buried records — and the reference. */
    private suspend fun QueueTask.report(p: Player) {
        if (!hasOrders(p)) {
            if (banked(p, ORDERS)) {
                tiffy(p, "You've put the Kinshra's field orders in a BANK?<br>Fetch them. I want the originals in my hands.")
            } else {
                tiffy(p, "You had them and you've lost them?")
                tiffy(p, "Then it's back to that crate in the fortress hall,<br>I'm afraid. Kinshra clerks are nothing if not thorough -<br>there will be another copy.")
            }
            return
        }
        p.message("You hand Sir Tiffy the Kinshra Field Orders. He reads the first part.")
        tiffy(p, "Reserve positions...<br>Supply route...<br>Artillery placements...")
        p.message("He smiles.")
        tiffy(p, "Oh, this is useful.")
        me(p, "Keep reading.")
        p.message("He does. The smile disappears.")
        tiffy(p, "Where did you get this?")
        me(p, "Where you sent me.")
        tiffy(p, "The second section.")
        if (QuestEngine.counter(p, this@OldWounds, C_DAQ_FORTRESS) > 0) {
            me(p, "Daquarius said your people investigated<br>something before Varrock fell.")
        } else {
            me(p, "Someone has the Kinshra digging for old Temple<br>Knight records. About something before Varrock fell.")
        }
        p.message("Silence.")
        var asked = false
        while (!asked) {
            when (options(p, "Who was Lucien?", "What are these old records?", title = TIFFY_NAME)) {
                1 -> {
                    me(p, "Who was Lucien?")
                    tiffy(p, "A Mahjarrat.")
                    me(p, "Like Zemouregal?")
                    tiffy(p, "Yes.<br>Different ambitions.<br>Equally unpleasant consequences.")
                }
                else -> {
                    me(p, "What are these old records?")
                    asked = true
                }
            }
        }
        tiffy(p, "There were Wilderness reports.<br>Old ones.<br>From operations involving Lucien.")
        me(p, "And?")
        tiffy(p, "They were classified.")
        me(p, "You're Temple Knights.<br>That doesn't sound unusual.")
        tiffy(p, "They were classified from us.")
        p.message("Sir Tiffy is quiet for a moment.")
        tiffy(p, "Give me a moment. Bureaucracy is, unfortunately,<br>something I am rather good at.")
        p.message("Sir Tiffy leafs back through his own papers, muttering file references.")
        tiffy(p, "I have it.")
        me(p, "The report?")
        tiffy(p, "A reference to it.")
        tiffy(p, "Survey Site Seven.<br>Southern Wilderness.<br>Field designation...")
        p.message("He pauses.")
        take(p, ORDERS) // Tiffy keeps the orders: they are Asgarnia's now
        QuestEngine.satisfy(p, this@OldWounds, S_REPORT_TIFFY) // → FIRST_SCAR: mutate, then narrate
        tiffy(p, "First Scar.")
        tiffy(p, "The old ring of pillars south-east of the Dark<br>Warriors' Fortress, in the lower Wilderness. Go and<br>look at it - before the Kinshra recover whatever<br>remains there.")
        tiffy(p, "I'll keep these. Sir Amik will want the first half<br>rather badly.")
    }

    /** RETURN: "Oh dear." — the honest uncertainty, the buried authorisation, and the good news. Completes the quest. */
    private suspend fun QueueTask.debrief(p: Player) {
        if (!hasReport(p)) {
            if (banked(p, REPORT)) {
                tiffy(p, "The report is in your BANK? Twelve years under a<br>rock and you've put it in a vault. Fetch it, there's<br>a good fellow.")
            } else {
                tiffy(p, "Lost it? Then it's under that rubble again, I'm afraid.<br>Temple Knights filed in triplicate even in the field.<br>Go and dig.")
            }
            return
        }
        p.message("Sir Tiffy reads the damaged report. The pause is longer this time.")
        tiffy(p, "Oh dear.")
        me(p, "That's not usually what you want your<br>spymaster to say.")
        tiffy(p, "Strictly speaking, I'm not-")
        me(p, "Tiffy.")
        tiffy(p, "Yes.")
        tiffy(p, "Oh dear.")
        me(p, "Is it the same thing that happened at Varrock?")
        tiffy(p, "I don't know.")
        tiffy(p, "Zemouregal attacked Varrock.<br>His dead broke its defences.<br>Arrav was seen among them.")
        tiffy(p, "Those facts haven't changed.")
        p.message("He looks at the report.")
        tiffy(p, "But perhaps something else was happening<br>at the same time.")
        me(p, "Who denied the investigation?")
        tiffy(p, "The authorisation code has been removed.")
        me(p, "Can you find out?")
        tiffy(p, "I intend to.")
        me(p, "And Daquarius?")
        tiffy(p, "Unfortunately...")
        tiffy(p, "...so does he.")
        tiffy(p, "There is one piece of good news.")
        me(p, "Finally.")
        tiffy(p, "Daquarius has been moving experienced troops off<br>the Falador front to conduct these searches.")
        tiffy(p, "And his orders identify his remaining reserves.")
        p.message("Sir Tiffy spreads out the stolen plans.")
        tiffy(p, "For twelve years, we've attacked the Kinshra<br>where they were strongest.")
        me(p, "And now?")
        p.message("Sir Tiffy taps the orders.")
        tiffy(p, "Now we know where they aren't.")
        me(p, "You want to attack.")
        tiffy(p, "Oh, heavens no.")
        me(p, "No?")
        tiffy(p, "I want Sir Amik to attack.")
        QuestEngine.satisfy(p, this@OldWounds, S_RETURN) // completes the quest — mutate, then narrate
        tiffy(p, "I intend to stand somewhere comfortably<br>behind him.")
        tiffy(p, "Keep the report. I have copied every word, and I<br>would rather the original were somewhere the<br>Kinshra don't think to look.")
        tiffy(p, "Sir Amik will send word when Falador is ready<br>to move. It won't be tomorrow.")
    }

    // --- Lord Daquarius ---------------------------------------------------------------------

    /** The everyday branch on the stock npc (a scene's Daquarius, or the Kinshra base's own): never mute. */
    suspend fun QueueTask.daquariusIdle(p: Player) {
        if (QuestEngine.isComplete(p, this@OldWounds)) {
            daq(p, "We've said what needed saying.<br>Tell Tiffy I'm still looking.")
            return
        }
        daq(p, "You're a long way from anywhere you should be.")
        daq(p, "Say what you came to say, or leave.")
    }

    /** Scene 1 — the fortress hall, after the orders are read. Not a fight; a rival who reads. */
    private suspend fun QueueTask.daquariusAtTheFortress(p: Player) {
        LordDaquarius.appear(p, DAQ_FORTRESS_TILE)
        p.message("<col=801700>Someone speaks behind you.</col>")
        daq(p, "You might as well finish reading it.")
        p.message("You turn.")
        me(p, "Lord Daquarius.")
        daq(p, "Good.")
        daq(p, "At least Tiffy tells his agents who they're<br>stealing from.")
        me(p, "Are you going to stop me?")
        daq(p, "If I intended to stop you, we would not be<br>having this conversation.")
        me(p, "That's reassuring.")
        daq(p, "It wasn't intended to be.")
        me(p, "What is the First Scar?")
        daq(p, "If I knew that...")
        daq(p, "...you wouldn't be holding my orders.")
        me(p, "You've been sending men into the Wilderness<br>looking for something you don't understand?")
        daq(p, "You say that as though your own kingdom hasn't<br>spent twelve years doing precisely that.")
        me(p, "This has something to do with Varrock.")
        p.message("Daquarius pauses.")
        daq(p, "Perhaps.")
        me(p, "You know it does.")
        daq(p, "I know there are questions your White Knights<br>stopped asking a long time ago.")
        daq(p, "Before Varrock fell, the Temple Knights investigated<br>disturbances in the Wilderness.")
        daq(p, "Most were dismissed.<br>Some were classified.")
        daq(p, "One was buried so thoroughly that even Tiffy's<br>people seem to have forgotten it.")
        me(p, "And Lucien?")
        daq(p, "His name appears in the surviving records.")
        daq(p, "That alone makes them worth finding.")
        me(p, "So this isn't about attacking Falador.")
        daq(p, "Everything I do is somehow about Falador.")
        daq(p, "But no.<br>Not this.")
        me(p, "Why tell me any of this?")
        daq(p, "Because if I'm wrong...")
        daq(p, "...you've learned nothing useful.")
        me(p, "And if you're right?")
        QuestEngine.addCounter(p, this@OldWounds, C_DAQ_FORTRESS) // the scene has played; Tiffy hears "Daquarius said…"
        daq(p, "Then I would rather Sir Tiffy start paying attention.")
        p.message("<col=801700>Lord Daquarius leaves.</col>")
        LordDaquarius.leave(p, DAQ_FORTRESS_EXIT)
    }

    /** Scene 2 — the First Scar, after the report is read. His men arrive too late; he does not. */
    private suspend fun QueueTask.daquariusAtTheScar(p: Player) {
        LordDaquarius.appear(p, DAQ_SCAR_TILE)
        p.message("<col=801700>Boots on broken stone. You are not alone.</col>")
        daq(p, "So it exists.")
        me(p, "You followed me.")
        daq(p, "No.")
        daq(p, "I followed the same twelve-year-old trail.")
        me(p, "I got here first.")
        daq(p, "Yes.")
        daq(p, "You seem very proud of that.")
        me(p, "You knew this looked like Varrock.")
        daq(p, "I suspected.")
        me(p, "You could have told us.")
        daq(p, "I command the army trying to conquer your kingdom.")
        me(p, "Fair.")
        daq(p, "Does it name a cause?")
        me(p, "No.")
        p.message("Daquarius looks genuinely disappointed.")
        daq(p, "Then we're both still ignorant.")
        me(p, "Why do you care?")
        daq(p, "Because Zemouregal attacked Varrock.<br>Arrav led his dead.<br>Everyone knows that.")
        p.message("He looks at the ruined site.")
        daq(p, "But this happened before either of them<br>reached the city.")
        me(p, "What are you going to do now?")
        daq(p, "Continue looking.")
        me(p, "For what?")
        p.message("Daquarius turns away.")
        QuestEngine.addCounter(p, this@OldWounds, C_DAQ_SCAR)
        daq(p, "The question you should be asking...")
        daq(p, "...is why someone wanted everyone to stop.")
        p.message("<col=801700>Lord Daquarius leaves. His men will be along soon - too late.</col>")
        LordDaquarius.leave(p, DAQ_SCAR_EXIT)
    }
}
