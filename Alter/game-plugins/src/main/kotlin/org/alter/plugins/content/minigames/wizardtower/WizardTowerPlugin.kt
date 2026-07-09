package org.alter.plugins.content.minigames.wizardtower

import dev.openrune.cache.CacheManager.getNpc
import dev.openrune.cache.CacheManager.getObjectOrDefault
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.EntityType
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.collision.isClipped
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.StaticObject
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.magic.spellbook.unlockMageBooks
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Wizard Tower** — a repeatable instanced combat minigame and the reward gate for the special
 * spellbooks (the War-Prep Magic quest routes through it, and ANY first completion unlocks the
 * Ancient/Lunar/Arceuus books). A **Void Knight** on the mainland end of the tower bridge
 * (3113,3208) starts games: **solo** (a private instance) or **multi** (an open game up to
 * [MAX_PLAYERS] — later challengers who pick multi join the same game; muster on the bridge, then
 * push in together).
 *
 * Players spawn ON the bridge inside the instance, walk down to the island and in through the
 * (permanently open) tower door, then fight up floor by floor — **a floor's staircase is sealed
 * until every mage on it is dead** — to the Archmage at the top. Every mage pays out the
 * higher-tier runes the shops don't sell (loot goes to the killer); the tower is the rune faucet.
 * A 10-minute timer bounds each game.
 *
 * The instance uses [RaidInstance.allocateFloors]: each floor is a plane-0 island (stacked
 * multi-plane copies render every floor superimposed — see that function's doc), with the ground
 * island extended to include the island grounds + the bridge front. "Climbing" the staircases
 * teleports between islands ([handleClimb], wired through LadderPlugin).
 */
object WizardTower {

    // Set once at world init by the plugin; every run references its own instance.
    lateinit var world: World

    private val runs = mutableListOf<Run>()

    // ---- config (source-arena coords; translated into each instance) ----
    // The tower footprint (3x2 chunks, walls x3102-3117 z3153-3167) — copied once per FLOOR as
    // side-by-side plane-0 islands. GROUND_AREA additionally pulls in the island grounds and the
    // south end of the bridge (z3168-3175) for the ground island only, so games start outdoors.
    private val SOURCE_AREA = Area(3096, 3152, 3119, 3167)
    private val GROUND_AREA = Area(3096, 3152, 3119, 3175)
    private const val FLOOR_COUNT = 3
    const val MAX_PLAYERS = 5

    /** Where players spawn inside the instance: ON the bridge, just north of the island. */
    private val BRIDGE_SPAWNS = listOf(
        Tile(3113, 3174, 0), Tile(3114, 3174, 0), Tile(3113, 3173, 0),
        Tile(3114, 3173, 0), Tile(3113, 3175, 0),
    )

    private val BOSS_TILE = Tile(3110, 3161, 2) // the rug at the heart of the top floor
    val EXIT_TILE = Tile(3113, 3210, 0)         // live-map: beside the Void Knight at the bridge's mainland end

    /** The exit portal on the top floor (by the east wall — owner-picked spot; mapdump-verified
     *  open). Games have NO timer — mages respawn so you can farm runes as long as you like, and
     *  you leave through the portal (→ [EXIT_TILE], same spot as ::wizardtower) when you're done. */
    const val PORTAL_KEY = "object.portal_4150"
    private val PORTAL_TILE = Tile(3113, 3160, 2)

    /** Where the player lands when climbing UP to floor h (mapdump-verified open tiles beside
     *  each staircase on the DESTINATION floor). Index 0 unused. */
    private val UP_ARRIVAL = listOf(Tile(0, 0, 0), Tile(3104, 3161, 1), Tile(3105, 3162, 2))

    /** Where the player lands when climbing DOWN to floor h. Index 2 unused. */
    private val DOWN_ARRIVAL = listOf(Tile(3105, 3160, 0), Tile(3104, 3161, 1), Tile(0, 0, 0))

    /** Doorways stay OPEN for the whole game: these are stripped from every fresh instance
     *  (collision + client), which also lets the spawn flood-fill cover whole floors. */
    private val DOOR_NAMES = setOf("door", "large door", "gate")

    /**
     * A floor: its map level, the staircase [seed] tile the spawn flood-fill grows from, a HARD
     * [bounds] box the fill may not leave (keeps ground-floor spawns INSIDE the tower — the fill
     * would otherwise pour out the open door onto the island), enemy + count. Spawn tiles are
     * only ever tiles a player can physically WALK to from the stairs ([reachableFrom]) — which
     * rules out upper-floor void ("the floor looks empty") and the level-2 lesser-demon cage,
     * both of which fooled `!isClipped` tile scans.
     */
    private data class Floor(val height: Int, val seed: Tile, val bounds: Area, val mageKey: String, val count: Int)

    // Seeds are the STAIR LANDING tiles (same ones the climb teleports use), NOT the staircase
    // tile itself: the stair's own clipped footprint + the alcove walls seal the stair tile in
    // completely on floors 0-1, so a fill seeded there finds zero tiles ("no wizards").
    private val FLOORS = listOf(
        Floor(0, Tile(3105, 3160, 0), Area(3102, 3154, 3116, 3166), "npc.dark_wizard", 6),
        Floor(1, Tile(3104, 3161, 1), Area(3102, 3154, 3116, 3166), "npc.battle_mage", 8),
        Floor(2, Tile(3105, 3162, 2), Area(3102, 3154, 3114, 3165), "npc.ancient_wizard", 10),
    )

    /** The Archmage. NOT `archmage_sedridor` — Sedridor is a QUEST npc whose cache def has only
     *  Talk-to (players literally cannot attack him); the infernal mage is a real combat npc. */
    private const val BOSS_KEY = "npc.infernal_mage_443"

    private class Run(
        /** Everyone in the game. Solo = exactly one; multi = up to [MAX_PLAYERS], joinable live. */
        val players: MutableList<Player>,
        val multi: Boolean,
        val instance: RaidInstance,
        /** Every npc this run spawned. ONLY [endRun] removes entries (plus the boss on death) —
         *  the engine's death event fires AFTER the death animation plays out, so a "remove on
         *  isDead()" sweep here races it and the loot/boss hooks then can't find the npc's run
         *  (the no-drops bug). Mages RESPAWN (the farming loop), so they stay members for life. */
        val members: MutableSet<Npc> = mutableSetOf(),
        /** Mages killed and waiting on the engine's respawn timer: invisible + unattackable, but
         *  their hp is already reset (isDead() reads false) — the floor gate must ignore them. */
        val awaitingRespawn: MutableSet<Npc> = mutableSetOf(),
        var boss: Npc? = null,
        var portal: DynamicObject? = null,
        var bossDead: Boolean = false,
        var done: Boolean = false,
    )

    fun inRun(p: Player): Boolean = runs.any { p in it.players }

    /**
     * Start (or, for multi, join) a game. Multi games are OPEN: if one is live with a free slot,
     * the challenger drops straight into it at the bridge spawn; otherwise they open a new one
     * that later multi challengers can join.
     */
    fun enter(p: Player, multi: Boolean) {
        if (inRun(p)) { p.message("You're already inside the tower."); return }

        if (multi) {
            val open = runs.firstOrNull { it.multi && !it.done && it.players.size < MAX_PLAYERS }
            if (open != null) {
                open.players += p
                p.moveTo(spawnSpot(open, open.players.size - 1))
                open.players.forEach { it.message("<col=8f00ff>${p.username} joins the assault!</col> (${open.players.size}/$MAX_PLAYERS)") }
                briefing(p)
                return
            }
        }

        val instance = RaidInstance.allocateFloors(
            world, SOURCE_AREA, levels = FLOOR_COUNT, exitTile = EXIT_TILE, owner = p.uid,
            groundArea = GROUND_AREA,
        )
        if (instance == null) { p.message("The tower is crowded right now — try again shortly."); return }
        val run = Run(mutableListOf(p), multi, instance)
        openDoors(run)
        sealFloorEdges(run)
        FLOORS.forEach { spawnFloor(run, it) }
        spawnBoss(run)
        spawnPortal(run)
        runs += run
        p.moveTo(spawnSpot(run, 0))
        briefing(p)
        if (multi) {
            p.message("<col=8f00ff>This is an open game</col> — up to $MAX_PLAYERS can join you. Muster on the bridge, or push in alone.")
        }
    }

    private fun briefing(p: Player) {
        p.message("<col=8f00ff>Cross the bridge and take the tower.</col> Clear each floor's mages to climb — the Archmage holds the grimoire at the top.")
        p.message("Keep <col=801700>Protect from Magic</col> active and their spells will wash off you. The mages respawn — farm runes as long as you like, and leave through the portal on the top floor.")
    }

    /** No timer: the way out is the portal on the top floor (bound in the plugin below). */
    private fun spawnPortal(run: Run) {
        val id = runCatching { getRSCM(PORTAL_KEY) }.getOrNull() ?: return
        val portal = DynamicObject(id, 10, 0, snapWalkable(run.instance.translate(PORTAL_TILE)))
        world.spawn(portal)
        run.portal = portal
    }

    /** The top-floor exit portal: leaves the game (back to the Void Knight). Returns false if
     *  [p] isn't in a run (some other portal_4150 in the world — not ours to handle). */
    fun usePortal(p: Player): Boolean {
        runs.firstOrNull { p in it.players } ?: return false
        p.moveTo(EXIT_TILE)
        p.message("You step through the portal — the tower's magic seals behind you.")
        // tick() prunes players who left the instance; the run tears down once everyone is out.
        return true
    }

    private fun spawnSpot(run: Run, slot: Int): Tile =
        snapWalkable(run.instance.translate(BRIDGE_SPAWNS[slot % BRIDGE_SPAWNS.size]))

    /**
     * Strip every door in the fresh instance — doorways stay open for the whole game.
     *
     * Two-step removal per door: [World.remove] on the server's dynamic copy clears the wall
     * collision, but for a DYNAMIC entity the chunk merely cancels its pending add-update — no
     * delete is recorded, so any client arriving LATER still renders the cache's closed door
     * (and clicking it no-ops, since the server has no object there: the "can't open the door"
     * bug). The client's baseline for instance zones IS the cache, so we additionally push a
     * STATIC-style removal for the same loc — that one persists in the chunk's update list and
     * is replayed to every player who loads the zone.
     */
    private fun openDoors(run: Run) {
        val area = run.instance.map.area
        var removed = 0
        var x = area.bottomLeftX
        while (x < area.topRightX) {
            var z = area.bottomLeftY
            while (z < area.topRightY) {
                val chunk = world.chunks.get(Tile(x, z, 0), createIfNeeded = false)
                if (chunk != null) {
                    chunk.getEntities<DynamicObject>(EntityType.DYNAMIC_OBJECT)
                        .filter { (getObjectOrDefault(it.id).name ?: "").lowercase() in DOOR_NAMES }
                        .forEach { door ->
                            world.remove(door) // collision + cancel the dup add-update
                            // persistent cache-loc delete for late viewers
                            chunk.removeEntity(world, StaticObject(door.id, door.type, door.rot, door.tile), door.tile)
                            removed++
                        }
                }
                z += 8
            }
            x += 8
        }
        logger.info { "wizard-tower: opened (removed) $removed doors in instance @${area.bottomLeftX},${area.bottomLeftY}" }
    }

    /**
     * Walk-block every tile of the UPPER-floor islands whose source tile lies outside the floor
     * plate ([Floor.bounds]). Inside the copied chunk box but beyond the tower wall, the source
     * planes have open (flag-0) terrain — walkable-tile snaps (companion formation catch-up,
     * anything using `!isClipped`) happily placed pawns out there, floating beyond the walls.
     * Walk-block only (no projectile flag) so line-of-sight inside the room is untouched.
     */
    private fun sealFloorEdges(run: Run) {
        val flag = org.rsmod.routefinder.flag.CollisionFlag.BLOCK_WALK
        FLOORS.filter { it.height > 0 }.forEach { floor ->
            for (x in SOURCE_AREA.bottomLeftX..SOURCE_AREA.topRightX) {
                for (z in SOURCE_AREA.bottomLeftY..SOURCE_AREA.topRightY) {
                    if (floor.bounds.contains(x, z)) continue
                    val t = run.instance.translate(Tile(x, z, floor.height))
                    world.collision.add(t.x, t.z, t.height, flag)
                }
            }
        }
    }

    private fun spawnFloor(run: Run, floor: Floor) {
        val id = runCatching { getRSCM(floor.mageKey) }.getOrNull() ?: return
        val reachable = floorTiles(run, floor)
        // BFS order = distance rings from the stairs; an even stride spreads the pack across the
        // whole room instead of clumping them on the landing. Skip the first ring so the player
        // doesn't climb up INTO a mage.
        val pool = reachable.drop(6).ifEmpty { reachable }
        val tiles = if (pool.size <= floor.count) pool else List(floor.count) { pool[it * pool.size / floor.count] }
        // respawns=true: killed mages come back (engine respawnDelay, ~30s) — the farming loop.
        tiles.forEach { t -> run.members += spawn(id, t, respawns = true) }
        logger.info {
            "wizard-tower: floor h=${floor.height} '${floor.mageKey}' spawned ${tiles.size}/${floor.count} " +
                "(reachable=${reachable.size} from stairs @${run.instance.translate(floor.seed)})"
        }
        if (tiles.size < floor.count) {
            logger.warn { "wizard-tower: floor h=${floor.height} UNDER-SPAWNED — flood-fill found only ${reachable.size} reachable tiles" }
        }
    }

    private fun spawnBoss(run: Run) {
        val id = runCatching { getRSCM(BOSS_KEY) }.getOrNull() ?: return
        val top = FLOORS.last()
        val preferred = run.instance.translate(BOSS_TILE)
        // The rug at the heart of the top floor if the fill can reach it, else the nearest
        // reachable tile to it (never an unreachable pocket).
        val spot = floorTiles(run, top).minByOrNull { it.getDistance(preferred) } ?: snapWalkable(preferred)
        val boss = spawn(id, spot, respawns = false) // one Archmage per game — his fall completes it
        run.boss = boss
        run.members += boss
        logger.info { "wizard-tower: boss '$BOSS_KEY' spawned at $spot" }
    }

    /** A floor's spawnable tiles: flood-filled from the stair landing, clamped to the floor plate.
     *  If the seed is somehow sealed in, retry once from the nearest open tile. */
    private fun floorTiles(run: Run, floor: Floor): List<Tile> {
        val lo = run.instance.translate(Tile(floor.bounds.bottomLeftX, floor.bounds.bottomLeftY, floor.height))
        val hi = run.instance.translate(Tile(floor.bounds.topRightX, floor.bounds.topRightY, floor.height))
        val bounds = Area(lo.x, lo.z, hi.x, hi.z)
        val allow = { t: Tile -> bounds.contains(t.x, t.z) }
        val seed = run.instance.translate(floor.seed)
        return reachableFrom(seed, allow = allow).ifEmpty { reachableFrom(snapWalkable(seed), allow = allow) }
    }

    private fun spawn(id: Int, tile: Tile, respawns: Boolean): Npc {
        val npc = Npc(id, tile, world)
        // The tower pays through its OWN rune-faucet table — keep the generic OSRS drop plugin out.
        npc.attr[NO_LOOT_ATTR] = true
        world.spawn(npc)
        // AFTER world.spawn: setNpcDefaults() runs inside spawn and CLOBBERS respawns (it derives
        // it from respawnDelay > 0) — a value set before the spawn call is silently overwritten.
        npc.respawns = respawns
        npc.setActive(true)
        // Tower mages hunt HUMANS on sight — no OSRS tolerance/level check, and no wasting their
        // rage on companions/bots (those just help kill mages faster).
        npc.aggroCheck = { _, target -> target.entityType.isHumanControlled }
        return npc
    }

    /** Re-arm a respawned tower mage: the engine's respawn path clears its attributes (the
     *  NO_LOOT flag) and the aggro plugin re-binds the default tolerance check — both must be
     *  put back or respawned mages leak generic OSRS drops and go passive. Called from the
     *  plugin's global npc-spawn hook (which fires on every respawn). */
    fun onNpcSpawned(npc: Npc) {
        val run = runs.firstOrNull { npc in it.members } ?: return
        run.awaitingRespawn.remove(npc)
        npc.attr[NO_LOOT_ATTR] = true
        npc.aggroCheck = { _, target -> target.entityType.isHumanControlled }
    }

    /**
     * Death hook, called by the plugin's global `onAnyNpcDeath`: if [npc] belongs to a live run,
     * roll its loot at the corpse — for the KILLER in multi games — and advance the run. Loot
     * rides the ENGINE's death event, which fires only after the death animation finishes; see
     * [Run.members] for why nothing else may prune dead npcs.
     */
    fun onNpcDeath(npc: Npc) {
        val run = runs.firstOrNull { npc in it.members } ?: return
        val killer = npc.attr[KILLER_ATTR]?.get() as? Player
        val owner = if (killer != null && killer in run.players) killer else run.players.firstOrNull() ?: return
        val isBoss = npc === run.boss
        dropLoot(owner, npc.tile, if (isBoss) BOSS_DROPS else MAGE_DROPS)
        if (isBoss) {
            run.members.remove(npc) // the Archmage doesn't respawn
            run.bossDead = true
        } else {
            // Mages respawn (farming loop): keep membership, but flag the corpse — the engine
            // refills its hp immediately (isDead() reads false while it waits invisibly on the
            // respawn timer), so the floor gate needs this to know the floor is really clear.
            run.awaitingRespawn += npc
        }
    }

    /** Driven once per tick by the plugin: player upkeep and completion. No timer — players
     *  farm as long as they like and leave through the top-floor portal. */
    fun tickAll() {
        runs.toList().forEach { runCatching { tick(it) }.onFailure { e -> logger.error(e) { "wizard-tower tick failed" } } }
    }

    private fun tick(run: Run) {
        // Drop players who left the instance (portal/death/logout); the game lives while anyone remains.
        run.players.removeAll { it.index < 0 || !run.instance.contains(it.tile) }
        if (run.players.isEmpty()) { endRun(run); return }
        if (run.bossDead && !run.done) complete(run)
    }

    private fun complete(run: Run) {
        run.done = true
        run.players.forEach { p ->
            p.message("<col=ffae00>The Archmage falls!</col> Stay and farm the mages as long as you like — the portal beside the rug leads out.")
            // First completion ALWAYS unlocks the special spellbooks (idempotent) — the War-Prep
            // chain additionally advances if the player is on its TOWER step.
            if (WarPrepChain.step(p) == WarPrepChain.Step.TOWER) {
                p.message("<col=8f00ff>You lift the grimoire from the Archmage's remains.</col>")
                WarPrepChain.onGrimoireTaken(p) // → unlockMageBooks + War-Prep DONE
                p.message("Use <col=8f00ff>::spellbook</col> to switch between your spellbooks.")
            } else if (p.unlockMageBooks()) {
                p.message("<col=8f00ff>The grimoire's secrets are yours — the Ancient, Lunar and Arceuus spellbooks are unlocked!</col>")
                p.message("Use <col=8f00ff>::spellbook</col> to switch between your spellbooks.")
            }
        }
    }

    private fun endRun(run: Run) {
        run.members.forEach { if (it.index >= 0 && world.npcs.contains(it)) world.remove(it) }
        run.members.clear()
        run.awaitingRespawn.clear()
        run.portal?.let { runCatching { world.remove(it) } }
        run.players.forEach { if (it.index >= 0 && run.instance.contains(it.tile)) it.moveTo(EXIT_TILE) }
        runs.remove(run)
    }

    /**
     * The tower staircases, called by [LadderPlugin]. Returns **false for anyone not in a run**
     * (real-tower visitors get the default climb). Inside a run the minigame owns the movement —
     * floors are side-by-side islands, so "climbing" is a teleport to the next island's stair
     * landing — and the floor gate applies: you climb up only once every mage on your current
     * floor is dead.
     */
    fun handleClimb(p: Player, up: Boolean): Boolean {
        val run = runs.firstOrNull { p in it.players } ?: return false
        val floor = run.instance.floorOf(p.tile)
        if (up) {
            if (floor >= FLOOR_COUNT - 1) {
                p.message("You're at the top of the tower.")
                return true
            }
            // A mage on this floor still standing blocks the climb. Excluded: dying ones
            // (isDead, their death event may be a few animation ticks away) and killed ones
            // waiting invisibly on the respawn timer (their hp is already refilled).
            val standing = run.members.count {
                run.instance.floorOf(it.tile) == floor && it.index >= 0 && !it.isDead() && it !in run.awaitingRespawn
            }
            if (standing > 0) {
                p.message("<col=ff0000>Clear this floor's mages before you climb higher! ($standing left)</col>")
                return true
            }
            p.moveTo(run.instance.translate(UP_ARRIVAL[floor + 1]))
        } else {
            if (floor <= 0) {
                p.message("The basement is sealed off.")
                return true
            }
            p.moveTo(run.instance.translate(DOWN_ARRIVAL[floor - 1]))
        }
        return true
    }

    // ---- loot (the higher-tier runes the shops don't sell) ----

    private val MAGE_DROPS = DropTable(
        always = listOf(DropEntry("item.coins_995", 200, 900)),
        main = listOf(
            DropEntry("item.chaos_rune", 30, 80, weight = 40),
            DropEntry("item.nature_rune", 15, 40, weight = 25),
            DropEntry("item.cosmic_rune", 15, 35, weight = 18),
            DropEntry("item.law_rune", 10, 30, weight = 18),
            DropEntry("item.death_rune", 12, 35, weight = 18),
            DropEntry("item.blood_rune", 10, 25, weight = 12),
            DropEntry("item.soul_rune", 8, 20, weight = 8),
        ),
        rare = listOf(DropEntry("item.wrath_rune", 5, 15, oneInN = 60)),
    )

    private val BOSS_DROPS = DropTable(
        always = listOf(
            DropEntry("item.coins_995", 5_000, 15_000),
            DropEntry("item.death_rune", 50, 100),
            DropEntry("item.blood_rune", 40, 90),
        ),
        main = listOf(
            DropEntry("item.law_rune", 80, 150, weight = 25),
            DropEntry("item.nature_rune", 100, 200, weight = 25),
            DropEntry("item.soul_rune", 60, 120, weight = 30),
            DropEntry("item.wrath_rune", 20, 50, weight = 20),
        ),
        rare = listOf(
            DropEntry("item.mystic_lava_staff", 1, 1, oneInN = 40, announce = true, log = true),
            DropEntry("item.master_wand", 1, 1, oneInN = 80, announce = true, log = true),
        ),
    )

    private fun dropLoot(p: Player, tile: Tile, table: DropTable) {
        table.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            world.spawn(GroundItem(id, drop.amount, tile, p))
        }
    }

    // ---- walkable-tile helpers (spawn on real floor, never in a wall) ----

    /** Nearest unclipped tile to [t] on the same level (spiral out); [t] itself if already open. */
    private fun snapWalkable(t: Tile): Tile {
        if (!world.collision.isClipped(t)) return t
        for (r in 1..6) for (dx in -r..r) for (dz in -r..r) {
            if (max(abs(dx), abs(dz)) != r) continue
            val c = Tile(t.x + dx, t.z + dz, t.height)
            if (!world.collision.isClipped(c)) return c
        }
        return t
    }

    /**
     * Tiles reachable ON FOOT from [seed]: a breadth-first fill that only expands through steps
     * the pathfinder itself would allow ([World.stepValidator] — wall/fence edges included),
     * constrained by [allow]. Unlike a bare `!isClipped` scan this can never leak into upper-floor
     * VOID (no floor also reads "unclipped") or into fenced pockets like the lesser-demon cage —
     * the enclosing walls block the steps. Returned in BFS order (= distance from the seed). The
     * seed itself may be clipped (stair footprint); expansion is tested per-step.
     */
    private fun reachableFrom(seed: Tile, limit: Int = 200, allow: (Tile) -> Boolean): List<Tile> {
        val dirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
        val visited = linkedSetOf(seed)
        val queue = ArrayDeque(listOf(seed))
        while (queue.isNotEmpty() && visited.size < limit) {
            val cur = queue.removeFirst()
            for (dir in dirs) {
                val next = cur.step(dir)
                if (next in visited || !allow(next)) continue
                val ok = world.stepValidator.canTravel(
                    level = cur.height,
                    x = cur.x,
                    z = cur.z,
                    offsetX = dir.getDeltaX(),
                    offsetZ = dir.getDeltaZ(),
                    size = 1,
                )
                if (!ok) continue
                visited += next
                queue += next
            }
        }
        return visited.drop(1) // exclude the seed (the stairs / player's landing spot)
    }
}

class WizardTowerPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** The Void Knight game-master at the mainland end of the tower bridge. Cache def edited
     *  (`gradlew :game-server:npcDef -PnpcArgs="wizardknight"`) so his right-click menu carries
     *  "Solo game" and "Multi game" alongside Talk-to. */
    private val knight = "npc.void_knight"
    private val knightTile = Tile(3113, 3208, 0)
    private val teleTile = Tile(3113, 3211, 0) // ::wizardtower / portal landing, facing the knight

    private val towerRegion = 12337
    private val bridgeRegion = 12338 // the knight's region (mainland end of the bridge)
    private val timer = TimerKey()

    /** Per-tier magic max hit — every tower mage CASTS, so Protect from Magic truly counters the
     *  tower (the whole point of the War-Prep Prayer step). TUNABLE. */
    private val mageMaxHit = mapOf(
        "npc.dark_wizard" to 4,
        "npc.battle_mage" to 8,
        "npc.ancient_wizard" to 12,
        "npc.infernal_mage_443" to 20, // the Archmage
    )

    /** Aggro radius per tier: ground-floor wizards use a shorter leash so they don't swarm the
     *  bridge spawn while a multi game musters — they engage once you come off the bridge and
     *  through the door. Upper floors see their whole island. */
    private val mageAggroRadius = mapOf(
        "npc.dark_wizard" to 8,
        "npc.battle_mage" to 16,
        "npc.ancient_wizard" to 16,
        "npc.infernal_mage_443" to 16,
    )

    /** Generic magic-bolt projectile gfx (same id the Chaos Fanatic uses — known-good). */
    private val mageSpellGfx = 159

    init {
        registerMageDefs()

        // Custom combat: default NPC combat is MELEE (and the default magic strategy NPEs for
        // npcs), so override the strategy (the wilderness-boss onNpcCombat pattern) with a
        // magic-projectile loop. bossProjectile handles Protect-from-Magic + accuracy + travel.
        mageMaxHit.forEach { (key, maxHit) ->
            runCatching { onNpcCombat(key) { npc.queue { npc.mageCombat(this, maxHit) } } }
                .onFailure { logger.warn { "wizard-tower: combat bind for '$key' skipped: ${it.message}" } }
        }

        // Loot on the engine's own death event (additive hook — cheap early-out for non-tower npcs).
        onAnyNpcDeath { WizardTower.onNpcDeath(npc) }

        // Fires on every npc (re)spawn — tower mages respawn for the farming loop, and the
        // engine's respawn path wipes their attrs (NO_LOOT) + the aggro plugin re-binds the
        // default tolerance check; this puts both back.
        onGlobalNpcSpawn { WizardTower.onNpcSpawned(npc) }

        // The top-floor exit portal (spawned per instance). Guarded: other portal_4150s may
        // exist in the world — usePortal only acts for players inside a tower game.
        runCatching {
            onObjOption(WizardTower.PORTAL_KEY, option = "use") {
                if (!WizardTower.usePortal(player)) {
                    player.message("The portal's magic doesn't respond to you.")
                }
            }
        }.onFailure { logger.warn { "wizard-tower: portal bind skipped: ${it.message}" } }

        onWorldInit {
            WizardTower.world = world
            // Force-load both regions so the knight spawns and the tower's collision is built
            // even with no player nearby (the instance copy reads it at allocation).
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(towerRegion, bridgeRegion)) }
                .onFailure { logger.error(it) { "wizard-tower: region force-load failed" } }
            // Spawn DIRECTLY — KotlinPlugin.spawnNpc only queues into PluginRepository.npcSpawns,
            // and that queue is consumed (spawnEntities) during plugin load, BEFORE onWorldInit
            // handlers run: a spawnNpc call from here is a silent no-op.
            runCatching {
                val n = Npc(getRSCM(knight), knightTile, world)
                n.walkRadius = 0
                n.lastFacingDirection = Direction.NORTH
                world.spawn(n)
                n.setActive(true)
                logger.info { "wizard-tower: Void Knight spawned at $knightTile (index=${n.index})" }
            }.onFailure { logger.warn { "wizard-tower: knight '$knight' not spawned: ${it.message}" } }
            world.timers[timer] = 1
        }
        onTimer(timer) {
            WizardTower.tickAll()
            world.timers[timer] = 1
        }

        bindKnight()
        onCommand("wizardtower", description = "Teleport to the Wizard Tower Void Knight") {
            player.moveTo(teleTile)
            player.message("Speak to the <col=8f00ff>Void Knight</col> to enter the Wizard Tower — solo, or in a band of up to ${WizardTower.MAX_PLAYERS}.")
        }
    }

    /** The mage attack loop (mirrors the wilderness-boss combat loop): approach to spell range,
     *  face, and lob a magic bolt on each attack-speed window until combat disengages. Tower
     *  mages fight HUMANS — if one somehow swings onto a companion/bot (retaliation), it swings
     *  back to the nearest human in range. */
    private suspend fun Npc.mageCombat(it: QueueTask, maxHit: Int) {
        var target = getCombatTarget() ?: return
        if (!target.entityType.isHumanControlled) {
            nearestHuman()?.let { h -> attack(h); return }
        }
        while (canEngageCombat(target)) {
            facePawn(target)
            // combatRaycast = SYMMETRIC "within 7 tiles + line of sight". (Combat.moveToAttackRange
            // is broken two ways for this: it never actually moves — engine pursuit is commented
            // out — and its areOverlapping box is anchored NE of the target, so mages approaching
            // from the south/west had to walk right on top of you before it said "in range".)
            val inPosition = combatRaycast(target, distance = 7, projectile = true)
            if (inPosition) {
                if (isAttackDelayReady()) {
                    animate(combatDef.attackAnimation)
                    bossProjectile(target, CombatClass.MAGIC, maxHit, gfx = mageSpellGfx)
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.nearestHuman(): Player? =
        world.players.entries
            .filterNotNull()
            .filter { it.entityType.isHumanControlled && it.tile.height == tile.height && it.tile.isWithinRadius(tile, 16) }
            .minByOrNull { max(abs(it.tile.x - tile.x), abs(it.tile.z - tile.z)) }

    /** Bind the Void Knight's menu. Talk-to gets the briefing dialogue; the custom "Solo game" /
     *  "Multi game" options (cache def edit) go straight into a game. Guarded so a stale cache
     *  (edit not applied yet) degrades to Talk-to-only instead of dropping the plugin. */
    private fun bindKnight() {
        val acts = runCatching { getNpc(getRSCM(knight)).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        acts.forEach { act ->
            when (act.lowercase()) {
                "talk-to" -> onNpcOption(knight, option = act) { player.queue { knightDialog(player) } }
                "solo game" -> onNpcOption(knight, option = act) { WizardTower.enter(player, multi = false) }
                "multi game" -> onNpcOption(knight, option = act) { WizardTower.enter(player, multi = true) }
            }
        }
        if (acts.none { it.equals("solo game", true) }) {
            logger.warn { "wizard-tower: knight cache def lacks 'Solo game' — run: gradlew :game-server:npcDef -PnpcArgs=\"wizardknight\" (actions=$acts)" }
        }
    }

    private suspend fun QueueTask.knightDialog(p: Player) {
        val id = runCatching { getRSCM(knight) }.getOrDefault(-1)
        chatNpc(p, "The Wizards' Tower has fallen to rogue mages — and Archmage Sedridor's grimoire with it. We knights hold this bridge, and we pay in runes for every head.", npc = id, title = "Void Knight")
        chatNpc(p, "Fight up the tower a floor at a time and fell the Archmage at the top. Go in alone, or open your assault to others — up to ${WizardTower.MAX_PLAYERS} may storm it together.", npc = id, title = "Void Knight")
        chatNpc(p, "Their spells bite — pray against magic before you cross. The mages rise again as fast as you cut them down, so farm their runes as long as you dare. The portal at the top brings you home.", npc = id, title = "Void Knight")
        when (options(p, "Enter alone. (solo)", "Open assault. (up to ${WizardTower.MAX_PLAYERS} players)", "Not now.")) {
            1 -> WizardTower.enter(p, multi = false)
            2 -> WizardTower.enter(p, multi = true)
            3 -> chatPlayer(p, "Maybe later.")
        }
    }

    /** Escalating mage combat defs (magic casters). Protect from Magic hard-counters them — which is
     *  exactly what the War-Prep Prayer step readies the recruit for. Guarded so a bad key can't drop
     *  the plugin; death anim set (required or the def fails to build). */
    private fun registerMageDefs() {
        mageDef("npc.dark_wizard", hp = 20, mag = 30, def = 20, atkMag = 20)
        mageDef("npc.battle_mage", hp = 40, mag = 55, def = 40, atkMag = 40)
        mageDef("npc.ancient_wizard", hp = 60, mag = 80, def = 55, atkMag = 60)
        mageDef("npc.infernal_mage_443", hp = 200, mag = 120, def = 100, atkMag = 90, magDmg = 10) // the Archmage
        // Boot-time attackability report: an npc whose cache def lacks "Attack" can never be
        // fought by players (that's how Sedridor slipped through). Surfaces the problem in the
        // log instead of in a playtest.
        mageMaxHit.keys.forEach { key ->
            runCatching {
                val acts = getNpc(getRSCM(key)).actions.filterNotNull().filter { it.isNotBlank() }
                if (acts.none { it.equals("attack", true) }) {
                    logger.warn { "wizard-tower: '$key' has NO Attack option (actions=$acts) — players cannot fight it!" }
                } else {
                    logger.info { "wizard-tower: '$key' actions=$acts" }
                }
            }
        }
    }

    private fun mageDef(key: String, hp: Int, mag: Int, def: Int, atkMag: Int, magDmg: Int = 0) {
        runCatching {
            setCombatDef(key) {
                configs {
                    attackSpeed = 4 // fast — the tower is meant to be hard, and they pile on
                    respawnDelay = 50
                }
                aggro {
                    radius = mageAggroRadius[key] ?: 8
                    searchDelay = 1
                    aggroTimer = Int.MAX_VALUE // never tolerant — always hostile (spawn() also overrides aggroCheck)
                }
                stats {
                    hitpoints = hp
                    magic = mag
                    defence = def
                }
                bonuses {
                    attackMagic = atkMag
                    magicDamageBonus = magDmg
                    defenceMagic = def
                    defenceStab = def
                    defenceSlash = def
                    defenceCrush = def
                    defenceRanged = def
                }
                anims {
                    attack = 711 // spell cast (729 is the staff MELEE bash — casts looked like melee hits)
                    block = 424
                    death = 836 // human death — REQUIRED or the def won't build
                }
            }
        }.onFailure { logger.warn { "wizard-tower: combat def for '$key' skipped: ${it.message}" } }
    }
}
