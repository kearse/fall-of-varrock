package org.alter.plugins.content.quests.asgarnia

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.Prerequisite
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.quests.framework.Reward
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM
import java.lang.ref.WeakReference

/**
 * **A Matter of Trolls** — Asgarnia (BREACH), quest 2 (`docs/quests/a-matter-of-trolls.md`): the
 * Northern Front. Sir Amik cannot move the Imperial Guard south while Troll Country is unstable, so
 * the player scouts Death Plateau for Commander Denulth, finds that the trolls themselves are
 * divided, builds a fixed human–troll coalition with **My Arm** and **Snowflake**, and breaks the
 * post-Fall splinter warband in the **Battle of the Pass** ([BattleOfThePass]) — after which the
 * northern frontier is secured and Imperial Guard manpower is free for Falador's war.
 *
 * The lineage breadcrumb lives here: My Arm mistakes the player for the original Adventurer, then
 * realises they are not the same person — "You just... same." No physical description is canonised.
 *
 * A framework quest ([QuestDefinition]) over the existing world: Denulth's stock Burthorpe camp,
 * Death Plateau's stock ground, the stock troll models, a private copy of the plateau for the
 * battle. My Arm and Snowflake are the stock npcs hand-placed (the OSRS wiki spawn dump's ids for
 * both are name-drift skips in this cache, so neither exists in the live world otherwise). No new
 * map, no new mechanic, no custom troll. The design's open detail — the war-chief's final
 * RuneScape-style name — stays open: he is "Troll War-chief" until it is chosen.
 *
 * Gate: **At the White Wall** (`at_the_white_wall`) once it is registered; until then the quest
 * before it on the main road (A Kingdom Alone → First Reclamation → The North → The Last Free
 * City), so the chain never dead-ends while the Asgarnia campaign lands quest by quest. Auto-begins.
 * The Guns of Asgarnia is meant to sit in parallel — nothing here gates on it.
 *
 * Journal varp [QuestJournal.TROLLS_VARP]; native quest-tab row = the relabelled Death Plateau row,
 * varp [QuestJournal.TROLLS_QUEST_VARP]. Wiring (binds, hand-placed npcs, the damage-share kill
 * credit, the temp-spawn sweep, `::trolls`) is in [AMatterOfTrollsPlugin].
 */
object AMatterOfTrolls : QuestDefinition(
    key = "a_matter_of_trolls",
    displayName = "A Matter of Trolls",
    chainIndex = QuestBook.A_MATTER_OF_TROLLS,
    journalVarp = QuestJournal.TROLLS_VARP,
) {
    const val KEY = "a_matter_of_trolls"

    /** Set on completion: the Northern Front is secured and Imperial Guard manpower is available —
     *  the flag the Asgarnian finale (The White Wall) reads alongside The Guns of Asgarnia's. */
    const val NORTHERN_FRONT_FLAG = "asgarnia.northern_front_secured"

    private const val WHITE_WALL_KEY = "at_the_white_wall"
    private val FALLBACK_KEYS = listOf("a_kingdom_alone", "first_reclamation", "the_north", "recruit_trials")

    // ---- npcs (all existing cache npcs) ---------------------------------------------------------

    /** Commander Denulth — his stock Burthorpe spawn (`npc_spawns.json` 4083 @ 2896,3528). */
    const val DENULTH = "npc.denulth"

    /** My Arm (the Making Friends era def, 8411) — hand-placed on the summit of Trollheim. */
    const val MY_ARM = "npc.my_arm_8411"

    /** Snowflake (8431) — hand-placed in Weiss, where the wiki dump puts her. */
    const val SNOWFLAKE = "npc.snowflake"

    /** Sir Amik Varze — the stock Falador castle spawn is id 4771 (2960,3336,2); `npc.sir_amik_varze`
     *  resolves to 1867, which the world never spawns. */
    const val SIR_AMIK = "npc.sir_amik_varze_4771"

    /** The neutral scout: a stock TALKING troll def (Burntmeat, 4157 — Talk-to only, so it can never be
     *  attacked), renamed "Troll scout" at spawn. Quest-only temporary spawn; the plain troll defs
     *  (mountain troll, spectator, Twig/Berry…) carry no Talk-to option in the cache. */
    const val SCOUT_TROLL = "npc.burntmeat"

    /** Burthorpe's soldiers ARE the Imperial Guard — the stock "Soldier" def, used for the forward post
     *  and the battle line. */
    const val SOLDIER = "npc.soldier"

    /** Death Plateau's trolls: the warband, the patrol and My Arm's Stronghold fighters. */
    const val MOUNTAIN_TROLL = "npc.mountain_troll"

    /** Weiss fighters: the stock ice trolls. */
    const val ICE_TROLL_MALE = "npc.ice_troll_male"
    const val ICE_TROLL_FEMALE = "npc.ice_troll_female"

    /** The war-chief: the stock Troll general model, renamed. */
    const val WAR_CHIEF_MODEL = "npc.troll_general"

    // ---- world anchors (existing, unchanged places) ---------------------------------------------

    val DENULTH_TILE = Tile(2896, 3528, 0)

    /** The Trollheim summit — where the Trollheim teleport (spell and tab) already lands. */
    val MY_ARM_TILE = Tile(2891, 3679, 0)
    val TROLLHEIM_LANDING = Tile(2889, 3678, 0)

    val SNOWFLAKE_TILE = Tile(2873, 3934, 0)
    val WEISS_LANDING = Tile(2869, 3936, 0)

    val SIR_AMIK_TILE = Tile(2960, 3336, 2)

    /** Just outside Denulth's tent — quest travel drops here, and the battle instance exits here. */
    val BURTHORPE_CAMP = Tile(2898, 3533, 0)

    /** The Imperial Guard forward post: the ground north of the Warriors' Guild, at the foot of the plateau. */
    val FORWARD_POST = Area(2850, 3564, 2879, 3576)
    val FORWARD_POST_GUARDS = listOf(Tile(2866, 3572, 0), Tile(2870, 3573, 0))

    /** The path up: the western approach to the plateau (tracks cross here). */
    val THE_APPROACH = Area(2835, 3577, 2853, 3583)

    /** Death Plateau proper — the stock troll ground, entered from its north-east gap. */
    val PLATEAU = Area(2851, 3584, 2879, 3600)
    val PLATEAU_CENTRE = Tile(2866, 3592, 0)

    // ---- step ids (persisted strings) ----------------------------------------------------------

    const val START = "start"
    const val SCOUT = "scout"
    const val PATROL = "patrol"
    const val MY_ARM_STEP = "my_arm"
    const val SNOWFLAKE_STEP = "snowflake"
    const val DENULTH_STEP = "denulth"
    const val READY = "ready"
    const val BATTLE = "battle"
    const val WAR_CHIEF = "war_chief"
    const val REPORT = "report"

    const val PATROL_SIZE = 4

    // Scouting observations (quest counters — once each).
    private const val OBS_POST = "obs_post"
    private const val OBS_APPROACH = "obs_approach"

    /** Tags the splinter patrol (open world, per player). */
    val PATROL_TROLL = AttributeKey<Boolean>()

    /** Tags the neutral scout (open world, per player). */
    val SCOUT_NPC = AttributeKey<Boolean>()

    private const val PATROL_TTL_SWEEPS = 60  // ~10 min at the plugin's 10-tick sweep
    private const val SCOUT_TTL_SWEEPS = 180  // ~30 min

    // ---- journal (docs/quests/a-matter-of-trolls.md §49) ---------------------------------------

    private const val J_START = "Sir Amik says Imperial Guard soldiers are tied down defending Burthorpe. Speak with Commander Denulth in Burthorpe."
    private const val J_SCOUT = "Scout Death Plateau and investigate the changing troll attacks."
    private const val J_PATROL = "Defeat the hostile troll patrol."
    private const val J_MY_ARM = "Find My Arm around Troll Stronghold and ask about the splinter warband."
    private const val J_SNOWFLAKE = "Speak with Snowflake in Weiss."
    private const val J_DENULTH = "Arrange safe passage for the allied trolls with Denulth."
    private const val J_READY = "Tell Denulth when you are ready for the Battle of the Pass."
    private const val J_BATTLE = "Fight beside the Imperial Guard and allied trolls — hold the pass."
    private const val J_WAR_CHIEF = "Defeat the troll War-chief and break the splinter warband."
    private const val J_REPORT = "The hostile troll warband has been broken and the northern frontier is stable. Report to Sir Amik in Falador."
    const val J_DONE = "The northern frontier is stable. Imperial Guard manpower can now reinforce Falador's war against the Kinshra."

    override val prerequisites: List<Prerequisite> = listOf(
        Prerequisite.Custom("finish At the White Wall (the quest before it on the main road until that lands)") { p -> previousQuestDone(p) },
    )

    /** Begins the moment At the White Wall ends — Sir Amik's last line sends the player to Burthorpe. */
    override val autoBegin: Boolean = true

    override val nativeTabVarp: Int = QuestJournal.TROLLS_QUEST_VARP
    override val nativeTabComplete: Int = QuestJournal.TROLLS_QUEST_COMPLETE
    override val questPoints: Int = 2

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            START, Objective.TalkTo(J_START, DENULTH),
            anchor = DENULTH_TILE, anchorNpc = DENULTH,
            nudge = "Burthorpe is north of Falador, past Taverley. Denulth commands the Imperial Guard camp in the north-west of the town.",
        ),
        QuestStep(
            SCOUT, Objective.Predicate(J_SCOUT) { p -> scoutPoll(p) },
            anchor = PLATEAU_CENTRE,
            nudge = "Death Plateau is north-west of Burthorpe: past the Warriors' Guild, up the western path and around onto the top. Read the ground as you go.",
        ),
        QuestStep(
            PATROL, Objective.KillNpcs(J_PATROL, count = PATROL_SIZE, filter = { p, npc -> isPatrolOf(p, npc) }),
            anchor = PLATEAU_CENTRE,
            onEnter = { p -> spawnPatrol(p) },
            nudge = "Only the warband trolls count — the ones that came for you. Every hit you land is credited, whoever finishes them.",
        ),
        QuestStep(
            MY_ARM_STEP, Objective.TalkTo(J_MY_ARM, MY_ARM),
            anchor = MY_ARM_TILE, anchorNpc = MY_ARM,
            onEnter = { p -> patrolBroken(p) },
            nudge = "My Arm waits on the summit of Trollheim, above the Stronghold. The troll scout will walk you up; a Trollheim teleport lands there too.",
        ),
        QuestStep(
            SNOWFLAKE_STEP, Objective.TalkTo(J_SNOWFLAKE, SNOWFLAKE),
            anchor = SNOWFLAKE_TILE, anchorNpc = SNOWFLAKE,
            nudge = "Weiss is far to the north. My Arm's trolls will walk you there — ask him.",
        ),
        QuestStep(
            DENULTH_STEP, Objective.TalkTo(J_DENULTH, DENULTH),
            anchor = DENULTH_TILE, anchorNpc = DENULTH,
            nudge = "Snowflake's trolls will see you down to Burthorpe — ask her.",
        ),
        QuestStep(
            READY, Objective.TalkTo(J_READY, DENULTH),
            anchor = DENULTH_TILE, anchorNpc = DENULTH,
            nudge = "Bring food and your best gear. The pass is a real fight — the coalition does not win it for you.",
        ),
        QuestStep(
            BATTLE, Objective.KillNpcs(J_BATTLE, count = BattleOfThePass.PLAYER_KILLS, filter = { p, npc -> BattleOfThePass.isHostileOf(p, npc) }),
            onEnter = { p -> BattleOfThePass.open(p) },
            nudge = "Hold with the Imperial Guard until My Arm and Snowflake arrive. Any warband troll you have hit counts when it falls.",
        ),
        QuestStep(
            WAR_CHIEF, Objective.Manual(J_WAR_CHIEF),
            nudge = "He shows himself once the warband is thinned. The whole coalition can hurt him.",
        ),
        QuestStep(
            REPORT, Objective.TalkTo(J_REPORT, SIR_AMIK),
            anchor = SIR_AMIK_TILE, anchorNpc = SIR_AMIK,
            nudge = "Sir Amik is in Falador castle.",
        ),
    )

    override val completionRewards: List<Reward> = listOf(
        Reward.WarEffort(50),
        Reward.Flag(NORTHERN_FRONT_FLAG),
    )

    override val completionMessage: String = "<col=801700>$J_DONE</col>"

    /** The strategic update (spec §47). BREACH itself stays incomplete until the Asgarnian finale. */
    override fun onComplete(p: Player) {
        p.message("<col=801700>ASGARNIA — BREACH.</col> Northern Frontier: <col=4f9b4f>SECURED</col>. Imperial Guard Manpower: <col=4f9b4f>AVAILABLE</col>.")
        p.message("Artillery Production: unresolved. Kinshra Front: active. Temple Knight Intelligence: unresolved. BREACH: incomplete.")
        p.message("The other half of what Falador needs before it can commit east is its guns — <col=801700>The Guns of Asgarnia</col>.")
    }

    // ---- gate / status ---------------------------------------------------------------------------

    /** At the White Wall once it exists in the registry, else the deepest registered main-road quest before it. */
    private fun previousQuestDone(p: Player): Boolean {
        QuestRegistry.byKey(WHITE_WALL_KEY)?.let { return it.complete(p) }
        for (key in FALLBACK_KEYS) {
            QuestRegistry.byKey(key)?.let { return it.complete(p) }
        }
        return false
    }

    private fun stepIndex(p: Player): Int = QuestEngine.stepId(p, this)?.let { indexOf(it) } ?: -1

    /** True once the player is on [stepId] or past it (or has finished the quest). */
    private fun reached(p: Player, stepId: String): Boolean =
        QuestEngine.isComplete(p, this) || stepIndex(p) >= indexOf(stepId)

    /** One-line status (`::trolls`). */
    fun statusLine(p: Player): String = when {
        QuestEngine.isComplete(p, this) -> "<col=801700>A Matter of Trolls:</col> complete. $J_DONE"
        QuestEngine.started(p, this) -> "<col=801700>A Matter of Trolls — current objective:</col> ${QuestEngine.objectiveLine(p, this)}"
        else -> "<col=801700>A Matter of Trolls:</col> not started — finish At the White Wall and Sir Amik sends you to Burthorpe."
    }

    // ---- scouting (the SCOUT predicate: three observations, area-triggered) --------------------

    private fun scoutPoll(p: Player): Boolean {
        val t = p.tile
        if (t.height != 0) return false
        if (FORWARD_POST.contains(t) && QuestEngine.counter(p, this, OBS_POST) == 0) {
            QuestEngine.addCounter(p, this, OBS_POST)
            p.message("<col=801700>The Imperial Guard position shows signs of repeated troll attacks.</col>")
            p.message("Splintered stakes. A burned supply crate. Two graves — one of them fresh.")
            p.message("<col=801700>The damage is recent.</col>")
        }
        if (THE_APPROACH.contains(t) && QuestEngine.counter(p, this, OBS_APPROACH) == 0) {
            QuestEngine.addCounter(p, this, OBS_APPROACH)
            p.message("<col=801700>Several sets of troll tracks cross the pass from different directions.</col>")
            p.message("<col=801700>The tracks show signs of fighting before reaching the human position.</col>")
            p.message("Some of the blood on the rocks here is troll blood.")
        }
        if (!PLATEAU.contains(t)) return false
        p.message("<col=801700>Death Plateau.</col> The tracks converge here — and something is moving between the rocks.")
        return true
    }

    // ---- the splinter patrol (PATROL) ------------------------------------------------------------

    fun isPatrolOf(p: Player, npc: Npc): Boolean = npc.attr[PATROL_TROLL] == true && TempSpawns.ownedBy(npc, p)

    private fun spawnPatrol(p: Player) {
        val world = p.world
        val id = runCatching { getRSCM(MOUNTAIN_TROLL) }.getOrNull() ?: run {
            p.message("<col=801700>No troll patrol could muster here — ::questdebug set $KEY $PATROL re-tries it.</col>")
            return
        }
        p.message("<col=801700>Trolls! A patrol breaks from the rocks and comes straight for you.</col>")
        val offsets = listOf(3 to 3, -3 to 3, 4 to -1, -4 to -2)
        for ((dx, dz) in offsets) {
            val tile = world.snapToWalkable(p.tile.transform(dx, dz), maxRadius = 5)
            val npc = Npc(id, tile, world)
            world.spawn(npc)
            // After world.spawn: setNpcDefaults resets combatDef/hp/respawns on spawn.
            npc.respawns = false
            npc.walkRadius = 3
            npc.routeLogic = 1
            npc.setActive(true)
            WarNpcNames.rename(npc, "Warband troll")
            npc.combatDef = npc.combatDef.copy(aggressiveRadius = 12, aggroTargetDelay = 4, aggressiveTimer = 500)
            npc.aggroCheck = { _, pl -> pl === p } // dead-simple: the engine's aggro path must never throw
            npc.attr[PATROL_TROLL] = true
            TempSpawns.track(npc, p, PATROL_TTL_SWEEPS)
            npc.attack(p)
        }
    }

    /** MY_ARM entered: the patrol is down — the survivor steps out, and the conversation starts itself. */
    private fun patrolBroken(p: Player) {
        p.message("<col=801700>These trolls were not attacking from the main Troll Stronghold force.</col>")
        TempSpawns.removeOwnedBy(p) { it.attr[PATROL_TROLL] == true }
        ensureScout(p)
        p.queue {
            wait(2)
            p.message("A troll steps out from behind the rocks. It does not attack.")
            with(AMatterOfTrolls) { scoutMeets(p) }
        }
    }

    /** Spawn the neutral scout beside [p] if they have none alive (also Denulth's re-issue on the MY_ARM step). */
    fun ensureScout(p: Player) {
        if (TempSpawns.any(p) { it.attr[SCOUT_NPC] == true }) return
        val world = p.world
        val id = runCatching { getRSCM(SCOUT_TROLL) }.getOrNull() ?: return
        val tile = world.snapToWalkable(p.tile.transform(2, 2), maxRadius = 5)
        val npc = Npc(id, tile, world)
        world.spawn(npc)
        npc.respawns = false
        npc.walkRadius = 0
        npc.setActive(true)
        WarNpcNames.rename(npc, "Troll scout")
        npc.aggroCheck = { _, _ -> false }
        npc.attr[SCOUT_NPC] = true
        npc.faceTile(p.tile)
        TempSpawns.track(npc, p, SCOUT_TTL_SWEEPS)
    }

    // ---- dialogue helpers ------------------------------------------------------------------------

    private fun id(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1)

    private suspend fun QueueTask.den(p: Player, text: String) = chatNpc(p, text, npc = id(DENULTH), title = "Denulth")
    private suspend fun QueueTask.arm(p: Player, text: String) = chatNpc(p, text, npc = id(MY_ARM), title = "My Arm")
    private suspend fun QueueTask.snow(p: Player, text: String) = chatNpc(p, text, npc = id(SNOWFLAKE), title = "Snowflake")
    private suspend fun QueueTask.amik(p: Player, text: String) = chatNpc(p, text, npc = id(SIR_AMIK), title = "Sir Amik Varze")
    private suspend fun QueueTask.troll(p: Player, text: String) = chatNpc(p, text, npc = id(SCOUT_TROLL), title = "Troll")
    private suspend fun QueueTask.me(p: Player, text: String) = chatPlayer(p, text)

    init {
        // Denulth
        talk(DENULTH, START) { p -> denulthStart(p) }
        talk(DENULTH, SCOUT) { p -> den(p, "The plateau's north-west of town — past the Warriors' Guild, up the western path and around onto the top. Read the ground on your way. Come back with something I can act on.") }
        talk(DENULTH, PATROL) { p -> den(p, "Trolls on the plateau? Deal with them. That's the job.") }
        talk(DENULTH, MY_ARM_STEP) { p -> denulthMyArm(p) }
        talk(DENULTH, SNOWFLAKE_STEP) { p -> den(p, "Weiss? That's the far side of the mountains. Whatever the troll told you, follow it through. I'll hold here.") }
        talk(DENULTH, DENULTH_STEP) { p -> denulthProposal(p) }
        talk(DENULTH, READY) { p -> denulthReady(p) }
        talk(DENULTH, BATTLE) { p -> denulthBackUp(p) }
        talk(DENULTH, WAR_CHIEF) { p -> denulthBackUp(p) }
        talk(DENULTH, REPORT) { p -> den(p, "The passes are quiet. Amik will want to hear it from you, not from my report. Falador — the castle.") }

        // My Arm
        talk(MY_ARM, MY_ARM_STEP) { p -> myArmRecognition(p) }
        talk(MY_ARM, SNOWFLAKE_STEP) { p -> arm(p, "Snowflake in Weiss. Go ask. My Arm's friends walk you."); offerWeiss(p) }
        for (s in listOf(DENULTH_STEP, READY, BATTLE, WAR_CHIEF)) talk(MY_ARM, s) { p -> myArmCoalition(p) }
        talk(MY_ARM, REPORT) { p -> myArmAfter(p) }

        // Snowflake
        talk(SNOWFLAKE, SNOWFLAKE_STEP) { p -> snowflakeMeets(p) }
        for (s in listOf(DENULTH_STEP, READY, BATTLE, WAR_CHIEF)) talk(SNOWFLAKE, s) { p -> snowflakeWaiting(p) }
        talk(SNOWFLAKE, REPORT) { p -> snowflakeAfter(p) }

        // Sir Amik — quest-priority branches on THIS quest's steps only (At the White Wall owns his idle lines).
        talk(SIR_AMIK, START) { p -> amik(p, "Burthorpe. Commander Denulth commands the Imperial Guard there. I sent word ahead — he'll be expecting you.") }
        for (s in listOf(SCOUT, PATROL, MY_ARM_STEP, SNOWFLAKE_STEP, DENULTH_STEP, READY, BATTLE, WAR_CHIEF)) {
            talk(SIR_AMIK, s) { p -> amik(p, "Denulth's report hasn't reached me. Whatever is happening in the north, finish it.") }
        }
        talk(SIR_AMIK, REPORT) { p -> amikDebrief(p) }

        // The scout troll (quest-only spawn on a stock talking-troll def).
        talk(SCOUT_TROLL, MY_ARM_STEP) { p -> scoutMeets(p) }
    }

    // ---- Denulth ---------------------------------------------------------------------------------

    /** START: "Lumbridge." The problem, the split, and the job (spec §6-9). */
    private suspend fun QueueTask.denulthStart(p: Player) {
        den(p, "Lumbridge.")
        me(p, "Does everyone know where I'm from?")
        den(p, "You're carrying their business halfway across Gielinor.")
        den(p, "People notice.")
        me(p, "Sir Amik sent me.")
        den(p, "Of course he did.")
        me(p, "He needs soldiers.")
        den(p, "So do I.")
        me(p, "That seems to be a theme.")
        den(p, "Welcome to Asgarnia.")
        den(p, "We've kept the mountain passes contained for years.")
        den(p, "Recently the raids changed.")
        me(p, "How?")
        den(p, "More organized.")
        den(p, "More aggressive.")
        den(p, "And stranger.")
        me(p, "Stranger how?")
        den(p, "Some of the dead trolls aren't ours.")
        me(p, "They're fighting each other?")
        den(p, "Looks that way.")
        den(p, "But I'm not sending men deeper into Troll Country based on tracks and corpses.")
        me(p, "So you're sending me.")
        QuestEngine.satisfy(p, this@AMatterOfTrolls, START) // mutate, then narrate
        den(p, "You learn quickly.")
        den(p, "Death Plateau. North-west of town — past the Warriors' Guild, up the western path and around onto the top. My forward post sits at the foot of it. Find out who is behind these raids.")
    }

    /** MY_ARM: Denulth has heard the name; re-issues the scout if the player lost theirs. */
    private suspend fun QueueTask.denulthMyArm(p: Player) {
        den(p, "My Arm.")
        den(p, "...The troll. I've heard the name. Grows things on the Stronghold roof, or did.")
        den(p, "If one of the talking trolls will walk you up the mountain, take the offer. The path from the plateau to the Stronghold needs climbing boots and a death wish.")
        if (!TempSpawns.any(p) { it.attr[SCOUT_NPC] == true }) {
            ensureScout(p)
            den(p, "One of them followed you down, by the look of it. It's waiting outside.")
        }
    }

    /** DENULTH: the proposal, the agreement, the battle plan (spec §30-33). */
    private suspend fun QueueTask.denulthProposal(p: Player) {
        me(p, "I found your problem.")
        den(p, "I was hoping you'd kill it.")
        me(p, "More complicated than that.")
        den(p, "It usually is.")
        me(p, "The warband is attacking other trolls too.")
        me(p, "There are trolls willing to help us stop them.")
        p.message("Denulth stares.")
        den(p, "You want me to bring trolls into an Imperial Guard position.")
        me(p, "Yes.")
        den(p, "Deliberately.")
        me(p, "Yes.")
        den(p, "...")
        den(p, "Amik is going to blame me for this.")
        den(p, "If they attack the warband from the mountain side...")
        den(p, "...and we hold the southern pass...")
        me(p, "They're trapped.")
        den(p, "Or we are.")
        me(p, "You're very encouraging.")
        den(p, "Occupational habit.")
        den(p, "Fine. The order goes out: no Imperial Guard raises a weapon at a troll coming down that mountain under My Arm or Snowflake. If it costs me men, it's on your head.")
        den(p, "The plan. The Guard holds the pass — the way down off the plateau to Burthorpe. That's the anvil.")
        den(p, "My Arm's Stronghold trolls hit their flank from the mountain side. Snowflake's Weiss fighters take the high ground and cut off the retreat.")
        den(p, "You fight wherever the line is weakest. And when their war-chief shows himself, you put him down.")
        QuestEngine.satisfy(p, this@AMatterOfTrolls, DENULTH_STEP) // mutate, then narrate
        den(p, "Get whatever you need. Food. Gear. Then tell me you're ready, and we go up together.")
    }

    /** READY: opens the Battle of the Pass — the satisfy is the LAST thing, the instance moves the player. */
    private suspend fun QueueTask.denulthReady(p: Player) {
        den(p, "Ready for the pass? Once we go up, we hold it or we don't come back down.")
        when (options(p, "I'm ready. Let's go.", "Not yet.", title = "Denulth")) {
            1 -> {
                me(p, "I'm ready. Let's go.")
                den(p, "Then let's not keep the trolls waiting.")
                QuestEngine.satisfy(p, this@AMatterOfTrolls, READY)
            }
            else -> {
                me(p, "Not yet.")
                den(p, "Don't take long. He's gathering trolls while we talk.")
            }
        }
    }

    /** BATTLE / WAR_CHIEF outside the instance (died, left, timed out, logged out): back up the pass. */
    private suspend fun QueueTask.denulthBackUp(p: Player) {
        den(p, "The pass isn't held yet. The Guard's still up there — and so are the trolls. Ready to go back up?")
        when (options(p, "Take me back up.", "Not yet.", title = "Denulth")) {
            1 -> {
                me(p, "Take me back up.")
                QuestEngine.advanceTo(p, this@AMatterOfTrolls, BATTLE)
            }
            else -> me(p, "Not yet.")
        }
    }

    /** Denulth's everyday lines — no quest branch claimed the click. */
    suspend fun QueueTask.denulthIdle(p: Player) {
        if (QuestEngine.isComplete(p, this@AMatterOfTrolls)) {
            den(p, "Quiet. For now.")
            me(p, "For now is enough?")
            den(p, "Amik's words. I'll take them.")
            den(p, "That many trolls coming down the mountain... I still don't like it. But it worked.")
            return
        }
        den(p, "The Imperial Guard holds Burthorpe. If Sir Amik has business with me, it comes through Falador first.")
    }

    // ---- the scout troll ---------------------------------------------------------------------------

    /** The split, explained simply, and the name: "My Arm." (spec §12-14). Offers the climb. */
    suspend fun QueueTask.scoutMeets(p: Player) {
        troll(p, "You kill bad trolls.")
        me(p, "That's what I was hoping.")
        troll(p, "Bad trolls say all trolls fight humans.")
        me(p, "And you don't?")
        troll(p, "Sometimes.")
        me(p, "Comforting.")
        troll(p, "But not today.")
        troll(p, "War trolls say humans weak.")
        troll(p, "Say trolls what talk to humans are weak too.")
        troll(p, "They fight everyone.")
        me(p, "Who leads them?")
        troll(p, "Big war-chief.")
        me(p, "Who can stop him?")
        p.message("The troll thinks.")
        troll(p, "My Arm.")
        me(p, "Your arm?")
        troll(p, "No.")
        troll(p, "My Arm.")
        me(p, "...Right.")
        troll(p, "My Arm on top of mountain. Above big troll house. Thinking.")
        offerClimb(p)
    }

    /** The scout's lines when no quest branch claimed the click — it keeps offering the climb. */
    suspend fun QueueTask.scoutIdle(p: Player) {
        if (reached(p, MY_ARM_STEP)) {
            troll(p, "My Arm up mountain. Trolls know way.")
            offerClimb(p)
            return
        }
        troll(p, "Grr.")
    }

    private suspend fun QueueTask.offerClimb(p: Player) {
        when (options(p, "Take me to My Arm.", "I'll find my own way.", title = "Troll")) {
            1 -> {
                me(p, "Take me to My Arm.")
                p.message("<col=801700>The troll leads you up the mountain paths — past the Stronghold and onto the summit of Trollheim.</col>")
                p.moveTo(TROLLHEIM_LANDING)
            }
            else -> {
                me(p, "I'll find my own way.")
                troll(p, "Mountain path need climbing boots. Trolls know other way. Ask again.")
            }
        }
    }

    // ---- My Arm ------------------------------------------------------------------------------------

    /** MY_ARM: the recognition scene, the warband, the ask (spec §16-24). Sends the player to Weiss. */
    private suspend fun QueueTask.myArmRecognition(p: Player) {
        p.message("My Arm stares.")
        arm(p, "You.")
        arm(p, "...")
        me(p, "Me?")
        arm(p, "You come back.")
        me(p, "I don't think we've met.")
        arm(p, "Yes we has.")
        arm(p, "You help My Arm.")
        arm(p, "You bring goutweed.")
        arm(p, "You go snow place.")
        arm(p, "You help Snowflake.")
        p.message("My Arm looks genuinely happy.")
        arm(p, "You is Adventurer.")
        me(p, "No.")
        me(p, "My name is ${p.username}.")
        p.message("My Arm looks again. Longer this time.")
        arm(p, "...No.")
        p.message("He steps closer.")
        arm(p, "You not Adventurer.")
        me(p, "That's what I said.")
        arm(p, "But you is like Adventurer.")
        me(p, "Who was this Adventurer?")
        arm(p, "Human.")
        me(p, "That narrows it down.")
        p.message("My Arm ignores the joke.")
        arm(p, "Good human.")
        arm(p, "Help My Arm grow goutweed.")
        arm(p, "Help My Arm go Weiss.")
        arm(p, "Help Snowflake.")
        arm(p, "Help My Arm marry Snowflake.")
        me(p, "They did all that?")
        arm(p, "Yes.")
        me(p, "Why did you think I was them?")
        p.message("My Arm struggles to explain.")
        arm(p, "My Arm not know.")
        arm(p, "You just... same.")
        me(p, "That's slightly unsettling.")
        arm(p, "You less wrinkly.")
        me(p, "Thanks.")
        arm(p, "Probably.")
        me(p, "There's a troll warband attacking Burthorpe.")
        arm(p, "My Arm knows.")
        me(p, "They're attacking other trolls too.")
        arm(p, "Yes.")
        arm(p, "Stupid trolls.")
        me(p, "That sounds judgmental.")
        arm(p, "They very stupid.")
        arm(p, "After Varrock fall, human patrols go away. Some trolls learn: talk to humans, trade with humans, life easier.")
        arm(p, "Other trolls say: humans weak now. Kingdom busy. Time to raid south.")
        arm(p, "War-chief spend years collecting stupid trolls. Say humans weak. Say trolls who talk to humans weaker.")
        arm(p, "Warband hurt Burthorpe. Hurt Stronghold. Hurt Weiss. Hurt trade. Hurt trolls who not want war forever.")
        me(p, "So you're going to help Burthorpe?")
        arm(p, "No.")
        me(p, "Oh.")
        arm(p, "Burthorpe help My Arm.")
        me(p, "That's going to be a difficult conversation.")
        arm(p, "You do conversation.")
        me(p, "Of course I do.")
        arm(p, "Need more trolls. Snowflake has strong trolls.")
        me(p, "You want me to go ask your wife for an army?")
        arm(p, "Yes.")
        me(p, "You're not coming?")
        arm(p, "My Arm busy.")
        me(p, "Doing what?")
        arm(p, "Thinking.")
        QuestEngine.satisfy(p, this@AMatterOfTrolls, MY_ARM_STEP) // mutate, then narrate
        arm(p, "Weiss far. Cold. My Arm's friends walk you. Go now?")
        offerWeiss(p)
    }

    /** DENULTH / READY / BATTLE / WAR_CHIEF: he is thinking; travel either way. */
    private suspend fun QueueTask.myArmCoalition(p: Player) {
        arm(p, "You do conversation with human commander?")
        me(p, "Working on it.")
        arm(p, "My Arm thinking. Go.")
        offerTravel(p, from = "My Arm", toWeiss = true, toBurthorpe = true, toTrollheim = false)
    }

    /** REPORT: the battle is won. */
    private suspend fun QueueTask.myArmAfter(p: Player) {
        arm(p, "Stupid trolls run. Pass quiet.")
        arm(p, "You go tell human king-man. Then come back. My Arm has thinking to show you.")
        offerTravel(p, from = "My Arm", toWeiss = true, toBurthorpe = true, toTrollheim = false)
    }

    /** My Arm's everyday lines — no quest branch claimed the click. */
    suspend fun QueueTask.myArmIdle(p: Player) {
        if (QuestEngine.isComplete(p, this@AMatterOfTrolls)) {
            arm(p, "You come back.")
            me(p, "I came back.")
            arm(p, "Good.")
            arm(p, "Snowflake say 'later'. My Arm still waiting for later.")
            offerTravel(p, from = "My Arm", toWeiss = true, toBurthorpe = true, toTrollheim = false)
            return
        }
        arm(p, "My Arm busy.")
        me(p, "Doing what?")
        arm(p, "Thinking.")
    }

    private suspend fun QueueTask.offerWeiss(p: Player) {
        when (options(p, "Send me to Weiss.", "Not yet.", title = "My Arm")) {
            1 -> {
                me(p, "Send me to Weiss.")
                p.message("<col=801700>My Arm's trolls walk you north over the ice, to Weiss.</col>")
                p.moveTo(WEISS_LANDING)
            }
            else -> {
                me(p, "Not yet.")
                arm(p, "Snowflake waiting. Snowflake not like waiting.")
            }
        }
    }

    // ---- Snowflake ---------------------------------------------------------------------------------

    /** SNOWFLAKE: recognition, the danger, the condition (spec §26-29). Sends the player back to Denulth. */
    private suspend fun QueueTask.snowflakeMeets(p: Player) {
        snow(p, "My Arm said a human was coming.")
        p.message("She looks at you.")
        snow(p, "Oh.")
        me(p, "What?")
        snow(p, "Nothing.")
        me(p, "Everyone keeps doing that.")
        when (options(p, "My Arm thought I was someone else.", "There's a troll warband attacking Burthorpe.", title = "Snowflake")) {
            1 -> {
                me(p, "My Arm thought I was someone else.")
                snow(p, "I can see why.")
                me(p, "Who were they?")
                snow(p, "A friend.")
                snow(p, "A very strange one.")
                me(p, "That seems to be the only kind I hear about.")
                snow(p, "You did not come all this way to talk about old friends.")
            }
            else -> {}
        }
        me(p, "There's a troll warband attacking Burthorpe. My Arm says you have strong trolls.")
        snow(p, "He attacks Weiss scouts.")
        snow(p, "He attacks Stronghold trolls.")
        snow(p, "He attacks humans.")
        me(p, "He seems committed.")
        snow(p, "He is building one large warband.")
        snow(p, "If he wins the pass, more trolls join him.")
        me(p, "So you'll send fighters?")
        snow(p, "On one condition.")
        snow(p, "Humans see trolls.")
        snow(p, "Humans shoot trolls.")
        me(p, "Historically, that hasn't been completely unreasonable.")
        snow(p, "Today it is.")
        snow(p, "Burthorpe must agree: allied trolls entering the pass will not be attacked by the Imperial Guard. Get me that, and Weiss marches.")
        QuestEngine.satisfy(p, this@AMatterOfTrolls, SNOWFLAKE_STEP) // mutate, then narrate
        snow(p, "Go and tell the human commander. My trolls will see you down the mountain.")
        offerTravel(p, from = "Snowflake", toWeiss = false, toBurthorpe = true, toTrollheim = true)
    }

    /** DENULTH / READY / BATTLE / WAR_CHIEF: does Burthorpe agree? */
    private suspend fun QueueTask.snowflakeWaiting(p: Player) {
        snow(p, "Does Burthorpe agree?")
        me(p, "I'm working on it.")
        snow(p, "Then work faster. He is gathering trolls.")
        offerTravel(p, from = "Snowflake", toWeiss = false, toBurthorpe = true, toTrollheim = true)
    }

    /** REPORT: after the pass. */
    private suspend fun QueueTask.snowflakeAfter(p: Player) {
        snow(p, "The pass is ours. Tell your commander Weiss keeps its word.")
        offerTravel(p, from = "Snowflake", toWeiss = false, toBurthorpe = true, toTrollheim = true)
    }

    /** Snowflake's everyday lines — no quest branch claimed the click. */
    suspend fun QueueTask.snowflakeIdle(p: Player) {
        if (QuestEngine.isComplete(p, this@AMatterOfTrolls)) {
            snow(p, "You fought well.")
            me(p, "Thank you.")
            snow(p, "My Arm talks about the Adventurer again.")
            snow(p, "Later, I told him.")
            offerTravel(p, from = "Snowflake", toWeiss = false, toBurthorpe = true, toTrollheim = true)
            return
        }
        snow(p, "You are a long way from home, human.")
    }

    /** The quest's travel: an existing-infrastructure teleport offered in dialogue (the design's own fallback for Weiss). */
    private suspend fun QueueTask.offerTravel(p: Player, from: String, toWeiss: Boolean, toBurthorpe: Boolean, toTrollheim: Boolean) {
        val choices = ArrayList<Pair<String, Tile?>>()
        if (toWeiss) choices += "Send me to Weiss." to WEISS_LANDING
        if (toBurthorpe) choices += "Walk me down to Burthorpe." to BURTHORPE_CAMP
        if (toTrollheim) choices += "Take me to My Arm on Trollheim." to TROLLHEIM_LANDING
        choices += "I'll stay a while." to null
        val pick = options(p, *choices.map { it.first }.toTypedArray(), title = from)
        val (line, dest) = choices.getOrNull(pick - 1) ?: return
        me(p, line)
        if (dest == null) return
        val narration = when (dest) {
            WEISS_LANDING -> "The trolls walk you north over the ice, to Weiss."
            BURTHORPE_CAMP -> "The trolls see you down the mountain to the Imperial Guard camp at Burthorpe."
            else -> "The trolls lead you over the mountain paths to the summit of Trollheim."
        }
        p.message("<col=801700>$narration</col>")
        p.moveTo(dest)
    }

    // ---- Sir Amik ----------------------------------------------------------------------------------

    /** REPORT: the debrief and the strategic payoff (spec §45-46). Completes the quest. */
    private suspend fun QueueTask.amikDebrief(p: Player) {
        amik(p, "Denulth says the passes are quiet.")
        me(p, "For now.")
        amik(p, "For now is enough.")
        me(p, "What happens to the Imperial Guard?")
        amik(p, "They stop spending every waking hour staring north.")
        amik(p, "We'll leave enough men to hold Burthorpe.")
        amik(p, "The rest can move south.")
        me(p, "To the Kinshra front?")
        amik(p, "Yes.")
        amik(p, "Not all at once.")
        QuestEngine.satisfy(p, this@AMatterOfTrolls, REPORT) // completes the quest — mutate, then narrate
        amik(p, "But for the first time in years...")
        amik(p, "...I have soldiers I can move.")
    }
}

/**
 * Per-player temporary open-world spawns for this quest (the splinter patrol, the troll scout):
 * owner-tagged, time-limited, swept by [AMatterOfTrollsPlugin] every few ticks. Never respawns,
 * never outlives its owner's session.
 */
object TempSpawns {
    val OWNER = AttributeKey<WeakReference<Player>>()
    private val EXPIRES = AttributeKey<Int>()

    private val live = ArrayList<Npc>()
    private var sweeps = 0

    fun track(npc: Npc, owner: Player, ttlSweeps: Int) {
        npc.attr[OWNER] = WeakReference(owner)
        npc.attr[EXPIRES] = sweeps + ttlSweeps
        live += npc
    }

    fun ownedBy(npc: Npc, p: Player): Boolean = npc.attr[OWNER]?.get() === p

    fun any(p: Player, filter: (Npc) -> Boolean): Boolean =
        live.any { it.index >= 0 && !it.isDead() && ownedBy(it, p) && filter(it) }

    fun removeOwnedBy(p: Player, filter: (Npc) -> Boolean) {
        val it = live.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (!ownedBy(n, p) || !filter(n)) continue
            if (n.index >= 0 && n.world.npcs.contains(n)) n.world.remove(n)
            it.remove()
        }
    }

    /** Drop dead/removed npcs from the list; remove expired ones and those whose owner is gone. */
    fun sweep(world: World) {
        sweeps++
        if (live.isEmpty()) return
        val it = live.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.index < 0 || !world.npcs.contains(n)) { it.remove(); continue }
            val owner = n.attr[OWNER]?.get()
            val expired = (n.attr[EXPIRES] ?: 0) <= sweeps
            if (expired || owner == null || !owner.isOnline) {
                world.remove(n)
                it.remove()
            }
        }
    }
}
