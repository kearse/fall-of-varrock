package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * A reusable **battlefield** for one raid field (The War — `docs/war-system-design.md`).
 *
 * Two populations live here, both spawned & maintained by the owning [AttackDirector]
 * (natural NPCs are untouched):
 *  - **Attackers** (goblins + elite shock troops) — spawn at their camp and march their
 *    waypoints toward the castle, fighting defenders and **players** they meet.
 *  - **Defenders** (knights) — spawn at the muster and march out to meet the attackers.
 *
 * Spawning is **bounded**: this class never refills on its own. The director calls
 * [spawnGoblins]/[spawnElites]/[spawnKnights] from the raid's *finite* shared reserve /
 * knight pool, so the dead stay dead and a raid actually ends. [combat] drives whatever
 * is currently alive.
 *
 * Combatants get real stats pushed into `npc.stats` (the melee formula reads that, not
 * the combat def). routeLogic = smart pathfinder. A unit wedged for [UNSTICK_LIMIT]
 * ticks **hops to its next waypoint**, so the line always advances. Target choice is the
 * War Brain's [TargetSelector] (Layer 3), weighted by the per-side posture the director
 * sets in [goblinWeights]/[knightWeights].
 */
class WarFront(
    val frontId: String,
    /** Short human name for broadcasts (the field, e.g. "EAST"). */
    val name: String,
    private val zone: Area,
    private val attackerNpcs: List<String>,
    private val attackerStaging: List<Tile>,
    private val defenderNpcs: List<String>,
    private val defenderStaging: List<Tile>,
    /** Ordered waypoints: attackers march camp -> ... -> castle; defenders the reverse. */
    private val attackerObjectives: List<Tile>,
    private val defenderObjectives: List<Tile>,
    private val attackerDef: NpcCombatDef,
    private val defenderDef: NpcCombatDef,
    private val attackerEliteNpc: String,
    private val attackerEliteDef: NpcCombatDef,
    /** Protected castle keep (the new-player respawn courtyard). Attackers are leashed OUT of it
     *  and never target a player standing inside it; defenders (knights) are free to enter. Null =
     *  no keep. See [Sieges.LUMBRIDGE_KEEP]. */
    private val keep: Area? = null,
) {
    private val warGoblinPool = mutableListOf<Npc>()
    private val elitePool = mutableListOf<Npc>()
    private val knightPool = mutableListOf<Npc>()

    /** Troop targets for this field, set by the owning [Commander] each tick. */
    var attackerTarget: Int = 0
    var defenderTarget: Int = 0
    var eliteTarget: Int = 0

    /** Per-side target-selection posture, set by the director each tick (Layer 1 → 3). */
    var goblinWeights: TargetSelector.Weights = TargetSelector.Weights.BALANCED
    var knightWeights: TargetSelector.Weights = TargetSelector.Weights.BALANCED

    /** The knights' kill objective (the Warlord), or null. Always kept OUT of the normal goblin
     *  target pool; NPC knights engage it only when [knightObjectiveActive]. Players are never
     *  gated — they may rush it via the click path at any time. */
    var knightObjective: Npc? = null
    var knightObjectiveActive: Boolean = false

    /** The goblins' kill objective (General Zo). Goblins prioritise him and path to him. */
    var goblinObjective: Npc? = null

    /** True once the goblins may actually DAMAGE Zo — i.e. his knights have been cleared. Until
     *  then goblins fight through the defenders; this gate applies to NPC goblins only, never to
     *  players (who can rush a general directly, at their own risk). The director sets it. */
    var goblinObjectiveActive: Boolean = false

    /** The shared [TacticalMap] flow field; when set, goblins march along its gradient toward
     *  Zo (terrain-aware) instead of fixed waypoints. Set by the director each tick. */
    var tactical: TacticalMap? = null
    var flowLookahead: Int = 6

    /** The full ACTIVE RAIDING FORCE — every field's rabble + elites — refreshed by the director each
     *  tick. Knights target only this, so they focus the marching attackers and ignore the Warlord's
     *  holding bodyguards and any natural/roaming goblin-kin (the old in-zone name-scan caught those). */
    var raidingForce: List<Npc> = emptyList()

    // --- Layer 1 → MOVEMENT: posture-driven knight march. ---
    // The strategist's [KnightPosture] used to steer only target weights; now it also steers where
    // the knights actually WALK. Three routes are derived from tiles the field already owns:
    //   GUARD_ZO -> collapse to the rally ring at Zo (the fixed defenderObjectives).
    //   HOLD     -> advance to the forward line (~the bridges / city edge).
    //   HUNT     -> push the whole road to the attacker camp / Warlord.
    // The HUNT route is the attacker road reversed, so it is guaranteed pathable (the goblins walk
    // those exact tiles every raid) — this is what avoids the old hand-drawn "stuck at the river" bug.
    private val rallyRoute: List<Tile> = defenderObjectives
    private val huntRoute: List<Tile> = attackerObjectives.reversed()
    private val holdRoute: List<Tile> = huntRoute.take(max(1, (huntRoute.size + 1) / 2))

    /** Where knights currently march, chosen from the posture by [setKnightPosture]. */
    var knightMarch: List<Tile> = rallyRoute
        private set

    /** Translate the director's per-tick [KnightPosture] into the knights' march objective. */
    fun setKnightPosture(posture: KnightPosture) {
        knightMarch = when (posture) {
            KnightPosture.GUARD_ZO -> rallyRoute
            KnightPosture.HOLD -> holdRoute
            KnightPosture.HUNT -> huntRoute
        }
    }

    // Anti-stuck tracking (see the long note in the original design: progress is "got
    // closer than ever before", not "tile changed", so lateral de-stack jitter doesn't
    // read as progress and the horde never mills at a river forever).
    // What each unit is currently steering toward — a march waypoint (Tile) while advancing, or
    // its live combat target (Pawn) while engaged. Anti-stuck measures progress toward THIS, so a
    // unit closing on a distant foe isn't mistaken for one wedged against terrain.
    private val lastGoalByNpc = HashMap<Npc, Any>()
    private val bestDistByNpc = HashMap<Npc, Int>()
    private val stuckTicksByNpc = HashMap<Npc, Int>()

    // --- live counts ---

    fun aliveRabbleCount(world: World): Int = warGoblinPool.count { isAlive(world, it) }
    fun aliveEliteCount(world: World): Int = elitePool.count { isAlive(world, it) }
    fun aliveAttackerCount(world: World): Int = aliveRabbleCount(world) + aliveEliteCount(world)
    fun aliveDefenderCount(world: World): Int = knightPool.count { isAlive(world, it) }

    /** Live unit lists (for the spatial influence map + the replay datasheet). */
    fun aliveGoblins(world: World): List<Npc> = (warGoblinPool + elitePool).filter { isAlive(world, it) }
    fun aliveKnights(world: World): List<Npc> = knightPool.filter { isAlive(world, it) }

    /** Real players standing in this field's zone. */
    fun playersIn(world: World): List<Player> {
        val out = ArrayList<Player>()
        for (i in 0 until world.players.capacity) {
            val p = world.players[i] ?: continue
            if (zone.contains(p.tile)) out += p
        }
        return out
    }
    fun playersInZone(world: World): Int = playersIn(world).size

    /** Chebyshev distance of the closest living goblin to [goal]; [Int.MAX_VALUE] if none. */
    fun closestAttackerDist(world: World, goal: Tile): Int =
        (warGoblinPool + elitePool).filter { isAlive(world, it) }.minOfOrNull { dist(it.tile, goal) } ?: Int.MAX_VALUE

    // --- bounded spawning (called by the director from the finite shared supply) ---

    fun spawnGoblins(world: World, n: Int): Int =
        spawnInto(world, warGoblinPool, attackerNpcs, attackerStaging, n, aggroAll = true)

    fun spawnElites(world: World, n: Int): Int =
        spawnInto(world, elitePool, listOf(attackerEliteNpc), attackerStaging, n, aggroAll = true)

    // Knights spawn EXACTLY on the gate tiles (radius 0) and the staging list alternates the two
    // gates per spawn, so a pair emerges one-per-gate and marches out in a column.
    fun spawnKnights(world: World, n: Int): Int =
        spawnInto(world, knightPool, defenderNpcs, defenderStaging, n, aggroAll = false, spawnRadius = 0)

    private fun spawnInto(
        world: World,
        pool: MutableList<Npc>,
        names: List<String>,
        staging: List<Tile>,
        n: Int,
        aggroAll: Boolean,
        spawnRadius: Int = SPAWN_RADIUS,
    ): Int {
        if (n <= 0 || names.isEmpty() || staging.isEmpty()) return 0
        pool.removeAll { it.index < 0 || it.isDead() }
        var spawned = 0
        repeat(n) {
            val name = names[pool.size % names.size]
            val st = staging[pool.size % staging.size]
            val tile = if (spawnRadius <= 0) st else world.findRandomTileAround(st, radius = spawnRadius) ?: st
            val npc = Npc(getRSCM(name), tile, world)
            npc.walkRadius = 0
            world.spawn(npc)
            WarNpcNames.apply(npc, name) // display "Knight of Lumbridge" without a cache edit
            npc.respawns = false
            npc.setActive(true)
            if (aggroAll) npc.aggroCheck = AGGRO_ANY
            pool += npc
            spawned++
        }
        return spawned
    }

    /** Despawn surviving knights (they retreat home) and return how many survived, so the
     *  director can credit them back to the castle pool. */
    fun withdrawDefenders(world: World): Int {
        val survivors = knightPool.count { isAlive(world, it) }
        for (npc in knightPool) {
            if (npc.index >= 0 && world.npcs.contains(npc)) {
                npc.setCurrentHp(0)
                world.remove(npc)
            }
        }
        knightPool.clear()
        return survivors
    }

    /** Wipe every unit this field owns (raid over / reset). */
    fun clear(world: World) {
        for (pool in listOf(warGoblinPool, elitePool, knightPool)) {
            for (npc in pool) {
                if (npc.index >= 0 && world.npcs.contains(npc)) {
                    npc.setCurrentHp(0)
                    world.remove(npc)
                }
            }
            pool.clear()
        }
        lastGoalByNpc.clear()
        bestDistByNpc.clear()
        stuckTicksByNpc.clear()
    }

    /** Drive the battle for whatever is currently alive (no spawning here). */
    fun combat(world: World) {
        try {
            // Drop stuck-tracking for the dead (no leak).
            lastGoalByNpc.keys.removeAll { it.index < 0 || it.isDead() }
            bestDistByNpc.keys.removeAll { it.index < 0 || it.isDead() }
            stuckTicksByNpc.keys.removeAll { it.index < 0 || it.isDead() }

            val rabble = warGoblinPool.filter { isAlive(world, it) }
            val elites = elitePool.filter { isAlive(world, it) }
            val warGoblins = rabble + elites
            val knights = knightPool.filter { isAlive(world, it) }
            if (warGoblins.isEmpty() && knights.isEmpty()) return

            rabble.forEach { enlist(it, attackerDef); it.routeLogic = 1; it.aggroCheck = AGGRO_ANY }
            elites.forEach { enlist(it, attackerEliteDef); it.routeLogic = 1; it.aggroCheck = AGGRO_ANY }
            knights.forEach { enlist(it, defenderDef); it.routeLogic = 1 }

            // Goblins fight knights AND players they meet (the War Brain hunts humans on
            // purpose) and prioritise General Zo; knights hunt any goblin in the zone.
            // Players who are standing inside the protected keep (the spawn courtyard) are NEVER
            // valid goblin prey — so a fresh account that just respawned, or one that retreats into
            // the castle, can't be dragged into the fight. Knights are unaffected.
            val players = playersIn(world)
            val goblinPlayers = keep?.let { k -> players.filter { !k.contains(it.tile) } } ?: players
            // Goblins can only TARGET Zo once their army has cleared the knights (objective gate);
            // players can attack him only via the engine's click path, which this never affects.
            val zo = if (goblinObjectiveActive) goblinObjective?.takeIf { isAlive(world, it) } else null
            val goblinCandidates: List<Pawn> = if (zo != null) knights + goblinPlayers + zo else knights + goblinPlayers

            // Knights engage only the ACTIVE RAIDING FORCE (every field's rabble + elites, set by the
            // director). They ignore the Warlord's holding bodyguards and any natural/roaming
            // goblin-kin in the zone — the old name-scan dragged knights off to fight camp guards and
            // roamers. The Warlord itself is a protected boss, added only once exposed.
            val warlordNow = knightObjective?.takeIf { isAlive(world, it) }
            val baseGoblins: List<Pawn> = raidingForce.filter { isAlive(world, it) }
            val knightTargets: List<Pawn> = if (knightObjectiveActive && warlordNow != null) baseGoblins + warlordNow else baseGoblins

            // Yank any goblin that has slipped into the keep back out BEFORE it can act, so the
            // spawn courtyard stays clear even if the pathfinder routed one through it.
            if (keep != null) leashGoblinsFromKeep(world, warGoblins, keep)

            drive(world, warGoblins, goblinCandidates, attackerObjectives, GOBLIN_ENGAGE_RANGE, goblinWeights, objective = zo, flow = tactical, keep = keep)
            drive(world, knights, knightTargets, knightMarch, KNIGHT_ENGAGE_RANGE, knightWeights, objective = if (knightObjectiveActive) warlordNow else null, flow = null, advanceToEnemy = true)

            // Soft de-stack so a clump doesn't read as a single multi-hitting NPC.
            val occ = HashMap<Long, Int>()
            for (u in warGoblins + knights) {
                if (u.isDead()) continue
                val k = world.npcTileKey(u.tile.x, u.tile.z, u.tile.height)
                occ[k] = (occ[k] ?: 0) + 1
            }
            spreadOut(world, warGoblins, attackerObjectives, occ)
            spreadOut(world, knights, knightMarch, occ)
        } catch (e: Exception) {
            logger.error(e) { "WarFront '$frontId' combat failed (skipped this tick)" }
        }
    }

    /** Push the def's levels into `npc.stats` (the formula's real source) once. */
    private fun enlist(npc: Npc, def: NpcCombatDef) {
        if (npc.combatDef !== def) {
            npc.combatDef = def
            npc.stats.setMaxLevel(NpcSkills.ATTACK, def.attack)
            npc.stats.setCurrentLevel(NpcSkills.ATTACK, def.attack)
            npc.stats.setMaxLevel(NpcSkills.STRENGTH, def.strength)
            npc.stats.setCurrentLevel(NpcSkills.STRENGTH, def.strength)
            npc.stats.setMaxLevel(NpcSkills.DEFENCE, def.defence)
            npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def.defence)
            npc.setCurrentHp(def.hitpoints)
        }
    }

    private fun nextWaypoint(npc: Npc, objectives: List<Tile>): Tile? =
        objectives.firstOrNull { dist(npc.tile, it) > 2 } ?: objectives.lastOrNull()

    /** Tile of the closest living enemy in [enemyPool] (knights use this to sally toward the horde). */
    private fun nearestEnemyTile(world: World, npc: Npc, enemyPool: List<Pawn>): Tile? =
        enemyPool.filter { isAlivePawn(world, it) }.minByOrNull { dist(npc.tile, it.tile) }?.tile

    /**
     * Fighters engage enemies within [engageRange] using the War Brain's [TargetSelector]
     * (Layer 3) — coordinating focus-fire and hunting the highest-threat target per the
     * posture [weights] — else they advance along their waypoints. Anti-stuck unchanged.
     */
    private fun drive(
        world: World,
        side: List<Npc>,
        enemyPool: List<Pawn>,
        objectives: List<Tile>,
        engageRange: Int,
        weights: TargetSelector.Weights,
        objective: Npc?,
        flow: TacticalMap?,
        advanceToEnemy: Boolean = false,
        keep: Area? = null,
    ) {
        // Focus-fire bookkeeping: how many allies are already locked onto each enemy.
        val focusCount = HashMap<Pawn, Int>()
        for (npc in side) {
            val t = npc.getCombatTarget()
            if (npc.isAttacking() && t != null && isAlivePawn(world, t)) focusCount[t] = (focusCount[t] ?: 0) + 1
        }

        // Where a NOT-engaged unit walks. Knights (advanceToEnemy) stream toward the NEAREST living
        // goblin so they sally out to meet the horde the instant it spawns — wherever it is — rather
        // than holding a fixed route; the pathfinder routes them around terrain (collision is loaded
        // for the whole battlefield). If no enemy is left they fall back to flow/waypoints. Everyone
        // else (the goblins) follows the flow field gradient / fixed waypoints toward their objective.
        fun marchBase(npc: Npc): Tile? =
            if (advanceToEnemy) nearestEnemyTile(world, npc, enemyPool)
                ?: flow?.nextStepToward(npc.tile, flowLookahead) ?: nextWaypoint(npc, objectives)
            else flow?.nextStepToward(npc.tile, flowLookahead) ?: nextWaypoint(npc, objectives)

        for (npc in side) {
            try {
                if (npc.isDead()) continue
                val focus = npc.getCombatTarget()
                // "Engaged" = has a live target within chase range. Engaged units steer toward that
                // target; everyone else marches their waypoints.
                // A target player who has ducked into the keep is no longer engageable — the goblin
                // drops them and resumes its march (and the keep leash pulls it back out if it
                // followed across the line). Keeps the spawn courtyard a true safe haven.
                val engaged = npc.isAttacking() && focus != null && isAlivePawn(world, focus) &&
                    dist(npc.tile, focus.tile) <= CHASE_RANGE &&
                    !(keep != null && focus is Player && keep.contains(focus.tile))

                val inMelee = engaged && dist(npc.tile, focus!!.tile) <= 1
                // Progress is measured toward what the unit is ACTUALLY trying to reach: its target
                // while engaged, else its next waypoint. (Measuring waypoint distance for an engaged
                // unit made every chase read as "stuck" within STUCK_LIMIT ticks, so units abandoned
                // a target mid-approach and marched off — looking like they lock onto a foe a ways
                // away and bound around without ever landing a hit.)
                val march: Tile? = if (engaged) null else marchBase(npc)
                val goal: Tile? = if (engaged) focus!!.tile else march
                val curDist = if (goal != null) dist(npc.tile, goal) else 0
                // Identity of the pursued thing: the target Pawn while engaged (so a moving target
                // keeps the same baseline), else the waypoint tile. Switching it resets "best dist".
                val goalKey: Any = if (engaged) focus!! else (goal ?: npc.tile)
                val prevKey = lastGoalByNpc.put(npc, goalKey)
                val goalChanged = prevKey == null || prevKey != goalKey
                val bestSoFar = if (goalChanged) Int.MAX_VALUE else (bestDistByNpc[npc] ?: Int.MAX_VALUE)
                val progressed = goalChanged || curDist < bestSoFar
                bestDistByNpc[npc] = minOf(bestSoFar, curDist)
                val stuck = if (progressed || inMelee) 0 else (stuckTicksByNpc[npc] ?: 0) + 1
                stuckTicksByNpc[npc] = stuck
                val unreachableLock = stuck >= STUCK_LIMIT

                if (stuck >= UNSTICK_LIMIT) {
                    // NO teleporting — units warping (and goblins snapping to the castle goal)
                    // looked broken. Just disengage an unreachable foe and re-issue a normal
                    // pathfinder walk toward the objective; the flow field routes around terrain,
                    // so a walk frees them without the jump.
                    if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }
                    val toward = marchBase(npc)
                    if (toward != null && dist(npc.tile, toward) > 1) npc.walkTo(walkableNear(world, toward))
                    stuckTicksByNpc[npc] = 0
                    continue
                }

                // Keep fighting the current target while engaged and not stuck on an unreachable foe.
                if (!unreachableLock && engaged) continue

                // Pick a fresh target with the War Brain's scorer.
                val living = if (unreachableLock) emptyList()
                    else enemyPool.filter { isAlivePawn(world, it) && dist(npc.tile, it.tile) <= engageRange }
                val enemy = TargetSelector.choose(npc, living, focusCount, objective, weights)
                if (enemy != null) {
                    npc.attack(enemy)
                    focusCount[enemy] = (focusCount[enemy] ?: 0) + 1
                } else {
                    if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }
                    // No enemy in engage range: advance. Knights steer toward the nearest goblin
                    // (sally out); goblins follow the flow field / waypoints toward Zo. Hand the
                    // pathfinder a stand-able destination so it routes AROUND obstacles.
                    val base = march ?: continue
                    if (dist(npc.tile, base) > 1) npc.walkTo(walkableNear(world, base))
                }
            } catch (e: Exception) {
                logger.error(e) { "WarFront '$frontId' combat step failed for npc id=${npc.id}" }
            }
        }
    }

    /**
     * Pull any attacker that has crossed into the protected [keep] back OUT: drop its target and
     * walk it to the goal (the gate, which sits just EAST of the keep), so a goblin only ever
     * touches the keep for the one tick it takes to get yanked back. Players in the keep are already
     * filtered from the goblin target pool, so this only catches the rare over-pather. Knights are
     * never passed here — they defend the keep freely.
     */
    private fun leashGoblinsFromKeep(world: World, goblins: List<Npc>, keep: Area) {
        val exit = attackerObjectives.lastOrNull() ?: return
        for (npc in goblins) {
            if (!isAlive(world, npc) || !keep.contains(npc.tile)) continue
            if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }
            npc.walkTo(walkableNear(world, exit))
        }
    }

    /** Soft de-stacking (unchanged): units prefer their own tile and sidestep off a stack. */
    private fun spreadOut(world: World, side: List<Npc>, objectives: List<Tile>, occ: HashMap<Long, Int>) {
        for (npc in side) {
            try {
                if (npc.isDead()) continue
                val here = world.npcTileKey(npc.tile.x, npc.tile.z, npc.tile.height)
                val hereCount = occ[here] ?: 0
                if (hereCount <= 1) continue

                val target = npc.getCombatTarget()
                // An adjacent target locks the unit into staying adjacent (don't de-stack out of
                // melee); the PULL direction is the target itself when it has one, else the next
                // waypoint — so a sidestep never drags an approaching unit off its target.
                val adjacentTile = target?.takeIf { dist(npc.tile, it.tile) <= 1 }?.tile
                val toward = target?.tile ?: nextWaypoint(npc, objectives)

                var best: Tile? = null
                var bestCount = Int.MAX_VALUE
                var bestPull = Int.MAX_VALUE
                for (dir in Direction.RS_ORDER) {
                    if (!world.canTraverse(npc.tile, dir, npc)) continue
                    val nbr = npc.tile.transform(dir.getDeltaX(), dir.getDeltaZ())
                    if (adjacentTile != null && dist(nbr, adjacentTile) > 1) continue
                    val k = world.npcTileKey(nbr.x, nbr.z, nbr.height)
                    val count = occ[k] ?: 0
                    val pull = if (toward != null) dist(nbr, toward) else 0
                    if (count < bestCount || (count == bestCount && pull < bestPull)) {
                        bestCount = count; bestPull = pull; best = nbr
                    }
                }
                val dest = best ?: continue
                if (bestCount + 1 >= hereCount) continue
                npc.walkTo(dest)
                occ[here] = hereCount - 1
                occ[world.npcTileKey(dest.x, dest.z, dest.height)] = bestCount + 1
            } catch (e: Exception) {
                logger.error(e) { "WarFront '$frontId' spread step failed for npc id=${npc.id}" }
            }
        }
    }

    private fun isAlive(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

    private fun isAlivePawn(world: World, pawn: Pawn): Boolean = when (pawn) {
        is Npc -> isAlive(world, pawn)
        is Player -> pawn.index >= 0 && !pawn.isDead() && zone.contains(pawn.tile)
        else -> false
    }

    /** Every goblin currently in the zone (war or natural) — knights' prey. */
    private fun goblinsInZone(world: World): List<Npc> {
        val out = mutableListOf<Npc>()
        for (i in 0 until world.npcs.capacity) {
            val npc = world.npcs[i] ?: continue
            if (!zone.contains(npc.tile)) continue
            val name = npc.def.name?.lowercase() ?: continue
            if (name.contains("goblin") && !name.contains("cook")) out += npc
        }
        return out
    }

    /**
     * Nearest stand-able tile to [t] (the tile itself if already clear), searched in
     * expanding rings out to [SNAP_RADIUS]. Uses the engine's own walkability predicate
     * (`!isClipped` — the same one [World.findRandomTileAround] uses to place NPCs), so a
     * waypoint or hop nudged into the river / a wall / a tree is corrected to dry ground
     * rather than stranding the unit. Falls back to [t] if nothing clear is within range.
     */
    private fun walkableNear(world: World, t: Tile): Tile = world.snapToWalkable(t, maxRadius = SNAP_RADIUS)

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private companion object {
        const val GOBLIN_ENGAGE_RANGE = 12
        const val KNIGHT_ENGAGE_RANGE = 14
        const val CHASE_RANGE = 18
        const val STUCK_LIMIT = 3
        const val UNSTICK_LIMIT = 5
        const val SPAWN_RADIUS = 2
        const val SNAP_RADIUS = 5 // rings searched for dry ground when snapping a blocked waypoint/hop
        val AGGRO_ANY: (Npc, Player) -> Boolean = { _, _ -> true }
    }
}
