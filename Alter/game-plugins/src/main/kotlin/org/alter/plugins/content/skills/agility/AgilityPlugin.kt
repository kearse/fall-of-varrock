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
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * **The Mire Run** — the swamp's custom Agility course, a 5-obstacle lap circling the south
 * Mirepool (region 12849; tiles picked from the mapDump collision grid), with the OSRS
 * wilderness-agility-course **loot dispenser** system ported over (owner's ask, 2026-07-04):
 *
 *  - Pay a one-time **150,000 gp attunement fee** at the Ticket Dispenser (a gp sink).
 *  - Complete a lap (all 5 obstacles in order) → **Tag** the dispenser for loot.
 *  - Loot scales with CONSECUTIVE laps: quantities multiply up to ×5 at 60 laps (OSRS model).
 *    The streak resets on logout or a >5-minute gap between laps.
 *  - Loot table (OSRS wilderness dispenser flavour): coins + blighted supplies + a Mark of
 *    Grace chance — the "make good money with Agility" loop.
 *
 * Lap route (counterclockwise around the south pond, start at the dispenser on the waist):
 *  1. Log balance SOUTH over the pond's north neck  → the mid-pond isthmus
 *  2. Stepping stones over the south lobe           → the south bank
 *  3. Climbing rocks (west along the bank)
 *  4. Squeeze through the loose railing (west bank, heading north)
 *  5. Balancing rope EAST over the marsh waist      → back beside the dispenser
 *
 * All obstacle ids/actions verified via objCheck (Walk-on / Jump-to / Climb / Squeeze-through /
 * Walk-on). moveTo carries the player, so water dests don't need to be walkable.
 */
class AgilityPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Obstacle(
        val objId: Int, val action: String, val tile: Tile, val hops: List<Tile>, val xp: Double, val anim: Int,
        // Loc SHAPE the client renders. These old obstacle defs carry models ONLY for the shape in
        // their cache def (objCheck prints it as `shapes=`): spawn them as anything else and the
        // client keeps the loc but draws NOTHING — the server still sees it (the invisible-course
        // bug of 2026-07-04). 22 = ground decor, 0 = straight wall, 10 = centrepiece.
        val objType: Int, val rot: Int = 0,
    )

    private val course = listOf(
        // 1. Log balance across the pond's north neck (water at x3226, z3170-3171).
        Obstacle(3553, "Walk-on", Tile(3226, 3172, 0), listOf(Tile(3226, 3169, 0)), 20.0, ANIM_BALANCE, objType = GROUND_DECOR),
        // 2. Stepping stones over the south lobe (stones rendered on the water tiles).
        Obstacle(4411, "Jump-to", Tile(3226, 3168, 0), listOf(Tile(3226, 3166, 0), Tile(3226, 3164, 0), Tile(3226, 3163, 0)), 25.0, ANIM_JUMP, objType = GROUND_DECOR),
        // 3. Climbing rocks on the south bank.
        Obstacle(2236, "Climb", Tile(3220, 3163, 0), listOf(Tile(3217, 3163, 0)), 22.0, ANIM_CLIMB, objType = GROUND_DECOR),
        // 4. Loose railing on the west bank (a wall-shape loc; rot 1 lies it E-W across the path).
        Obstacle(2186, "Squeeze-through", Tile(3214, 3167, 0), listOf(Tile(3214, 3170, 0)), 22.0, ANIM_SQUEEZE, objType = WALL, rot = 1),
        // 5. Balancing rope east over the marsh waist, landing by the dispenser.
        Obstacle(3551, "Walk-on", Tile(3215, 3173, 0), listOf(Tile(3222, 3174, 0)), 25.0, ANIM_BALANCE, objType = GROUND_DECOR, rot = 1),
    )

    /** Extra stepping-stone dressing on the water (visual only — obstacle 2 owns the clicks). */
    private val stoneDressing = listOf(Tile(3226, 3166, 0), Tile(3226, 3164, 0))

    private val dispenserTile = Tile(3226, 3174, 0)

    // Runtime lap state.
    private val lapStep = AttributeKey<Int>()      // next expected obstacle index
    private val lapReady = AttributeKey<Boolean>() // completed a lap; dispenser tag pending
    private val lapStreak = AttributeKey<Int>()    // consecutive laps (drives the multiplier)
    private val lastLapAt = AttributeKey<Long>()   // wall-clock of the last completed lap

    /** Dispenser loot: weighted picks, quantities scaled by the streak multiplier. */
    private data class Loot(val key: String, val min: Int, val max: Int, val weight: Int)
    // OSRS wilderness-dispenser pool shape, but REGULAR consumables (owner's ask 2026-07-04 —
    // blighted versions felt like junk outside the wildy).
    private val lootTable = listOf(
        Loot("item.anglerfish", 3, 5, 30),
        Loot("item.cooked_karambwan", 3, 5, 25),
        Loot("item.super_restore4", 1, 2, 20),
        Loot("item.manta_ray", 2, 4, 15),
        Loot("item.blighted_teleport_spell_sack", 1, 3, 7),
        Loot("item.mark_of_grace", 1, 1, 3),
    ).filter { resolves(it.key) }

    init {
        onWorldInit {
            course.forEach { o -> world.spawn(DynamicObject(id = o.objId, type = o.objType, rot = o.rot, tile = o.tile)) }
            stoneDressing.forEach { world.spawn(DynamicObject(id = STONE_ID, type = GROUND_DECOR, rot = 0, tile = it)) }
            world.spawn(DynamicObject(id = DISPENSER_ID, type = OBJ_TYPE, rot = 0, tile = dispenserTile))
            logger.info { "mire-run: course spawned (5 obstacles + dispenser at $dispenserTile)." }
        }
        course.forEachIndexed { idx, o ->
            try {
                onObjOption(obj = o.objId, option = o.action) {
                    // This plugin owns the whole (id, option) binding, but these ids also exist at
                    // real OSRS spots (4411 alone has 22 cache placements — the clash that used to
                    // kill AgilityShortcutPlugin at load). Dispatch by the clicked tile: course
                    // placements run the lap; remote stepping stones get a plain hop instead of
                    // being teleported to the Mire.
                    val clicked = player.getInteractingGameObj().tile
                    when {
                        isCoursePlacement(o, clicked) -> player.queue { traverse(this, player, idx, o) }
                        o.objId == STONE_ID -> player.queue { stoneHop(this, player, clicked) }
                        else -> player.message("Nothing interesting happens.")
                    }
                }
            } catch (e: Exception) {
                logger.warn { "mire-run: couldn't bind '${o.action}' on ${o.objId}" }
            }
        }
        try {
            onObjOption(obj = DISPENSER_ID, option = "Tag") { player.queue { tagDispenser(this, player) } }
        } catch (e: Exception) {
            logger.warn { "mire-run: couldn't bind the dispenser 'Tag'" }
        }
        onLogout {
            player.attr.remove(lapStreak); player.attr.remove(lapReady); player.attr.remove(lapStep)
        }
    }

    private suspend fun traverse(task: QueueTask, player: Player, idx: Int, o: Obstacle) {
        // Lock + reset the walk-to interaction so the repositions stick (see Player.teleport).
        player.lock = LockState.FULL
        player.resetInteractions()
        player.faceTile(o.tile)
        // Animated forced-movement glide per hop (ditch-style) — a bare moveTo reads as a
        // teleport-blink and made the course feel broken. Stones re-fire the jump per stone;
        // logs/ropes hold the balance-walk across the whole span.
        var from = player.tile
        for (hop in o.hops) {
            if (o.anim > 0) player.animate(o.anim)
            val dist = maxOf(abs(hop.x - from.x), abs(hop.z - from.z)).coerceAtLeast(1)
            val dur = (30 * dist).coerceIn(30, 240)
            player.forceMove(task, ForcedMovement.of(from, hop, dur / 2, dur, dirAngle(from, hop)))
            from = hop
        }
        player.animate(-1)
        player.unlock()
        player.addXp(Skills.AGILITY, o.xp)

        val expected = player.attr[lapStep] ?: 0
        if (idx == expected) {
            val next = idx + 1
            if (next >= course.size) {
                player.attr[lapStep] = 0
                completeLap(player)
            } else {
                player.attr[lapStep] = next
            }
        } else {
            player.attr[lapStep] = idx + 1 // resync from where they actually are
        }
    }

    private fun completeLap(player: Player) {
        player.addXp(Skills.AGILITY, LAP_BONUS)
        // Streak: consecutive if the previous lap was recent, else start over at 1.
        val now = System.currentTimeMillis()
        val last = player.attr[lastLapAt] ?: 0L
        val streak = if (now - last <= STREAK_WINDOW_MS) (player.attr[lapStreak] ?: 0) + 1 else 1
        player.attr[lapStreak] = streak
        player.attr[lastLapAt] = now
        player.attr[lapReady] = true
        player.message(
            "<col=801700>Lap complete!</col> +$LAP_BONUS bonus xp. Streak: $streak (x${"%.1f".format(multiplier(streak))} loot) — Tag the dispenser!",
        )
    }

    /** OSRS-style consecutive-lap scaling: ×1 → ×5 linearly, capped at [STREAK_CAP] laps. */
    private fun multiplier(streak: Int): Double = 1.0 + (minOf(streak, STREAK_CAP) - 1) * 4.0 / (STREAK_CAP - 1)

    private suspend fun tagDispenser(task: QueueTask, player: Player) {
        // One-time attunement fee (the wilderness course's 150k, ported — a clean gp sink).
        if (player.attr[MIRE_RUN_PAID_ATTR] != true) {
            when (task.options(
                player,
                "Pay 150,000 coins (one-time, permanent).",
                "Not now.",
                title = "Attune to the dispenser for lap rewards?",
            )) {
                1 -> {
                    if (player.inventory.remove(item = getRSCM("item.coins_995"), amount = ATTUNE_FEE, assureFullRemoval = true).hasFailed()) {
                        player.message("You need ${"%,d".format(ATTUNE_FEE)} coins to attune to the dispenser.")
                        return
                    }
                    player.attr[MIRE_RUN_PAID_ATTR] = true
                    player.message("<col=801700>The dispenser hums — you are attuned.</col> Complete laps and Tag it for rewards.")
                }
                else -> return
            }
            return
        }
        if (player.attr[lapReady] != true) {
            player.message("The dispenser is empty — complete a lap of the Mire Run first.")
            return
        }
        player.attr[lapReady] = false
        val streak = player.attr[lapStreak] ?: 1
        val mult = multiplier(streak)

        // Coins always; one weighted supply pick on top. Quantities scale with the streak.
        val coins = ((COINS_MIN + world.random(COINS_MAX - COINS_MIN)) * mult).toInt()
        grant(player, getRSCM("item.coins_995"), coins)
        pickLoot()?.let { loot ->
            val qty = ((loot.min + world.random(loot.max - loot.min)) * mult).toInt().coerceAtLeast(1)
            grant(player, getRSCM(loot.key), qty)
        }
        player.message("<col=4f9b4f>The dispenser pays out.</col> (${"%,d".format(coins)} coins + supplies, streak x${"%.1f".format(mult)})")
    }

    private fun pickLoot(): Loot? {
        if (lootTable.isEmpty()) return null
        var roll = world.random(lootTable.sumOf { it.weight } - 1)
        for (l in lootTable) {
            roll -= l.weight
            if (roll < 0) return l
        }
        return lootTable.last()
    }

    /** Add to the pack; overflow drops at the player's feet (never voided). */
    private fun grant(player: Player, id: Int, amount: Int) {
        if (amount <= 0) return
        val add = player.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - add.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, player.tile, player))
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
        const val OBJ_TYPE = 10      // centrepiece — only for defs with unrestricted shapes (the dispenser)
        const val GROUND_DECOR = 22  // shape the old obstacle defs carry their models under
        const val WALL = 0           // straight-wall shape (the loose railing)
        const val LAP_BONUS = 120.0
        const val DISPENSER_ID = 3581 // Ticket Dispenser — action "Tag" (objCheck-verified)
        const val STONE_ID = 4411     // Stepping stone dressing on the crossing
        // Canonical OSRS agility anims (verified in the rev-228 cache, same set as the shortcut plugin).
        const val ANIM_JUMP = 769     // leap onto a stepping stone
        const val ANIM_BALANCE = 762  // balance-walk across a log / rope
        const val ANIM_CLIMB = 828    // climb up rocks
        const val ANIM_SQUEEZE = 844  // squeeze through a gap
        const val ATTUNE_FEE = 150_000
        const val STREAK_CAP = 60          // laps at which the multiplier maxes out
        const val STREAK_WINDOW_MS = 5 * 60_000L // gap that breaks a streak
        const val COINS_MIN = 2_000
        const val COINS_MAX = 5_000
    }
}
