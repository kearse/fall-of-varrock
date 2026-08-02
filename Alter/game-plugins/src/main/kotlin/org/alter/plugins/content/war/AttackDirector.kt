package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.game.info.NpcInfo
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import kotlin.math.abs
import kotlin.math.max
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.isAttacking
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The brain that runs one city's defense as a series of **AI-commanded raids** - the
 * top of the War Brain stack (`docs/war-system-design.md`). One per [SiegeConfig].
 *
 * Lifecycle per front: **PEACE** (quiet; the knight pool replenishes; a random countdown
 * to the next raid) â†’ **UNDER_RAID** (a finite tiered goblin roster that the two
 * [Commander]s maneuver across the fields, drawing from a shared reserve / the 100-knight
 * pool) â†’ resolve:
 *  - **defenders win** (roster wiped) â†’ survivors return to the pool, contributors get
 *    rare loot ([RaidRewards]) â†’ PEACE.
 *  - **goblins win** (they breach to General Zo at the castle goal) â†’ **CITY_FALLEN**: a
 *    lasting penalty until recovery â†’ PEACE.
 *  - **timeout** â†’ the horde withdraws (a draw) â†’ PEACE.
 *
 * Each tick it: builds the live [BattleAssessment] (L0) â†’ picks postures (L1, [WarBrain])
 * â†’ sets each commander's focus field and allocates (L2) â†’ spawns from the finite supply
 * â†’ drives combat with posture-weighted targeting (L3) â†’ resolves.
 */
class AttackDirector(
    val config: SiegeConfig,
    private val fields: List<WarFront>,
    private val goblinCommander: Commander,
    private val knightCommander: Commander,
    private val broadcast: (String) -> Unit,
) {
    private val front = config.frontId

    private var phase: WarState.Phase = WarState.Phase.PEACE
    private var goblinReserve = 0
    private var peakRoster = 0
    private var tier: RaidTier? = null
    private var raidTicks = 0
    /** True once the marching raiders (rabble + elites) are wiped - knights then storm the camp and
     *  the Warlord's bodyguards stop replenishing so the wall can actually be cleared. */
    private var raidersCleared = false
    private var nextRaidTicks = 0
    private var replenishCounter = 0
    private var lastFocusAnnounced = -1

    var warlord: Npc? = null
        private set
    private var warlordFieldIndex = 0

    private var goblinPosture = GoblinPosture.PROBE
    private var knightPosture = KnightPosture.HOLD

    private val warlordDef = NpcCombatDef.DEFAULT.copy(
        attack = 110, strength = 110, defence = 150, hitpoints = config.warlordHp,
        aggressiveRadius = 14, aggroTargetDelay = 8, aggressiveTimer = 200,
    )

    /** Where the Warlord holds (his spawn) - he stays back so players fight through the horde. */
    private var warlordHoldTile: Tile? = null

    /** Elite squad that walls the Warlord at the NW camp (must be cleared to expose him to NPCs). */
    private val bodyguards = mutableListOf<Npc>()
    /** The NE camp's mini-boss wave-leader (no rare loot). */
    private var lieutenant: Npc? = null
    /** Roaming, aggressive goblins that hold the spawns BETWEEN raids (despawned during a raid). */
    private val peaceCamp = mutableListOf<Npc>()

    private val bodyguardDef = NpcCombatDef.DEFAULT.copy(
        attack = 80, strength = 80, defence = 90, hitpoints = 120,
        aggressiveRadius = 10, aggroTargetDelay = 8, aggressiveTimer = 200,
    )
    private val lieutenantDef = NpcCombatDef.DEFAULT.copy(
        attack = 100, strength = 100, defence = 110, hitpoints = 700,
        aggressiveRadius = 12, aggroTargetDelay = 8, aggressiveTimer = 200,
    )

    /** The spatial brain (influence maps + flow field), bounded to the battle box. */
    private val tactical: TacticalMap = config.battleBox().let { TacticalMap(it[0], it[1], it[2], it[3]) }

    /** Strategy layer - the live LLM commander (gated by `::warllm` + ANTHROPIC_API_KEY),
     *  falling back to the deterministic heuristic whenever the LLM is off or not ready. */
    private val strategist: Strategist = LlmStrategist(HeuristicStrategist())

    /** General Zo - the defended VIP the goblins hunt; his death fells the city. */
    var zo: Npc? = null
        private set

    /** Build the spatial map, post General Zo, and either resume a fallen state or schedule
     *  the first raid. */
    fun init(world: World) {
        // Force-build collision for EVERY region the battlefield spans. The engine only loads a
        // region's collision when a player is near it; the goblin camps sit 100+ tiles from where
        // players stand, so their chunks never loaded -> every tile there read as blocked and NO
        // pathfinder (flow field or waypoints) could route a unit out of camp. Loading them up front
        // (idempotent via activeRegions) gives the whole corridor real terrain collision.
        forceLoadBattlefieldRegions(world)
        openBridges(world)
        // The flow field is only consulted for movement when useFlowField is on (influence/threat
        // maps don't need it). Skip the per-init walkability scan + BFS otherwise.
        if (config.useFlowField) {
            tactical.buildWalkability(world)
            tactical.computeFlow(world, config.zoTile)
        }
        phase = if (WarState.isCityFallen(front)) WarState.Phase.CITY_FALLEN else WarState.Phase.PEACE
        if (phase != WarState.Phase.CITY_FALLEN) spawnZo(world)
        if (phase == WarState.Phase.PEACE) scheduleNextRaid(world)
        publishStatus(0)
    }

    /** Build collision for every region the [battleBox] overlaps, so far-flung camp tiles are
     *  pathable even with no player nearby. Idempotent - [DefinitionSet.loadRegions] skips regions
     *  already active. */
    private fun forceLoadBattlefieldRegions(world: World) {
        val box = config.battleBox()
        val rxMin = box[0] shr 6; val rxMax = (box[0] + box[2] - 1) shr 6
        val ryMin = box[1] shr 6; val ryMax = (box[1] + box[3] - 1) shr 6
        val regions = ArrayList<Int>()
        for (rx in rxMin..rxMax) for (ry in ryMin..ryMax) regions += (rx shl 8) or ry
        world.definitions.loadRegions(world, world.chunks, regions.toIntArray())
        logger.info { "[WAR] $front force-loaded ${regions.size} battlefield region(s): $regions" }
    }

    /** Make bridge decks crossable. A bridge tile renders its deck at level 0 (the cache shifts the
     *  deck loc down a plane) but keeps the river's level-0 BLOCK flag, so the flow field/pathfinder
     *  (both level-0) can't cross. Within each [SiegeConfig.bridgeSpans] box, clear the level-0 flags
     *  on exactly the bridge tiles - those blocked on L0 but walkable on L1 - leaving real walls/banks
     *  (blocked on both planes) untouched. */
    private fun openBridges(world: World) {
        var opened = 0
        config.bridgeSpans.forEach { span ->
            for (x in span.bottomLeftX..span.topRightX) for (z in span.bottomLeftY..span.topRightY) {
                if (world.collision.isClipped(Tile(x, z, 0)) && !world.collision.isClipped(Tile(x, z, 1))) {
                    world.collision.set(x, z, 0, 0)
                    opened++
                }
            }
        }
        if (opened > 0) logger.info { "[WAR] $front opened $opened bridge tile(s) for crossing" }
    }

    fun tick(world: World) {
        try {
            when (phase) {
                WarState.Phase.PEACE -> tickPeace(world)
                WarState.Phase.UNDER_RAID -> tickRaid(world)
                WarState.Phase.CITY_FALLEN -> tickFallen(world)
            }
        } catch (e: Exception) {
            logger.error(e) { "AttackDirector tick failed for '$front'" }
        }
    }

    // --- PEACE ---

    private fun tickPeace(world: World) {
        // Replenish the garrison slowly.
        if (++replenishCounter >= config.knightReplenishPeriodTicks) {
            replenishCounter = 0
            if (WarState.getKnightPool(front) < config.knightPoolMax) WarState.addKnightPool(front, 1)
        }
        ensurePeaceCamp(world) // standing roaming/aggro goblins hold the spawns between raids
        if (--nextRaidTicks <= 0) beginRaid(world)
        publishStatus(0)
    }

    private fun scheduleNextRaid(world: World) {
        nextRaidTicks = world.random(config.minPeaceTicks..config.maxPeaceTicks)
        phase = WarState.Phase.PEACE
    }

    // --- raid start ---

    fun beginRaid(world: World, forced: RaidTier? = null) {
        val t = forced ?: config.rollTier { bound -> world.random(bound) }
        tier = t
        goblinReserve = t.rosterSize
        peakRoster = t.rosterSize
        raidTicks = 0
        lastFocusAnnounced = -1
        goblinCommander.reset()
        knightCommander.reset()
        clearPeaceCamp(world) // the raiding horde replaces the peacetime roamers
        forceLoadBattlefieldRegions(world) // ensure camp/road collision is built (no-op if already)
        openBridges(world) // make the bridge decks crossable (clears the level-0 river block under them)
        spawnZo(world) // Zo must be at his post for the goblins to hunt
        // Rebuild the flow field's walkability HERE (not just at init): the boot-time sample in
        // init() runs before the map's collision regions are loaded, so it reads every tile as
        // clipped and the BFS can't leave Zo's tile. By raid time the map is loaded, so a fresh
        // sample gives the real terrain and the gradient actually reaches the camps.
        if (config.useFlowField) {
            tactical.buildWalkability(world)
            tactical.computeFlow(world, config.zoTile)
            fun fd(x: Int, z: Int): String { val d = tactical.flowDistance(Tile(x, z, 0)); return if (d == Int.MAX_VALUE) "X" else "$d" }
            // Scan the east-of-bridge area (Al Kharid road side) to find a flow-reachable camp tile.
            val scan = listOf(
                3250 to 3226, 3252 to 3226, 3254 to 3225, 3256 to 3226, 3258 to 3227,
                3260 to 3228, 3253 to 3223, 3256 to 3223, 3252 to 3229, 3255 to 3231,
            )
            logger.info { "[FLOWDBG] east-of-bridge reachable? (X=no): " + scan.joinToString(" ") { "(${it.first},${it.second})=${fd(it.first, it.second)}" } }
        }
        phase = WarState.Phase.UNDER_RAID
        broadcast(tierWarning(t))
        publishStatus(0)
        logger.info { "[WAR] $front raid begin: tier=${t.name} roster=${t.rosterSize} warlord=${t.warlord}" }
    }

    private fun tierWarning(t: RaidTier): String = when {
        t.warlord -> "<col=ff4f4f>WARHORNS! A goblin SIEGE descends on ${config.displayName} - ${t.rosterSize} strong, led by a Warlord. To arms!</col>"
        t.rosterSize >= 120 -> "<col=ffcf48>Goblin warhorns echo across ${config.displayName} - a raid of ${t.rosterSize} is coming!</col>"
        else -> "<col=ffcf48>A goblin probe tests ${config.displayName}'s defenses (${t.rosterSize}).</col>"
    }

    // --- UNDER_RAID ---

    private fun tickRaid(world: World) {
        raidTicks++
        val a = BattleAssessment(world, fields, config.goalTile, config.breachDist, warlord)

        // L0 - perception: snapshot units/players, update the spatial fields + intercept velocities.
        val goblinNpcs = fields.flatMap { it.aliveGoblins(world) }
        val knightNpcs = fields.flatMap { it.aliveKnights(world) }
        val players = fields.flatMap { it.playersIn(world) }.distinct()
        MovementTracker.tick(players)
        tactical.stampInfluence(goblinNpcs, knightNpcs, players)

        // L1 - strategy: the (pluggable) Strategist sets postures + each side's focus field.
        val ctx = StrategistContext(
            front, config.fields.map { it.name }, config.fields.map { it.zone }, warlordFieldIndex, tactical,
            knightPool = WarState.getKnightPool(front), goblinReserve = goblinReserve, tierName = tier?.name ?: "",
        )
        val intent = strategist.decide(a, ctx)
        goblinPosture = intent.goblinPosture
        knightPosture = intent.knightPosture
        goblinCommander.focusIndex = intent.goblinFocus
        knightCommander.focusIndex = intent.knightFocus
        if (goblinPosture == GoblinPosture.THRUST && intent.goblinFocus != lastFocusAnnounced) {
            lastFocusAnnounced = intent.goblinFocus
            val leader = if (tier?.warlord == true) "The Warlord turns the horde" else "The horde presses"
            broadcast("<col=ff4f4f>$leader toward the ${fields[intent.goblinFocus].name}!</col>")
        }

        // L2 - allocation from the live finite supply.
        val goblinBudget = goblinReserve + a.totalGoblinsAlive
        val knightBudget = WarState.getKnightPool(front) + a.totalKnightsAlive
        goblinCommander.allocate(
            world, a, goblinBudget,
            concentrate = goblinPosture != GoblinPosture.PROBE,
            concentrateMult = if (goblinPosture == GoblinPosture.RALLY) 4 else 3,
            seedElites = goblinPosture != GoblinPosture.PROBE,
        )
        knightCommander.allocate(
            world, a, knightBudget,
            concentrate = knightPosture != KnightPosture.HOLD,
            concentrateMult = if (knightPosture == KnightPosture.GUARD_ZO) 5 else 3,
            seedElites = false,
        )

        // Spawn toward targets from the shared reserve / pool, capped per tick.
        spawnFromSupply(world)

        // L3 - hand the posture weights, objectives, and flow field down to the engine.
        // The generals are "clear-the-army-first" objectives for NPCs: goblins may only damage Zo
        // once the knights are cleared; knights may only damage the Warlord once the goblins are
        // cleared. (Players are never gated by this - they can rush either general, at their risk.)
        val gW = WarBrain.goblinWeights(goblinPosture)
        val kW = WarBrain.knightWeights(knightPosture)
        val guardsAlive = bodyguards.count { isAlive(world, it) }
        val lieutenantAlive = lieutenant?.let { isAlive(world, it) } == true
        // FINAL ASSAULT: once the marching raiders (rabble + elites) are wiped, knights press the camp
        // - the Warlord's bodyguards + the NE lieutenant become valid targets (so the knights storm in
        // and clear the wall), and once the wall is down the Warlord is exposed for the kill. While
        // raiders remain the camp force stays off-limits to NPC knights (players can rush it sooner).
        raidersCleared = a.totalGoblinsAlive == 0
        val campForce: List<Npc> = if (raidersCleared)
            bodyguards.filter { isAlive(world, it) } + listOfNotNull(lieutenant?.takeIf { isAlive(world, it) })
            else emptyList()
        val warlordExposed = raidersCleared && guardsAlive == 0 && !lieutenantAlive
        val flowMap = if (config.useFlowField) tactical else null
        fields.forEach {
            it.goblinWeights = gW; it.knightWeights = kW
            // Knights target the marching raiders (rabble + elites across all fields), plus - once those
            // are wiped - the camp force (bodyguards + lieutenant). Never stray goblin-kin.
            it.raidingForce = goblinNpcs + campForce
            // Layer 1 â†’ MOVEMENT: the same posture that sets knightWeights now also steers where the
            // knights march (advance on HUNT, hold the line on HOLD, collapse to Zo on GUARD_ZO).
            it.setKnightPosture(knightPosture)
            // Warlord is always set (so the engine keeps it OUT of the normal goblin pool),
            // but NPC knights may only engage it once warlordExposed.
            it.knightObjective = warlord
            it.knightObjectiveActive = warlordExposed
            // The horde no longer hunts General Zo / breaches the keep — that mechanic was removed.
            // Goblins simply march to the gate (their waypoint) and fight whoever meets them there.
            it.goblinObjective = null
            it.goblinObjectiveActive = false
            it.tactical = flowMap
        }

        manageWarlord(world)
        manageBodyguards(world)
        manageLieutenant(world)

        // Quiet front-watch: one concise line every ~10 ticks so the raid can be followed in the log
        // (goblins closing on Zo = frontDist dropping). CAMP-ASSAULT shows once the field is cleared.
        if (raidTicks % 10 == 0) {
            val camp = if (raidersCleared) {
                val wlHp = warlord?.takeIf { isAlive(world, it) }?.getCurrentHp() ?: 0
                " CAMP-ASSAULT guards=${bodyguards.count { isAlive(world, it) }} warlordHp=$wlHp"
            } else ""
            val cg = fields.flatMap { it.aliveGoblins(world) }.minByOrNull { chebyshev(it.tile, config.zoTile) }?.tile
            logger.info {
                "[WAR] $front t=$raidTicks goblins=${a.totalGoblinsAlive} knights=${a.totalKnightsAlive} " +
                "reserve=$goblinReserve frontDist=${a.spearheadField?.frontDist ?: "-"} closestGob=${cg?.let { "(${it.x},${it.z})" } ?: "-"}$camp"
            }
        }

        fields.forEach { it.combat(world) }

        // Learning (per-field player presence) + optional replay datasheet.
        config.fields.forEachIndexed { i, f -> WarMemory.observe(front, f.name, a.fieldViews[i].playerCount) }
        if (WarRecorder.isEnabled(front)) WarRecorder.record(front, raidTicks, goblinNpcs, knightNpcs, players)

        resolve(world, a)
    }

    private fun spawnFromSupply(world: World) {
        val gRate = config.goblinReinforcePerTick
        // Hold the knights at the muster for the first few ticks so the goblins reach and stack on the
        // bridge before the defenders sally - the clash then happens at the bridge, not the gates.
        val kRate = if (raidTicks < config.knightDeployDelayTicks) 0 else config.knightReinforcePerTick
        for (f in fields) {
            // Elites first (they're the spearhead), then rabble - both drain the reserve.
            val eliteDemand = (f.eliteTarget - f.aliveEliteCount(world)).coerceIn(0, gRate)
            val eSpawn = minOf(eliteDemand, goblinReserve)
            if (eSpawn > 0) goblinReserve -= f.spawnElites(world, eSpawn)

            val rabbleDemand = (f.attackerTarget - f.aliveRabbleCount(world)).coerceIn(0, gRate)
            val rSpawn = minOf(rabbleDemand, goblinReserve)
            if (rSpawn > 0) goblinReserve -= f.spawnGoblins(world, rSpawn)

            val pool = WarState.getKnightPool(front)
            val kDemand = (f.defenderTarget - f.aliveDefenderCount(world)).coerceIn(0, kRate)
            val kSpawn = minOf(kDemand, pool)
            if (kSpawn > 0) {
                val spawned = f.spawnKnights(world, kSpawn)
                WarState.addKnightPool(front, -spawned)
            }
        }
    }

    // --- General Zo (the goblins' kill objective; his death fells the city) ---

    /** Spawn/refresh Zo at his post with his combat stats. Players can't attack him (his cache
     *  NPC has no Attack option); goblins call attack() on him directly, so he's huntable by the
     *  horde but un-griefable. */
    private fun spawnZo(world: World) {
        if (zo?.let { isAlive(world, it) } == true) return
        val npc = Npc(getRSCM(config.zoNpc), config.zoTile, world)
        npc.routeLogic = 1
        // MUST be before world.spawn: the client avatar takes its facing at alloc time, so a
        // direction set after spawn never renders. Faces his west desks in the GE hub's ring,
        // same as Duke Horacio one tile north — the pair stand facing the same way.
        npc.lastFacingDirection = Direction.WEST
        world.spawn(npc)
        // MUST be after world.spawn: setNpcDefaults() resets combatDef + HP to the cache default on
        // spawn, so applying Zo's tanky boss stats earlier would be silently clobbered (the bug
        // that made the generals one-hittable).
        applyCombatDef(npc, config.zoDef)
        WarNpcNames.apply(npc, config.zoNpc) // display "General Zo" without a cache edit
        npc.respawns = false
        npc.setActive(true)
        zo = npc
    }

    /** Push a combat def + its levels + full HP onto a spawned NPC. Call AFTER world.spawn. */
    private fun applyCombatDef(npc: Npc, def: NpcCombatDef) {
        npc.combatDef = def
        npc.stats.setMaxLevel(NpcSkills.ATTACK, def.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, def.attack)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, def.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, def.strength)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, def.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def.defence)
        npc.setCurrentHp(def.hitpoints)
    }

    private fun clearZo(world: World) {
        zo?.let { if (isAlive(world, it)) { it.setCurrentHp(0); world.remove(it) } }
        zo = null
    }

    /** Called by [SiegePlugin]'s death handler if THIS director's Zo dies. Zo is no longer a war
     *  objective (players can't attack him, goblins don't hunt him), so this is just bookkeeping —
     *  it NO LONGER fells the city. */
    fun onZoKilled(world: World) {
        zo = null
    }

    /** Post Zo at his courtyard tile as a permanent decorative/dialogue fixture. Used when the
     *  defensive siege is disabled: the director never runs [init], so nothing would otherwise
     *  spawn him — but he must still stand in the courtyard for [GeneralZoPlugin]'s war-status +
     *  feudal-command dialogue. Idempotent (no-op if he's already alive). */
    fun postDecorativeZo(world: World) = spawnZo(world)

    // --- Warlord (the knights' kill objective; killing it routs the horde) ---

    private fun manageWarlord(world: World) {
        val wl = warlord
        if (wl != null && isAlive(world, wl)) {
            // Hold position at the spawn - the boss stays back; players fight through to reach him.
            val hold = warlordHoldTile
            if (!wl.isAttacking() && hold != null && chebyshev(wl.tile, hold) > 2) wl.walkTo(hold)
            return
        }
        warlord = null
        val t = tier ?: return
        if (t.warlord && !goblinCommander.isDisrupted() && goblinReserve + fields.sumOf { it.aliveAttackerCount(world) } > 0) {
            warlordFieldIndex = 0 // the NW boss-arena field - used for the knights' HUNT focus
            warlord = spawnWarlord(world)
            broadcast("<col=ff4f4f>A Goblin Warlord commands the assault from the back lines - fight through the horde and slay him to break the siege!</col>")
        }
    }

    private fun spawnWarlord(world: World): Npc {
        val tile = config.warlordSpawnTile
        warlordHoldTile = tile
        val npc = Npc(getRSCM(config.warlordNpc), tile, world)
        npc.routeLogic = 1
        npc.aggroCheck = { _, _ -> true }
        world.spawn(npc)
        // After spawn (see applyCombatDef note) push the boss stats + 220 HP, name, and level.
        applyCombatDef(npc, warlordDef)
        NpcInfo(npc).setTempName("Goblin Warlord")
        npc.overrideLevel(WARLORD_LEVEL)
        npc.respawns = false
        npc.setActive(true)
        return npc
    }

    // --- Bodyguards (the elite wall protecting the Warlord), NE lieutenant, peace camps ---

    /** Keep ~[BODYGUARD_COUNT] elite guards clustered on the Warlord while he lives. */
    private fun manageBodyguards(world: World) {
        val wl = warlord
        if (wl == null || !isAlive(world, wl)) { clearList(world, bodyguards); return }
        bodyguards.removeAll { it.index < 0 || it.isDead() }
        // Once the raiders are wiped the wall STOPS replenishing, so the knights' final assault can
        // actually grind it down and expose the Warlord (otherwise guards respawn faster than they die).
        val replenish = if (raidersCleared) 0 else minOf(config.bodyguardCount - bodyguards.size, GUARD_SPAWN_PER_TICK).coerceAtLeast(0)
        repeat(replenish) {
            val tile = world.findRandomTileAround(wl.tile, radius = GUARD_HOLD_RADIUS) ?: wl.tile
            val npc = Npc(getRSCM(config.attackerEliteNpc), tile, world)
            npc.walkRadius = 0
            npc.routeLogic = 1
            npc.aggroCheck = { _, _ -> true }
            world.spawn(npc)
            applyCombatDef(npc, bodyguardDef)
            npc.respawns = false
            npc.setActive(true)
            bodyguards += npc
        }
        bodyguards.forEach { g ->
            if (isAlive(world, g) && !g.isAttacking() && chebyshev(g.tile, wl.tile) > GUARD_HOLD_RADIUS) {
                (world.findRandomTileAround(wl.tile, radius = GUARD_HOLD_RADIUS) ?: wl.tile).let { g.walkTo(it) }
            }
        }
    }

    /** The NE camp's mini-boss: holds at its post, no rare loot. Spawns on warlord-tier raids. */
    private fun manageLieutenant(world: World) {
        val lt = lieutenant
        if (lt != null && isAlive(world, lt)) {
            if (!lt.isAttacking() && chebyshev(lt.tile, config.lieutenantSpawnTile) > 2) lt.walkTo(config.lieutenantSpawnTile)
            return
        }
        lieutenant = null
        val t = tier ?: return
        if (config.hasLieutenant && t.warlord && goblinReserve + fields.sumOf { it.aliveAttackerCount(world) } > 0) {
            val npc = Npc(getRSCM(config.warlordNpc), config.lieutenantSpawnTile, world)
            npc.routeLogic = 1
            npc.aggroCheck = { _, _ -> true }
            world.spawn(npc)
            applyCombatDef(npc, lieutenantDef)
            NpcInfo(npc).setTempName("Goblin Lieutenant")
            npc.overrideLevel(LIEUTENANT_LEVEL)
            npc.respawns = false
            npc.setActive(true)
            lieutenant = npc
            broadcast("<col=ff4f4f>A Goblin Lieutenant rallies the north-east assault!</col>")
        }
    }

    /** Maintain roaming, aggressive goblins at both spawns during PEACE. */
    private fun ensurePeaceCamp(world: World) {
        peaceCamp.removeAll { it.index < 0 || it.isDead() }
        val target = PEACE_CAMP_PER_SPAWN * 2
        if (peaceCamp.size >= target) return
        val origins = listOf(config.warlordSpawnTile, config.lieutenantSpawnTile)
        repeat(minOf(target - peaceCamp.size, PEACE_SPAWN_PER_TICK)) {
            val origin = origins[peaceCamp.size % origins.size]
            val tile = world.findRandomTileAround(origin, radius = PEACE_ROAM_RADIUS) ?: origin
            val npc = Npc(getRSCM(config.attackerNpc), tile, world)
            npc.walkRadius = PEACE_ROAM_RADIUS // the engine wanders them within this radius
            npc.aggroCheck = { _, _ -> true }
            npc.routeLogic = 1
            world.spawn(npc)
            // Apply the goblin combat def AFTER spawn so the camp roamers are properly aggressive
            // (aggressiveRadius/Timer) and a real threat to players who wander the camp between raids.
            applyCombatDef(npc, config.attackerDef)
            npc.respawns = false
            npc.setActive(true)
            peaceCamp += npc
        }
    }

    private fun clearPeaceCamp(world: World) = clearList(world, peaceCamp)

    private fun clearList(world: World, list: MutableList<Npc>) {
        list.forEach { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
        list.clear()
    }

    /** Called by [SiegePlugin]'s death handler when THIS director's Warlord is slain. */
    fun onWarlordKilled(world: World, killer: Player?) {
        warlord = null
        goblinCommander.disrupt(DISRUPT_TICKS)
        // Morale collapse: half the un-committed horde flees.
        goblinReserve /= 2
        killer?.let { WarParticipation.record(front, it, WARLORD_KILL_POINTS) }
        broadcast("<col=4f9b4f>The Goblin Warlord has fallen! The leaderless horde reels in confusion.</col>")
    }

    private fun clearWarlord(world: World) {
        warlord?.let { if (isAlive(world, it)) { it.setCurrentHp(0); world.remove(it) } }
        warlord = null
    }

    // --- resolution ---

    private fun resolve(world: World, a: BattleAssessment) {
        // There is no Zo/breach loss condition any more — a raid ends only by the DEFENDER WIN
        // (roster + Warlord wiped) or the DRAW (timeout). The horde is held at the gate by the keep.
        // The Warlord counts as part of the horde: the defense isn't won until it's dead too
        // (knights expose it only after the rabble is cleared; players can rush it sooner).
        val warlordAlive = warlord?.let { isAlive(world, it) } == true
        val lieutenantAlive = lieutenant?.let { isAlive(world, it) } == true
        val guards = bodyguards.count { isAlive(world, it) }
        val goblinsAlive = fields.sumOf { it.aliveAttackerCount(world) } + guards +
            (if (warlordAlive) 1 else 0) + (if (lieutenantAlive) 1 else 0)
        if (goblinReserve <= 0 && goblinsAlive == 0) {
            defendersWin(world); return
        }
        if (raidTicks >= config.raidTimeoutTicks) {
            raidTimeout(world); return
        }
        publishStatus(goblinsAlive)
    }

    private fun defendersWin(world: World) {
        endRaid(world, returnKnights = true)
        val parts = WarParticipation.drain(front)
        RaidRewards.award(world, parts, config.displayName)
        broadcast("<col=4f9b4f>VICTORY! The goblin horde is broken - ${config.displayName} stands free!</col>")
        scheduleNextRaid(world)
        WarState.save(force = true)
        publishStatus(0)
    }

    private fun raidTimeout(world: World) {
        endRaid(world, returnKnights = true)
        WarParticipation.clear(front)
        broadcast("<col=ffcf48>The goblin assault on ${config.displayName} falters and the horde melts away.</col>")
        scheduleNextRaid(world)
        publishStatus(0)
    }

    private fun tickFallen(world: World) {
        val left = WarState.decayCityFallen(front, 1)
        if (left <= 0) {
            WarState.clearCityFallen(front)
            spawnZo(world) // a new General Zo takes the post as the city rebuilds
            broadcast("<col=4f9b4f>${config.displayName} has driven out the occupiers and rebuilt. General Zo retakes his post.</col>")
            scheduleNextRaid(world)
            WarState.save(force = true)
        }
        publishStatus(0)
    }

    /** Despawn the field units, returning surviving knights to the castle pool. */
    private fun endRaid(world: World, returnKnights: Boolean) {
        var returned = 0
        fields.forEach {
            returned += it.withdrawDefenders(world)
            it.clear(world)
        }
        if (returnKnights) WarState.addKnightPool(front, returned)
        clearWarlord(world)
        clearList(world, bodyguards)
        lieutenant?.let { if (isAlive(world, it)) { it.setCurrentHp(0); world.remove(it) } }
        lieutenant = null
        goblinReserve = 0
        tier = null
        WarMemory.save(force = true) // persist what we learned about player habits this raid
    }

    // --- admin / status ---

    /** Hard reset to a quiet baseline (::warreset). */
    fun reset(world: World) {
        endRaid(world, returnKnights = false)
        clearPeaceCamp(world)
        goblinCommander.reset()
        knightCommander.reset()
        WarState.clearCityFallen(front)
        WarState.setKnightPool(front, config.knightPoolMax)
        WarParticipation.clear(front)
        spawnZo(world)
        scheduleNextRaid(world)
        publishStatus(0)
    }

    /** Force the city into the fallen state (::warfall test). */
    fun forceCityFall(world: World) {
        if (phase == WarState.Phase.UNDER_RAID) endRaid(world, returnKnights = true)
        clearPeaceCamp(world)
        clearZo(world)
        WarState.setCityFallen(front, config.cityFallenTicks)
        phase = WarState.Phase.CITY_FALLEN
        WarState.save(force = true)
        publishStatus(0)
    }

    fun tierByName(name: String): RaidTier? =
        config.raidTiers.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Multi-line status for ::warmap. */
    fun statusLines(world: World): List<String> {
        val out = ArrayList<String>()
        val phaseLabel = when (WarState.phaseOf(front)) {
            WarState.Phase.PEACE -> "<col=4f9b4f>AT PEACE</col> (next raid ~${nextRaidTicks} ticks)"
            WarState.Phase.UNDER_RAID -> "<col=ff4f4f>UNDER RAID</col> ${tier?.name ?: ""} - reserve $goblinReserve, ${goblinPosture}/${knightPosture}"
            WarState.Phase.CITY_FALLEN -> "<col=ff4f4f>FALLEN</col> (~${WarState.cityFallenTicksLeft(front)} ticks to recover)"
        }
        out += "=== <col=801700>${config.displayName}</col> - $phaseLabel - pool ${WarState.getKnightPool(front)}/${config.knightPoolMax} ==="
        fields.forEachIndexed { i, f ->
            val g = if (i == goblinCommander.focusIndex) " <col=ff4f4f>[G]</col>" else ""
            val k = if (i == knightCommander.focusIndex) " <col=33aaff>[K]</col>" else ""
            out += "  ${f.name}: ${f.aliveAttackerCount(world)}/${f.attackerTarget}g - ${f.aliveDefenderCount(world)}/${f.defenderTarget}k - ${f.playersInZone(world)}P$g$k"
        }
        out += if (warlord != null) "  Warlord: <col=ff4f4f>ALIVE</col>" else "  Warlord: down"
        return out
    }

    private fun publishStatus(goblinsAlive: Int) {
        val statusPhase = if (phase == WarState.Phase.CITY_FALLEN) WarState.Phase.PEACE else phase
        WarState.setRaidStatus(front, WarState.RaidStatus(statusPhase, tier?.name ?: "", goblinsAlive, peakRoster))
    }

    private fun isAlive(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

    private fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private companion object {
        const val DISRUPT_TICKS = 50 // director ticks the goblin AI stays crippled after a Warlord kill
        const val WARLORD_KILL_POINTS = 500 // contribution credited to the Warlord-killer (makes them MVP)
        const val WARLORD_LEVEL = 150 // displayed combat level for the Warlord boss
        const val LIEUTENANT_LEVEL = 100 // displayed combat level for the NE lieutenant
        const val BODYGUARD_COUNT = 20 // elite guards walling the Warlord
        const val GUARD_HOLD_RADIUS = 4 // bodyguards stay within this many tiles of the Warlord
        const val GUARD_SPAWN_PER_TICK = 4 // how fast the guard wall reforms
        const val PEACE_CAMP_PER_SPAWN = 12 // roaming goblins held at each spawn between raids
        const val PEACE_ROAM_RADIUS = 8 // how far the peacetime roamers wander from their spawn
        const val PEACE_SPAWN_PER_TICK = 3 // peacetime camp replenish rate
    }
}
