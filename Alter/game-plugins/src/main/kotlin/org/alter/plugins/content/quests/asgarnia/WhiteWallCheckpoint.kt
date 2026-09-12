package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **White Knight checkpoint** outside Falador's north gate — the Asgarnian front as the player
 * first meets it in [AtTheWhiteWall] — and the **Kinshra raid** on it.
 *
 * Shared-world, no instance: four stock White Knights hold posts across the gate road, a wounded
 * soldier and a nurse sit behind them, crates and spiky barricades dress the position (existing
 * objects, spawned dynamically — no cache edit). Everything is **presence-gated** like the goblin
 * camp and the world spawns: nothing is maintained unless a real player is near, and the garrison
 * stands down when the road empties. The dressing (objects + the two non-combat npcs) is spawned
 * once and left.
 *
 * The raid runs only while a player on the quest's DEFEND step stands at the gate: stock Black
 * Knights (renamed "Kinshra raider", loot-less, stats raised a notch) appear on the field north of
 * the fence and push for the gate in a rolling stream — a few at a time, replenished as they fall —
 * so a lone player can never be stranded with nothing left to hit, and the White Knights out-
 * damaging them never costs a kill ([AtTheWhiteWallPlugin] credits every DEFEND-step player who drew
 * blood). Raiders go for defenders first (at most a few per player), then the knights; the knights
 * hunt the nearest raider and are recalled to their posts if they stray. When the last defender at
 * the gate finishes or leaves, the raid withdraws.
 *
 * Runs inside the plugin's guarded timer; never touches the engine aggro path (only the explicit
 * `Pawn.attack`), exactly like `GoblinCampPlugin` / `HostileZone`.
 */
object WhiteWallCheckpoint {

    /** Tags the raid's spawns — the only Black Knights that count for the DEFEND step. */
    val RAIDER_ATTR = AttributeKey<Boolean>()

    const val WHITE_KNIGHT = "npc.white_knight"      // 1798
    private const val RAIDER_NPC = "npc.black_knight"  // 516
    private const val RAIDER_NAME = "Kinshra raider"
    private const val WOUNDED = "npc.wounded_soldier"  // 6826 — the Burthorpe stretcher case
    private const val NURSE = "npc.nurse_sarah"        // 1152

    /** The checkpoint: the road just outside the north gate (gate opening x2964-2967, z3392-3394). */
    val CENTRE = Tile(2965, 3398, 0)

    /** The regions the checkpoint straddles — force-loaded before any dressing is placed. */
    private val REGIONS = intArrayOf(11829, 11828)

    const val TICK = 3 // ~1.8s upkeep cadence (a raid needs to feel alive; the goblin camp's 5 is for ambience)

    private const val ACTIVATION_RADIUS = 40
    private const val DEFEND_RADIUS = 24
    private const val KNIGHT_RESPAWN = 8 // timer ticks (~14s) from a knight's death to its respawn
    private const val KNIGHT_WALK = 1

    // --- the balance (TUNE in-game): a knight beats a raider one-on-one but slowly; the player's blows decide it ---
    private const val KN_HP = 90; private const val KN_ATK = 70; private const val KN_STR = 60; private const val KN_DEF = 70
    private const val RD_HP = 60; private const val RD_ATK = 48; private const val RD_STR = 44; private const val RD_DEF = 42

    private const val RAIDERS_ALIVE = 6      // the stream keeps this many on the field
    private const val RAIDERS_PER_SPAWN = 2  // per timer tick, while below the target
    private const val RAIDERS_PER_DEFENDER = 3
    private const val ENGAGE = 14
    private const val KNIGHT_ENGAGE = 12
    private const val KNIGHT_LEASH = 14
    private const val RAIDER_LEASH = 34
    private const val SHOUT_EVERY = 4 // timer ticks

    /** Knight posts across the gate road (tile + facing); the road itself (x2964-2966) stays open. */
    private val KNIGHT_POSTS = listOf(
        Tile(2963, 3397, 0) to Direction.NORTH,
        Tile(2967, 3397, 0) to Direction.NORTH,
        Tile(2961, 3399, 0) to Direction.NORTH,
        Tile(2969, 3399, 0) to Direction.NORTH,
    )

    /** Where raiders appear — the field north of the first fence line (z3400), pushing south. */
    private val STAGING = listOf(
        Tile(2960, 3410, 0), Tile(2963, 3411, 0), Tile(2966, 3410, 0), Tile(2969, 3411, 0),
        Tile(2962, 3413, 0), Tile(2967, 3413, 0), Tile(2964, 3409, 0),
    )

    /** The tile the raiders push for when nobody is in reach. */
    private val PUSH_TO = Tile(2965, 3400, 0)

    /** Existing objects as checkpoint dressing: id → tile → orientation. Crates (354) flank the road
     *  outside and inside the gate; spiky barricades (4421, Castle Wars) flank the fence line. */
    private val DRESSING = listOf(
        Triple(354, Tile(2960, 3396, 0), 0),
        Triple(354, Tile(2961, 3396, 0), 1),
        Triple(354, Tile(2970, 3396, 0), 2),
        Triple(354, Tile(2971, 3396, 0), 0),
        Triple(354, Tile(2962, 3389, 0), 1),
        Triple(354, Tile(2969, 3389, 0), 3),
        Triple(4421, Tile(2960, 3402, 0), 0),
        Triple(4421, Tile(2959, 3402, 0), 0),
        Triple(4421, Tile(2970, 3402, 0), 0),
        Triple(4421, Tile(2971, 3402, 0), 0),
    )
    private const val SCENERY_TYPE = 10

    private val KNIGHT_LINES = listOf("Hold the gate!", "Kinshra!", "Don't let them through!")
    private val RAIDER_LINES = listOf("Break the line!", "Keep them pinned!")

    private class Post(val home: Tile, val face: Direction) {
        var npc: Npc? = null
        var respawnIn = 0
    }

    private val posts = KNIGHT_POSTS.map { (t, d) -> Post(t, d) }
    private val raiders = ArrayList<Npc>()
    private var dressed = false
    private var shoutIn = 0

    // --- queries ------------------------------------------------------------------------------

    fun isRaider(npc: Npc): Boolean = npc.attr[RAIDER_ATTR] == true

    /** Within [radius] tiles of the checkpoint, on the ground floor. */
    fun isNear(p: Player, radius: Int = DEFEND_RADIUS): Boolean = p.tile.isWithinRadius(CENTRE, radius)

    // --- quest hooks --------------------------------------------------------------------------

    /** DEFEND entered: the knights sound the alarm; the raid itself streams in on the next tick. */
    fun alarm(p: Player) {
        val world = p.world
        val standing = posts.mapNotNull { it.npc }.filter { alive(world, it) }
        standing.getOrNull(0)?.forceChat("Movement!")
        standing.getOrNull(1)?.forceChat("Kinshra!")
        localMessage(world, "<col=cc2222>A Kinshra raiding party is moving on the checkpoint — help the White Knights hold the gate!</col>")
    }

    /** DEFEND cleared for [p]: the raid breaks for them; the knight has a word (re-askable at the checkpoint). */
    fun onDefenderDone(p: Player) {
        p.message("<col=801700>The Kinshra raiders withdraw from the checkpoint.</col>")
        p.queue { with(AtTheWhiteWall) { knightAfterFight(p) } }
    }

    // --- the tick -----------------------------------------------------------------------------

    fun tick(world: World) {
        if (!playerNear(world)) {
            standDown(world)
            return
        }
        if (!dressed) dress(world)
        maintainKnights(world)
        pruneRaiders(world)
        val defenders = defenders(world)
        if (defenders.isEmpty()) {
            if (raiders.isNotEmpty()) withdraw(world)
        } else {
            maintainRaiders(world)
            driveRaiders(world, defenders)
        }
        driveKnights(world)
        shouts(world)
    }

    /** Any real player within [ACTIVATION_RADIUS] of the checkpoint. */
    private fun playerNear(world: World): Boolean {
        var near = false
        world.players.forEach { p ->
            if (!near && p.entityType.isHumanControlled && p.isOnline && !p.invisible && p.tile.isWithinRadius(CENTRE, ACTIVATION_RADIUS)) near = true
        }
        return near
    }

    /** Players on the quest's DEFEND step standing at the gate. */
    private fun defenders(world: World): List<Player> {
        val out = ArrayList<Player>()
        world.players.forEach { p ->
            if (p.entityType.isHumanControlled && p.isOnline && p.tile.isWithinRadius(CENTRE, DEFEND_RADIUS) &&
                QuestEngine.stepId(p, AtTheWhiteWall) == AtTheWhiteWall.S_DEFEND
            ) out += p
        }
        return out
    }

    // --- dressing -----------------------------------------------------------------------------

    /** Once: force-load the regions' collision (a chunk built later would drop what we place), then the props + the two stretcher npcs. */
    private fun dress(world: World) {
        dressed = true
        runCatching { world.definitions.loadRegions(world, world.chunks, REGIONS) }
            .onFailure { logger.error(it) { "[WHITE WALL] collision load failed for the checkpoint regions" } }
        for ((id, tile, rot) in DRESSING) {
            runCatching { world.spawn(DynamicObject(id, SCENERY_TYPE, rot, tile)) }
                .onFailure { logger.warn(it) { "[WHITE WALL] could not place object $id at ${tile.x},${tile.z}" } }
        }
        spawnStill(world, WOUNDED, Tile(2959, 3397, 0), Direction.NORTH)
        spawnStill(world, NURSE, Tile(2960, 3398, 0), Direction.WEST)
        logger.info { "[WHITE WALL] checkpoint dressed at the Falador north gate (${DRESSING.size} props)." }
    }

    /** A non-combat npc that just stands there for the rest of the uptime. */
    private fun spawnStill(world: World, key: String, tile: Tile, face: Direction) {
        runCatching {
            val npc = Npc(getRSCM(key), tile, world)
            npc.walkRadius = 0
            npc.lastFacingDirection = face
            world.spawn(npc)
            npc.respawns = false
            npc.setActive(true)
        }.onFailure { logger.warn(it) { "[WHITE WALL] could not spawn '$key' at ${tile.x},${tile.z}" } }
    }

    // --- the garrison -------------------------------------------------------------------------

    private fun maintainKnights(world: World) {
        for (post in posts) {
            val n = post.npc
            if (n != null) {
                if (n.index < 0 || !world.npcs.contains(n)) { // killed since last tick
                    post.npc = null
                    post.respawnIn = KNIGHT_RESPAWN
                }
            } else if (post.respawnIn <= 0 || --post.respawnIn == 0) {
                post.npc = spawnKnight(world, post)
            }
        }
    }

    private fun spawnKnight(world: World, post: Post): Npc? = runCatching {
        val npc = Npc(getRSCM(WHITE_KNIGHT), post.home, world)
        npc.walkRadius = KNIGHT_WALK
        npc.lastFacingDirection = post.face
        npc.routeLogic = 1 // smarter pathing so the knight can close on a raider
        world.spawn(npc)
        // MUST be after world.spawn: setNpcDefaults() resets combatDef + HP to the cache default.
        npc.combatDef = npc.combatDef.copy(hitpoints = KN_HP, attack = KN_ATK, strength = KN_STR, defence = KN_DEF, attackSpeed = 4)
        applyStats(npc, KN_HP, KN_ATK, KN_STR, KN_DEF)
        // We own the lifecycle (respawn via the tick); engine respawn stays OFF so a knight can't
        // resurrect after the garrison stands down.
        npc.respawns = false
        npc.setActive(true)
        npc
    }.onFailure { logger.error(it) { "[WHITE WALL] could not spawn a checkpoint knight" } }.getOrNull()

    private fun applyStats(npc: Npc, hp: Int, atk: Int, str: Int, def: Int) {
        npc.stats.setMaxLevel(NpcSkills.ATTACK, atk); npc.stats.setCurrentLevel(NpcSkills.ATTACK, atk)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, str); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, str)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, def); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def)
        npc.setCurrentHp(hp)
    }

    private fun driveKnights(world: World) {
        for (post in posts) {
            val k = post.npc ?: continue
            if (!alive(world, k)) continue

            // Recall a knight that strayed too far chasing — the checkpoint holds the gate, not the field.
            if (k.tile.getDistance(post.home) > KNIGHT_LEASH) {
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                k.walkTo(post.home)
                continue
            }

            // Already locked onto a live raider? The engine drives the fight.
            val cur = k.getCombatTarget() as? Npc
            if (k.isAttacking() && cur != null && isRaider(cur) && alive(world, cur)) continue

            val foe = nearestRaider(k.tile, KNIGHT_ENGAGE)
            if (foe != null) {
                k.attack(foe)
            } else if (k.isAttacking()) {
                // A player picked a fight with the garrison — it is a stock attackable knight; leave the engine to it.
                if (k.getCombatTarget() !is Player) { k.removeCombatTarget(); k.resetFacePawn() }
            } else if (k.tile.getDistance(post.home) > 1) {
                k.walkTo(post.home)
            }
        }
    }

    /** Despawn the garrison and any raid when nobody is around (keeps npc slots / CPU free). */
    private fun standDown(world: World) {
        for (post in posts) {
            val n = post.npc ?: continue
            if (n.index >= 0 && world.npcs.contains(n)) world.remove(n)
            post.npc = null
            post.respawnIn = 0
        }
        if (raiders.isNotEmpty()) removeRaiders(world)
    }

    // --- the raid -----------------------------------------------------------------------------

    private fun pruneRaiders(world: World) {
        raiders.removeAll { !alive(world, it) }
    }

    private fun maintainRaiders(world: World) {
        if (raiders.size >= RAIDERS_ALIVE) return
        val fresh = raiders.isEmpty()
        val n = minOf(RAIDERS_PER_SPAWN, RAIDERS_ALIVE - raiders.size)
        repeat(n) { spawnRaider(world, STAGING[world.random(STAGING.size - 1)])?.let { raiders += it } }
        if (fresh && raiders.isNotEmpty()) {
            posts.mapNotNull { it.npc }.firstOrNull { alive(world, it) }?.forceChat("Kinshra!")
            localMessage(world, "<col=cc2222>Kinshra raiders push toward the checkpoint!</col>")
        }
    }

    private fun spawnRaider(world: World, tile: Tile): Npc? = runCatching {
        val npc = Npc(getRSCM(RAIDER_NPC), tile, world)
        npc.walkRadius = 0
        npc.lastFacingDirection = Direction.SOUTH
        npc.routeLogic = 1
        npc.attr[RAIDER_ATTR] = true
        npc.attr[NO_LOOT_ATTR] = true // a rolling stream of raiders next to a bank is not a drop faucet
        world.spawn(npc)
        npc.combatDef = npc.combatDef.copy(hitpoints = RD_HP, attack = RD_ATK, strength = RD_STR, defence = RD_DEF)
        applyStats(npc, RD_HP, RD_ATK, RD_STR, RD_DEF)
        npc.respawns = false
        npc.setActive(true)
        WarNpcNames.rename(npc, RAIDER_NAME)
        npc
    }.onFailure { logger.error(it) { "[WHITE WALL] could not spawn a Kinshra raider" } }.getOrNull()

    /** Raiders go for the defenders in reach (a few per player), then the knights, else push for the gate. */
    private fun driveRaiders(world: World, defenders: List<Player>) {
        // How many raiders already have each defender — the first pass keeps existing fights.
        val load = HashMap<Player, Int>()
        val idle = ArrayList<Npc>()
        for (r in raiders) {
            if (!r.tile.isWithinRadius(CENTRE, RAIDER_LEASH)) { // dragged off the field — it leaves the raid
                if (r.index >= 0 && world.npcs.contains(r)) world.remove(r)
                continue
            }
            val cur = r.getCombatTarget()
            val keep = r.isAttacking() && cur != null && alivePawn(world, cur) &&
                (cur !is Player || cur in defenders) // a player who finished or left is no longer a target
            if (keep) {
                if (cur is Player) load[cur] = (load[cur] ?: 0) + 1
            } else {
                if (r.isAttacking()) { r.removeCombatTarget(); r.resetFacePawn() }
                idle += r
            }
        }
        for (r in idle) {
            val player = defenders
                .filter { (load[it] ?: 0) < RAIDERS_PER_DEFENDER && it.tile.getDistance(r.tile) <= ENGAGE }
                .minByOrNull { it.tile.getDistance(r.tile) }
            val foe: Pawn? = player ?: nearestKnight(world, r.tile, ENGAGE)
            if (foe != null) {
                r.attack(foe)
                if (foe is Player) load[foe] = (load[foe] ?: 0) + 1
            } else if (r.tile.getDistance(PUSH_TO) > 1) {
                r.walkTo(PUSH_TO)
            }
        }
    }

    /** The raid breaks: nobody is defending the gate any more. */
    private fun withdraw(world: World) {
        removeRaiders(world)
        localMessage(world, "<col=801700>The Kinshra raiders withdraw from the checkpoint.</col>")
    }

    private fun removeRaiders(world: World) {
        for (r in raiders) if (r.index >= 0 && world.npcs.contains(r)) world.remove(r)
        raiders.clear()
    }

    private fun shouts(world: World) {
        if (raiders.isEmpty()) { shoutIn = 0; return }
        if (--shoutIn > 0) return
        shoutIn = SHOUT_EVERY
        posts.mapNotNull { it.npc }.filter { alive(world, it) }.randomOrNull()?.forceChat(KNIGHT_LINES.random())
        raiders.filter { alive(world, it) }.randomOrNull()?.forceChat(RAIDER_LINES.random())
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun alive(world: World, n: Npc): Boolean = n.index >= 0 && world.npcs.contains(n) && !n.isDead()

    private fun alivePawn(world: World, t: Pawn): Boolean = when (t) {
        is Npc -> alive(world, t)
        is Player -> t.isOnline && !t.isDead()
        else -> false
    }

    private fun nearestRaider(from: Tile, range: Int): Npc? =
        raiders.filter { it.index >= 0 && !it.isDead() && it.tile.getDistance(from) <= range }.minByOrNull { it.tile.getDistance(from) }

    private fun nearestKnight(world: World, from: Tile, range: Int): Npc? =
        posts.mapNotNull { it.npc }.filter { alive(world, it) && it.tile.getDistance(from) <= range }.minByOrNull { it.tile.getDistance(from) }

    private fun localMessage(world: World, text: String) {
        world.players.forEach { p ->
            if (p.entityType.isHumanControlled && p.isOnline && p.tile.isWithinRadius(CENTRE, ACTIVATION_RADIUS)) p.message(text)
        }
    }
}
