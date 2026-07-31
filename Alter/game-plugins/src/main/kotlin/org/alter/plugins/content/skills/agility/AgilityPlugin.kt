package org.alter.plugins.content.skills.agility

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.ForcedMovement
import org.alter.game.model.LockState
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.MIRE_RUN_PAID_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.MovementQueue
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.walkRoute
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM
import org.rsmod.routefinder.collision.CollisionStrategy
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Shared Mire Run dispenser state + the client-window driver (`lofmire` in the custom client).
 *
 * Same thin pattern as [org.alter.plugins.content.war.RankMenu]:
 *  - [open]/[refresh] pulse [OPEN_VARP] carrying the dispenser state; the client opens the window
 *    on the open bit's rising edge, and any non-zero pulse updates an already-open window in
 *    place (lap completions re-pulse so the streak/bank counters tick live).
 *  - Window actions come back as the public-chat token `::mire <attune|claim>`, which
 *    `MessagePublicHandler` routes to the `mireclick` command bound in [AgilityPlugin].
 */
object MireDispenser {
    /**
     * Overlay-open varp (docs/overlay-design-system.md §8). Packed:
     *   bit 0 open flag · bit 1 always-set data marker (a refresh pulse is never 0) ·
     *   bit 2 attuned · bits 3-8 banked laps (6 bits, cap 50) · bits 9-15 streak (display-capped 99).
     * Pulse-to-0 so a persisted varp can't re-open the window on login.
     */
    const val OPEN_VARP = 4631

    // Runtime lap state (per-player attributes; non-persistent by design — the streak and any
    // unclaimed bank die with the session, like the OSRS wilderness dispenser streak).
    val LAP_STEP = AttributeKey<Int>()    // next expected obstacle index
    val LAP_BANK = AttributeKey<Int>()    // completed-but-unclaimed laps (the "deposit")
    val LAP_STREAK = AttributeKey<Int>()  // consecutive laps (drives the multiplier)
    val LAST_LAP_AT = AttributeKey<Long>() // wall-clock of the last completed lap

    const val ATTUNE_FEE = 150_000
    const val MAX_BANK = 50            // unclaimed laps the dispenser holds
    const val STREAK_CAP = 60          // laps at which the multiplier maxes out
    const val STREAK_WINDOW_MS = 5 * 60_000L // gap that breaks a streak
    const val COINS_MIN = 2_000
    const val COINS_MAX = 5_000

    /** OSRS-style consecutive-lap scaling: ×1 → ×5 linearly, capped at [STREAK_CAP] laps. */
    fun multiplier(streak: Int): Double = 1.0 + (minOf(streak, STREAK_CAP).coerceAtLeast(1) - 1) * 4.0 / (STREAK_CAP - 1)

    fun open(p: Player) = pulse(p, open = true)

    /** Update an already-open window without opening a closed one. */
    fun refresh(p: Player) = pulse(p, open = false)

    private fun pulse(p: Player, open: Boolean) {
        val attuned = if (p.attr[MIRE_RUN_PAID_ATTR] == true) 1 else 0
        val bank = (p.attr[LAP_BANK] ?: 0).coerceIn(0, 63)
        val streak = (p.attr[LAP_STREAK] ?: 0).coerceIn(0, 99)
        val value = (if (open) 1 else 0) or 0b10 or (attuned shl 2) or (bank shl 3) or (streak shl 9)
        p.setVarp(OPEN_VARP, value)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}

/**
 * **The Mire Run** — the swamp's custom Agility course: a full 9-obstacle lap circling the south
 * Mirepool (region 12849; tiles picked from the mapDump collision grid), with the OSRS
 * wilderness-agility-course **loot dispenser** system ported over (owner's ask, 2026-07-04;
 * course expanded + dispenser window added 2026-07-19):
 *
 *  - Pay a one-time **150,000 gp attunement fee** at the Ticket Dispenser (a gp sink).
 *  - Complete a lap (all obstacles in order) → the dispenser **banks** the lap (up to 50).
 *  - **Tag** the dispenser to open the Mire Dispenser window (`lofmire`) and claim every banked
 *    lap at once. Loot scales with CONSECUTIVE laps: quantities multiply up to ×5 at 60 laps
 *    (OSRS model). The streak resets on logout or a >5-minute gap between laps.
 *  - Loot table (OSRS wilderness dispenser flavour): coins + supplies + a Mark of Grace chance —
 *    the "make good money with Agility" loop.
 *
 * Lap route (counterclockwise around the south pond, start at the dispenser on the waist):
 *  1. Log balance SOUTH over the pond's north neck  → the mid-pond isthmus
 *  2. Stepping stones over the south lobe           → the south bank
 *  3. Ropeswing WEST along the south bank
 *  4. Climbing rocks (west along the bank)
 *  5. Monkeybars across the south-west corner
 *  6. Squeeze through the loose railing (west bank, heading north)
 *  7. Balancing ledge shimmy up the west bank
 *  8. Balancing rope EAST over the marsh waist
 *  9. Log balance along the marsh path              → back beside the dispenser
 *
 * The original five obstacles' ids/actions were verified via objCheck (Walk-on / Jump-to / Climb /
 * Squeeze-through / Walk-on). The 2026-07-19 additions (ropeswing / monkeybars / ledge / second
 * log) could not be objCheck'd when authored (no cache in the work environment), so each carries a
 * LIST of candidate action strings and the course binds defensively: the first action present on
 * the def wins, and an obstacle whose def has none of them is logged and **dropped from the lap**
 * (the course shortens instead of dead-ending mid-lap). moveTo/forceMove carries the player, so
 * water dests don't need to be walkable; every new obstacle sits on a leg players already walk.
 *
 * **AFK laps (2026-07-21).** Clicking the FIRST obstacle (the log by the dispenser) starts a
 * hands-off run: [runLaps] auto-runs the bank between obstacles ([walkToObstacle]) and glides each
 * obstacle ([crossObstacle]), looping laps forever until the player clicks to walk away (which
 * fires `interruptQueues()` → the WEAK task's [QueueTask.terminate]) or logs out. Clicking any of
 * obstacles 2-9 directly still does a single crossing for manual play. The crossing itself is a
 * SINGLE [Player.forceMove] per obstacle (entry→exit) — the old per-hop chain re-issued a teleport
 * mid-glide every hop, which snapped the avatar forward/back/forward; one glide (the same shape the
 * wilderness ditch uses) renders clean and smooth.
 */
class AgilityPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Obstacle(
        val name: String, val objId: Int,
        // Candidate click actions, in preference order — the first one present on the cache def is
        // bound. Single-element lists are objCheck-verified; longer lists are the defensive picks.
        val actions: List<String>,
        val tile: Tile, val hops: List<Tile>, val xp: Double, val anim: Int,
        // Loc SHAPE the client renders. These old obstacle defs carry models ONLY for the shape in
        // their cache def (objCheck prints it as `shapes=`): spawn them as anything else and the
        // client keeps the loc but draws NOTHING — the server still sees it (the invisible-course
        // bug of 2026-07-04). 22 = ground decor, 0 = straight wall, 10 = centrepiece.
        val objType: Int, val rot: Int = 0,
    )

    private val course = listOf(
        // 1. Log balance across the pond's north neck (water at x3226, z3170-3171).
        Obstacle("log balance", 3553, listOf("Walk-on"), Tile(3226, 3172, 0), listOf(Tile(3226, 3169, 0)), 20.0, ANIM_BALANCE, objType = GROUND_DECOR),
        // 2. Stepping stones over the south lobe (stones rendered on the water tiles).
        Obstacle("stepping stones", 4411, listOf("Jump-to"), Tile(3226, 3168, 0), listOf(Tile(3226, 3166, 0), Tile(3226, 3164, 0), Tile(3226, 3163, 0)), 25.0, ANIM_JUMP, objType = GROUND_DECOR),
        // 3. Ropeswing west along the south bank (the Barbarian-Outpost swing def; centrepiece —
        //    it stands as regular interactive scenery at its real placements).
        Obstacle("ropeswing", 23131, listOf("Swing-on", "Swing"), Tile(3224, 3163, 0), listOf(Tile(3221, 3163, 0)), 25.0, ANIM_SWING, objType = CENTREPIECE),
        // 4. Climbing rocks on the south bank.
        Obstacle("climbing rocks", 2236, listOf("Climb"), Tile(3220, 3163, 0), listOf(Tile(3217, 3163, 0)), 22.0, ANIM_CLIMB, objType = GROUND_DECOR),
        // 5. Monkeybars over the south-west corner.
        Obstacle("monkeybars", 14941, listOf("Swing-across", "Cross", "Climb-across", "Swing-on"), Tile(3215, 3164, 0), listOf(Tile(3214, 3166, 0)), 25.0, ANIM_MONKEYBARS, objType = CENTREPIECE),
        // 6. Loose railing on the west bank (a wall-shape loc; rot 1 lies it E-W across the path).
        Obstacle("loose railing", 2186, listOf("Squeeze-through"), Tile(3214, 3167, 0), listOf(Tile(3214, 3170, 0)), 22.0, ANIM_SQUEEZE, objType = WALL, rot = 1),
        // 7. Balancing ledge shimmy up the west bank (Underground-Pass family, same decor shape
        //    as the verified rope/log defs).
        Obstacle("balancing ledge", 3559, listOf("Walk-on", "Walk-across", "Cross"), Tile(3214, 3171, 0), listOf(Tile(3214, 3172, 0)), 22.0, ANIM_LEDGE, objType = GROUND_DECOR),
        // 8. Balancing rope east over the marsh waist.
        Obstacle("balancing rope", 3551, listOf("Walk-on"), Tile(3215, 3173, 0), listOf(Tile(3222, 3174, 0)), 25.0, ANIM_BALANCE, objType = GROUND_DECOR, rot = 1),
        // 9. Second log balance along the marsh path, landing by the dispenser (3554 is the
        //    sibling id of the verified 3553 log — same Underground-Pass def family).
        Obstacle("log balance", 3554, listOf("Walk-on"), Tile(3223, 3174, 0), listOf(Tile(3225, 3174, 0)), 20.0, ANIM_BALANCE, objType = GROUND_DECOR, rot = 1),
    )

    /**
     * The obstacles that actually BOUND at startup, in lap order. Course logic (spawns, lap
     * sequence, xp) runs off this list, so an id whose def rejects every candidate action just
     * shortens the lap instead of breaking it.
     */
    private val live = ArrayList<Obstacle>()

    /** Set while a hands-off lap run is in progress; blocks duplicate loops and drives cleanup. */
    private val AFK_ACTIVE = AttributeKey<Boolean>()

    /** Extra stepping-stone dressing on the water (visual only — obstacle 2 owns the clicks). */
    private val stoneDressing = listOf(Tile(3226, 3166, 0), Tile(3226, 3164, 0))

    private val dispenserTile = Tile(3226, 3174, 0)

    /** Dispenser loot: weighted picks, quantities scaled by the streak multiplier. */
    private data class Loot(val key: String, val min: Int, val max: Int, val weight: Int)
    // OSRS wilderness-dispenser pool shape, but REGULAR consumables (owner's ask 2026-07-04 —
    // blighted versions felt like junk outside the wildy). Mirrored client-side in
    // lofmire/LofMireOverlay.LOOT — keep the two tables in step.
    private val lootTable = listOf(
        Loot("item.anglerfish", 3, 5, 30),
        Loot("item.cooked_karambwan", 3, 5, 25),
        Loot("item.super_restore4", 1, 2, 20),
        Loot("item.manta_ray", 2, 4, 15),
        Loot("item.blighted_teleport_spell_sack", 1, 3, 7),
        Loot("item.mark_of_grace", 1, 1, 3),
    ).filter { resolves(it.key) }

    init {
        // Bind first (collecting the obstacles that took), THEN spawn only the bound ones.
        course.forEach { o ->
            val bound = o.actions.firstOrNull { action ->
                try {
                    onObjOption(obj = o.objId, option = action) {
                        // This plugin owns the whole (id, option) binding, but these ids also exist
                        // at real OSRS spots (4411 alone has 22 cache placements — the clash that
                        // used to kill AgilityShortcutPlugin at load). Dispatch by the clicked
                        // tile: course placements run the lap; remote stepping stones get a plain
                        // hop instead of being teleported to the Mire.
                        val clicked = player.getInteractingGameObj().tile
                        when {
                            isCoursePlacement(o, clicked) -> {
                                val idx = live.indexOf(o)
                                when {
                                    // Only the first obstacle kicks off continuous AFK laps; the
                                    // rest are single crossings for anyone walking it by hand.
                                    idx == 0 -> player.queue(TaskPriority.WEAK) { runLaps(this, player) }
                                    idx > 0 -> player.queue { singleCross(this, player, idx, o) }
                                }
                            }
                            o.objId == STONE_ID -> player.queue { stoneHop(this, player, clicked) }
                            else -> player.message("Nothing interesting happens.")
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (bound != null) {
                live += o
            } else {
                logger.warn { "mire-run: no candidate action of ${o.actions} bound for ${o.name} (${o.objId}) — dropped from the lap" }
            }
        }

        onWorldInit {
            live.forEach { o -> world.spawn(DynamicObject(id = o.objId, type = o.objType, rot = o.rot, tile = o.tile)) }
            stoneDressing.forEach { world.spawn(DynamicObject(id = STONE_ID, type = GROUND_DECOR, rot = 0, tile = it)) }
            world.spawn(DynamicObject(id = DISPENSER_ID, type = CENTREPIECE, rot = 0, tile = dispenserTile))
            logger.info { "mire-run: course spawned (${live.size}/${course.size} obstacles + dispenser at $dispenserTile)." }
        }

        // Tagging the dispenser opens the Mire Dispenser window (attune + claim both live there).
        try {
            onObjOption(obj = DISPENSER_ID, option = "Tag") { MireDispenser.open(player) }
        } catch (e: Exception) {
            logger.warn { "mire-run: couldn't bind the dispenser 'Tag'" }
        }

        // The window's action channel ("::mire <attune|claim>" → mireclick, routed by
        // MessagePublicHandler). Also testable directly by typing ::mireclick attune.
        onCommand("mireclick", description = "Mire Dispenser window action (client overlay channel)") {
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "attune" -> attune(player)
                "claim" -> player.queue { claim(player) }
                "open" -> MireDispenser.open(player)
            }
        }
        onCommand("mirerun", description = "Open the Mire Dispenser window") {
            MireDispenser.open(player)
        }

        onLogout {
            player.attr.remove(MireDispenser.LAP_STREAK)
            player.attr.remove(MireDispenser.LAP_BANK)
            player.attr.remove(MireDispenser.LAP_STEP)
            player.attr.remove(AFK_ACTIVE)
        }
    }

    /**
     * Hands-off lap runner. Started by clicking the first obstacle (the player is already stood
     * adjacent to it — `ObjectPathAction` walked them there before this fires — so the first pass
     * crosses immediately). Runs as a WEAK queue task, so the moment the player clicks to walk
     * away the engine's `interruptQueues()` terminates it (→ [QueueTask.terminate] → [stopAfk]).
     */
    private suspend fun runLaps(task: QueueTask, player: Player) {
        if (live.isEmpty() || player.attr[AFK_ACTIVE] == true) return
        player.attr[AFK_ACTIVE] = true
        task.terminateAction = { stopAfk(player) } // fires on click-away / any interruptQueues()
        var idx = 0
        try {
            while (true) {
                crossObstacle(task, player, live[idx])
                awardAndAdvance(player, idx)
                val next = (idx + 1) % live.size
                // Run the bank to the next obstacle. walkRoute (not walkTo) so we don't terminate
                // our own task. A failed/stuck approach ends the run cleanly instead of gliding
                // the player across the map from wherever they got stranded.
                if (!walkToObstacle(task, player, live[next])) break
                idx = next
            }
        } finally {
            stopAfk(player) // covers the break above and an unwind on logout
        }
    }

    /** A single manual crossing (obstacles 2-9), for players walking the course by hand. */
    private suspend fun singleCross(task: QueueTask, player: Player, idx: Int, o: Obstacle) {
        crossObstacle(task, player, o)
        awardAndAdvance(player, idx)
    }

    /** Idempotent — safe to call from both [QueueTask.terminate] and the `finally` unwind. */
    private fun stopAfk(player: Player) {
        if (player.attr[AFK_ACTIVE] != true) return
        player.attr[AFK_ACTIVE] = false
        player.animate(-1)
        if (player.lock != LockState.NONE) player.unlock()
        // LAP_STEP / streak are left intact so a re-click resumes the lap where it left off.
    }

    /**
     * One clean glide across a single obstacle: entry (the player's ACTUAL tile) → exit
     * (`o.hops.last()`), a SINGLE [Player.forceMove] with one animation. The old code chained a
     * forceMove per hop, and each hop's `moveTo` re-issued a teleport while the previous hop's
     * exact-move was still rendering — that mid-glide teleport is what snapped the avatar
     * forward/back/forward. One glide (like [org.alter.plugins.content.objects.ditch] does) is
     * smooth. `forceMove` owns the lock (DELAY_ACTIONS → NONE), which also makes the cross atomic.
     */
    private suspend fun crossObstacle(task: QueueTask, player: Player, o: Obstacle) {
        val exit = o.hops.last()
        val from = player.tile
        player.faceTile(exit)
        val dist = maxOf(abs(exit.x - from.x), abs(exit.z - from.z)).coerceAtLeast(1)
        val dur = (30 * dist).coerceIn(30, 240) // delay2, in ~20ms client cycles
        if (o.anim > 0) player.animate(o.anim)
        player.forceMove(task, ForcedMovement.of(from, exit, clientDuration1 = 0, clientDuration2 = dur, directionAngle = dirAngle(from, exit)))
        player.animate(-1)
    }

    /** Award the obstacle's xp and advance the lap step (banking a lap on the wrap). */
    private fun awardAndAdvance(player: Player, idx: Int) {
        val o = live[idx]
        player.addXp(Skills.AGILITY, o.xp)
        val expected = player.attr[MireDispenser.LAP_STEP] ?: 0
        if (idx == expected) {
            val next = idx + 1
            if (next >= live.size) {
                player.attr[MireDispenser.LAP_STEP] = 0
                completeLap(player)
            } else {
                player.attr[MireDispenser.LAP_STEP] = next
            }
        } else {
            player.attr[MireDispenser.LAP_STEP] = idx + 1 // resync from where they actually are
        }
    }

    /**
     * Auto-run the bank to the next obstacle and report whether we actually got there. Routes to
     * the obstacle's object the same way a manual click does (the full route-finder overload, which
     * stops on the adjacent WALKABLE tile — never the water the obstacle sits on), so no fragile
     * per-obstacle approach tiles are needed. Draining the movement queue before returning also
     * guarantees the following [crossObstacle] never issues its teleport while a walk step is still
     * pending (the one thing that would re-introduce a snap).
     */
    private suspend fun walkToObstacle(task: QueueTask, player: Player, o: Obstacle): Boolean {
        val obj = world.getObject(o.tile, o.objType) ?: return false
        val def = obj.getDef()
        val route = world.smartRouteFinder.findRoute(
            level = player.tile.height,
            srcX = player.tile.x,
            srcZ = player.tile.z,
            destX = o.tile.x,
            destZ = o.tile.z,
            destWidth = def.sizeX,
            destLength = def.sizeY,
            srcSize = 1,
            collision = CollisionStrategy.Normal,
            locAngle = o.rot,
            locShape = o.objType,
            blockAccessFlags = def.clipMask,
        )
        player.walkRoute(route, MovementQueue.StepType.NORMAL)
        var cycles = 0
        while (player.hasMoveDestination() && cycles++ < ARRIVE_GUARD) task.wait(1)
        return route.success && player.tile.isWithinRadius(o.tile, 2)
    }

    private fun completeLap(player: Player) {
        player.addXp(Skills.AGILITY, LAP_BONUS)
        // Streak: consecutive if the previous lap was recent, else start over at 1.
        val now = System.currentTimeMillis()
        val last = player.attr[MireDispenser.LAST_LAP_AT] ?: 0L
        val streak = if (now - last <= MireDispenser.STREAK_WINDOW_MS) (player.attr[MireDispenser.LAP_STREAK] ?: 0) + 1 else 1
        player.attr[MireDispenser.LAP_STREAK] = streak
        player.attr[MireDispenser.LAST_LAP_AT] = now

        val bank = (player.attr[MireDispenser.LAP_BANK] ?: 0)
        if (bank < MireDispenser.MAX_BANK) {
            player.attr[MireDispenser.LAP_BANK] = bank + 1
            player.message(
                "<col=801700>Lap complete!</col> +$LAP_BONUS bonus xp. Streak: $streak " +
                    "(x${"%.1f".format(MireDispenser.multiplier(streak))} loot) — ${bank + 1} lap${if (bank + 1 > 1) "s" else ""} banked in the dispenser.",
            )
        } else {
            player.message(
                "<col=801700>Lap complete!</col> +$LAP_BONUS bonus xp. The dispenser is full " +
                    "(${MireDispenser.MAX_BANK} laps banked) — Tag it to claim before more can bank!",
            )
        }
        // Live-update an open dispenser window (a closed one ignores the open-bit-less pulse).
        MireDispenser.refresh(player)
    }

    /** One-time attunement fee (the wilderness course's 150k, ported — a clean gp sink). */
    private fun attune(player: Player) {
        if (!nearDispenser(player)) {
            player.message("You need to be at the Mire Run dispenser to do that.")
            return
        }
        if (player.attr[MIRE_RUN_PAID_ATTR] == true) {
            player.message("You are already attuned to the dispenser.")
            return
        }
        if (player.inventory.remove(item = getRSCM("item.coins_995"), amount = MireDispenser.ATTUNE_FEE, assureFullRemoval = true).hasFailed()) {
            player.message("You need ${"%,d".format(MireDispenser.ATTUNE_FEE)} coins to attune to the dispenser.")
            return
        }
        player.attr[MIRE_RUN_PAID_ATTR] = true
        player.message("<col=801700>The dispenser hums — you are attuned.</col> Complete laps and claim your rewards.")
        MireDispenser.refresh(player)
    }

    /** Pay out every banked lap at once, quantities scaled by the current streak multiplier. */
    private fun claim(player: Player) {
        if (!nearDispenser(player)) {
            player.message("You need to be at the Mire Run dispenser to do that.")
            return
        }
        if (player.attr[MIRE_RUN_PAID_ATTR] != true) {
            player.message("Attune to the dispenser first — it costs a one-time ${"%,d".format(MireDispenser.ATTUNE_FEE)} coins.")
            return
        }
        val bank = player.attr[MireDispenser.LAP_BANK] ?: 0
        if (bank <= 0) {
            player.message("The dispenser is empty — complete a lap of the Mire Run first.")
            return
        }
        player.attr[MireDispenser.LAP_BANK] = 0
        val streak = player.attr[MireDispenser.LAP_STREAK] ?: 1
        val mult = MireDispenser.multiplier(streak)

        // Per banked lap: coins always + one weighted supply pick, scaled by the streak. Rolls
        // aggregate into stacks so a 10-lap claim doesn't spray 20 partial stacks.
        var coins = 0
        val supplies = LinkedHashMap<Int, Int>()
        repeat(bank) {
            coins += ((MireDispenser.COINS_MIN + world.random(MireDispenser.COINS_MAX - MireDispenser.COINS_MIN)) * mult).toInt()
            pickLoot()?.let { loot ->
                val qty = ((loot.min + world.random(loot.max - loot.min)) * mult).toInt().coerceAtLeast(1)
                val id = getRSCM(loot.key)
                supplies[id] = (supplies[id] ?: 0) + qty
            }
        }
        grant(player, getRSCM("item.coins_995"), coins)
        supplies.forEach { (id, qty) -> grant(player, id, qty) }
        player.message(
            "<col=4f9b4f>The dispenser pays out $bank lap${if (bank > 1) "s" else ""}.</col> " +
                "(${"%,d".format(coins)} coins + supplies, streak x${"%.1f".format(mult)})",
        )
        MireDispenser.refresh(player)
    }

    private fun nearDispenser(player: Player): Boolean =
        player.tile.height == dispenserTile.height &&
            maxOf(abs(player.tile.x - dispenserTile.x), abs(player.tile.z - dispenserTile.z)) <= 6

    private fun pickLoot(): Loot? {
        if (lootTable.isEmpty()) return null
        var roll = world.random(lootTable.sumOf { it.weight } - 1)
        for (l in lootTable) {
            roll -= l.weight
            if (roll < 0) return l
        }
        return lootTable.last()
    }

    /**
     * Add a dispenser reward to the pack. Supplies are handed out **noted** so a whole claim
     * collapses into one stack per item — the fix for the "100 anglerfish dumped on the ground
     * because the pack was full" report: an un-noted stack of 100 needs 100 free slots, a noted
     * one needs a single slot. [Item.toNoted] is a graceful no-op for ids with no noted form
     * (coins, marks of grace), so those pass through unchanged. Any genuine overflow (a truly full
     * pack) still drops at the player's feet rather than being voided.
     */
    private fun grant(player: Player, id: Int, amount: Int) {
        if (amount <= 0) return
        val noted = Item(id).toNoted().id
        val add = player.inventory.add(item = noted, amount = amount, assureFullInsertion = false)
        val leftover = amount - add.completed
        if (leftover > 0) world.spawn(GroundItem(noted, leftover, player.tile, player))
    }

    /** Course objects are spawned at exact tiles; a same-id object anywhere else is world scenery. */
    private fun isCoursePlacement(o: Obstacle, t: Tile): Boolean =
        sameTile(t, o.tile) || (o.objId == STONE_ID && stoneDressing.any { sameTile(t, it) })

    private fun sameTile(a: Tile, b: Tile): Boolean = a.x == b.x && a.z == b.z && a.height == b.height

    /**
     * A real-OSRS stepping stone outside the course: the generic agility-shortcut hop (level-15
     * gate, land on the stone) that AgilityShortcutPlugin would have given 4411 if this plugin
     * didn't own the binding.
     */
    private suspend fun stoneHop(task: QueueTask, player: Player, stone: Tile) {
        if (player.getSkills().getCurrentLevel(Skills.AGILITY) < 15) {
            player.message("You need an Agility level of 15 to use this shortcut.")
            return
        }
        player.lock = LockState.FULL
        player.resetInteractions()
        player.faceTile(stone)
        player.animate(ANIM_JUMP)
        val dist = maxOf(abs(stone.x - player.tile.x), abs(stone.z - player.tile.z)).coerceAtLeast(1)
        val dur = (30 * dist).coerceIn(30, 120)
        player.forceMove(task, ForcedMovement.of(player.tile, stone, dur / 2, dur, dirAngle(player.tile, stone)))
        player.animate(-1)
        player.unlock()
        player.addXp(Skills.AGILITY, 6.0)
    }

    /** Cardinal facing for a forced-movement hop. */
    private fun dirAngle(from: Tile, to: Tile): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dir = when {
            abs(dx) >= abs(dz) && dx > 0 -> Direction.EAST
            abs(dx) >= abs(dz) && dx < 0 -> Direction.WEST
            dz > 0 -> Direction.NORTH
            else -> Direction.SOUTH
        }
        return dir.angle
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val CENTREPIECE = 10   // standard interactive-scenery shape (dispenser, swing, bars)
        const val GROUND_DECOR = 22  // shape the old obstacle defs carry their models under
        const val WALL = 0           // straight-wall shape (the loose railing)
        const val LAP_BONUS = 200.0  // raised from 120 with the 5→9-obstacle expansion
        const val DISPENSER_ID = 3581 // Ticket Dispenser — action "Tag" (objCheck-verified)
        const val STONE_ID = 4411     // Stepping stone dressing on the crossing
        // Max game cycles to wait for an auto-walk between obstacles to arrive (~15s) — a bad or
        // unreachable route can never spin the lap loop forever.
        const val ARRIVE_GUARD = 25
        // Canonical OSRS agility anims. The first four are verified in the rev-228 cache (same set
        // as the shortcut plugin); swing/bars/ledge are the canonical family ids picked without a
        // local cache — a wrong id plays no animation but never breaks the crossing.
        const val ANIM_JUMP = 769        // leap onto a stepping stone
        const val ANIM_BALANCE = 762     // balance-walk across a log / rope
        const val ANIM_CLIMB = 828       // climb up rocks
        const val ANIM_SQUEEZE = 844     // squeeze through a gap
        const val ANIM_SWING = 751       // ropeswing arc
        const val ANIM_MONKEYBARS = 744  // hand-over-hand across the bars
        const val ANIM_LEDGE = 756       // sideways ledge shimmy
    }
}
