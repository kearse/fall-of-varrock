package org.alter.plugins.content.npcs.worldspawns

import com.fasterxml.jackson.databind.ObjectMapper
import dev.openrune.cache.CacheManager.getNpc
import dev.openrune.cache.CacheManager.getNpcs
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.PvpZones
import org.alter.rscm.RSCM.getRSCM
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * The real-OSRS world population — EVERY npc spawn from the live game (shopkeepers,
 * bankers, townsfolk AND monsters/bosses at their true lairs), loaded from the
 * OSRS-wiki crowdsourced spawn dump (`mejrs/data_osrs`) converted into
 * `data/cfg/spawns/npc_spawns.json`, with real combat stats from the osrsreboxed
 * monster dataset in `npc_combat.json` ({byId: {id: [hp,att,str,def,mag,rng,speed,
 * aggro,slayerLvl,slayerXp]}, byName: {name: representativeId}} — byName resolves
 * the many variant ids per monster, mirroring npc_drops.json).
 *
 * Design rules:
 *  - **Presence gating**: spawns are DATA until a real player nears their region
 *    (the frontier-freeze lesson); dormant records cost nothing at any world size.
 *  - **Original world first, our content on top**: an id any plugin registered a
 *    death handler for (bespoke bosses, frontier lines) is NEVER world-spawned,
 *    and hand-placed npcs suppress colliding records (same id within 6 tiles) —
 *    so players can trust the OSRS wiki for locations, and our customizations win
 *    wherever they exist.
 *  - Killed monsters respawn via this plugin's slot refill (engine respawn is OFF
 *    so a closed gate can't resurrect npcs into an empty region).
 */
object WorldSpawns {
    /** One dormant spawn record. */
    class Rec(val id: Int, val x: Int, val z: Int, val plane: Int, val walkRadius: Int)

    /** regionId ((x shr 6) shl 8 or (z shr 6)) -> that region's spawn records. */
    val byRegion = HashMap<Int, MutableList<Rec>>()

    /** id -> [hp, att, str, def, mag, rng, atkSpeed, aggro01, slayerLvl, slayerXp]. */
    private val statsById = HashMap<Int, IntArray>()
    private val statIdByName = HashMap<String, Int>()

    var totalLoaded = 0
        private set
    var skippedUnknownId = 0
        private set
    var skippedNameDrift = 0
        private set

    /** Bankers etc. stand at their booths; other humans wander lightly, monsters more. */
    private val STATIONARY = listOf("banker", "grand exchange clerk", "poll booth")

    fun load(
        spawnsPath: String = "../data/cfg/spawns/npc_spawns.json",
        combatPath: String = "../data/cfg/spawns/npc_combat.json",
    ) {
        if (byRegion.isNotEmpty()) return
        val spawnsFile = File(spawnsPath)
        if (!spawnsFile.exists()) {
            logger.warn { "World npc spawn data not found at ${spawnsFile.absolutePath} — world spawns disabled." }
            return
        }
        val mapper = ObjectMapper()
        val cache = getNpcs()

        val combatFile = File(combatPath)
        if (combatFile.exists()) {
            val croot = mapper.readTree(combatFile)
            croot.get("byId")?.fields()?.forEach { (k, v) ->
                val id = k.toIntOrNull() ?: return@forEach
                statsById[id] = IntArray(v.size()) { v.get(it).asInt() }
            }
            croot.get("byName")?.fields()?.forEach { (k, v) -> statIdByName[k] = v.asInt() }
        } else {
            logger.warn { "npc_combat.json missing — world monsters would be 10hp pushovers; attackable spawns limited to trash tier." }
        }

        val root = mapper.readTree(spawnsFile)

        // Wiki name per id, used to catch id drift: the dump tracks LIVE OSRS, our cache is
        // rev 228 — an id reassigned since then would spawn the wrong npc, so any id whose
        // cache name doesn't match the wiki name is skipped (counted, not fatal).
        val wikiNames = HashMap<Int, String>()
        root.get("names")?.fields()?.forEach { (k, v) ->
            k.toIntOrNull()?.let { wikiNames[it] = v.asText() }
        }

        root.get("spawns")?.forEach { s ->
            if (s.size() < 4) return@forEach
            val id = s.get(0).asInt()
            val def = cache[id]
            if (def == null) {
                skippedUnknownId++
                return@forEach
            }
            val wikiName = wikiNames[id]
            if (wikiName != null && !def.name.equals(wikiName, ignoreCase = true)) {
                skippedNameDrift++
                return@forEach
            }
            val x = s.get(1).asInt()
            val z = s.get(2).asInt()
            val nameLc = def.name.lowercase()
            val attackable = def.actions.any { it == "Attack" }
            val walk = when {
                STATIONARY.any { nameLc.contains(it) } -> 0
                attackable -> 4
                else -> 2
            }
            val regionId = ((x shr 6) shl 8) or (z shr 6)
            byRegion.getOrPut(regionId) { ArrayList() }.add(Rec(id, x, z, s.get(3).asInt(), walk))
            totalLoaded++
        }
        logger.info {
            "World spawns: $totalLoaded spawns in ${byRegion.size} regions " +
                "(skipped $skippedUnknownId unknown-id, $skippedNameDrift name-drift vs 228 cache)."
        }
    }

    /** Real stats for [id], by exact id then by cache-name alias (variant ids). */
    fun statsFor(id: Int): IntArray? =
        statsById[id] ?: statIdByName[getNpc(id).name.lowercase().trim()]?.let { statsById[it] }
}

class WorldSpawnsPlugin(
    private val repo: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(repo, world, server) {

    /** A live spawn slot: its record + npc, or a countdown to respawn after a kill. */
    private class Slot(val rec: WorldSpawns.Rec) {
        var npc: Npc? = null
        var respawnIn = 0
    }

    /** A materialized region: its slots + how many sweeps it's gone unvisited. */
    private class LiveRegion(val slots: List<Slot>) {
        var idleSweeps = 0
    }

    private val live = HashMap<Int, LiveRegion>()
    private var prunedBespoke = 0
    private var prunedUnstatted = 0

    init {
        WorldSpawns.load()

        // Finalize AFTER every plugin has registered its content (bespoke bosses,
        // frontier lines, combat defs) — plugin init order is arbitrary, world init
        // runs once all of them are done.
        val timer = TimerKey()
        onWorldInit {
            finalizeSpawnData()
            world.timers[timer] = SWEEP_TICKS
        }
        onTimer(timer) {
            sweep()
            world.timers[timer] = SWEEP_TICKS
        }

        onCommand("worldspawns", Privilege.DEV_POWER, description = "Ambient world-spawn gate status") {
            val liveNpcs = live.values.sumOf { region -> region.slots.count { it.npc != null } }
            player.message("World spawns: ${WorldSpawns.totalLoaded} records ($prunedBespoke bespoke-pruned, $prunedUnstatted unstatted-pruned), ${live.size} live regions, $liveNpcs live npcs.")
            if (player.getCommandArgs().firstOrNull() == "here") {
                val id = ((player.tile.x shr 6) shl 8) or (player.tile.z shr 6)
                val recs = WorldSpawns.byRegion[id]?.size ?: 0
                player.message("This region ($id): $recs records, ${if (live.containsKey(id)) "LIVE" else "dormant"}.")
            }
        }
    }

    /**
     * Post-boot pass over the loaded records:
     *  - drop ids owned by bespoke content (any registered npc death handler — bosses,
     *    frontier lines): their plugins spawn/schedule them, a generic twin would fight
     *    without mechanics or double-drop;
     *  - drop attackable ids that have NO real stats and aren't trash tier — a boss-level
     *    monster on the 10hp DEFAULT def would be a free-loot piñata;
     *  - register a real combat def for every remaining attackable id that doesn't
     *    already have one (bespoke defs win).
     */
    private fun finalizeSpawnData() {
        stripGrandExchange()
        applyFallenVarrock()
        val registered = HashSet<Int>()
        val it = WorldSpawns.byRegion.iterator()
        while (it.hasNext()) {
            val list = it.next().value
            list.removeAll { rec ->
                if (repo.hasNpcDeathHandler(rec.id)) {
                    prunedBespoke++
                    return@removeAll true
                }
                val def = getNpc(rec.id)
                if (!def.actions.any { a -> a == "Attack" }) return@removeAll false
                val stats = WorldSpawns.statsFor(rec.id)
                if (stats == null || stats.size < 10) {
                    if (def.combatLevel > MAX_UNSTATTED_LEVEL) {
                        prunedUnstatted++
                        return@removeAll true
                    }
                    return@removeAll false // trash tier: DEFAULT 10hp def is close enough
                }
                if (registered.add(rec.id) && !repo.npcCombatDefs.containsKey(rec.id)) {
                    val deathAnim = if (def.standAnim > 0) def.standAnim else FALLBACK_DEATH
                    repo.npcCombatDefs[rec.id] = NpcCombatDef.DEFAULT.copy(
                        hitpoints = stats[0],
                        attack = stats[1],
                        strength = stats[2],
                        defence = stats[3],
                        magic = stats[4],
                        ranged = stats[5],
                        attackSpeed = stats[6],
                        deathAnimation = listOf(deathAnim),
                        aggressiveRadius = if (stats[7] == 1) AGGRO_RADIUS else 0,
                        aggroTargetDelay = 8,
                        aggressiveTimer = 200,
                        slayerReq = stats[8],
                        slayerXp = stats[9].toDouble(),
                    )
                }
                false
            }
            if (list.isEmpty()) it.remove()
        }
        logger.info {
            "World spawns finalized: ${registered.size} combat defs registered, " +
                "$prunedBespoke bespoke-owned + $prunedUnstatted unstatted high-level records pruned."
        }
    }

    /**
     * The Grand Exchange was torn down (see data/mapedit/ge_ruins.json — the octagon is rubble +
     * fire now, only its perimeter walls left standing). So its population goes too: EVERY spawn
     * record inside [GE_RUINS] is removed with no replacement — clerks, bankers, the lot. The two
     * Castle-Wars faction recruiters ([GE_RECRUITERS]) loiter on the GE's *west* approach, just
     * outside the footprint box, so they're stripped by id within [GE_APPROACH] as well (there's
     * no faction recruiting to be done at a demolished exchange). Runs before [applyFallenVarrock]
     * so the fallen-city pass never repopulates the ruins (and its "banks/GE survive" carve-out
     * no longer applies here — there's nothing left to staff).
     */
    private fun stripGrandExchange() {
        val recruiterIds = GE_RECRUITERS.mapNotNull { name ->
            runCatching { getRSCM(name) }.getOrElse {
                logger.warn { "[GE RUINS] unknown recruiter name '$name' — not stripped." }
                null
            }
        }.toHashSet()
        var removed = 0
        var recruiters = 0
        for (list in WorldSpawns.byRegion.values) {
            val before = list.size
            list.removeAll { rec ->
                when {
                    GE_RUINS.contains(rec.x, rec.z) -> true
                    rec.id in recruiterIds && GE_APPROACH.contains(rec.x, rec.z) -> {
                        recruiters++
                        true
                    }
                    else -> false
                }
            }
            removed += before - list.size
        }
        logger.info { "[GE RUINS] stripped $removed npc spawn record(s) inside the destroyed Grand Exchange (incl. $recruiters GE recruiter(s))." }
    }

    /**
     * FALLEN VARROCK (story): the city fell — no townsfolk, no lawful garrison. It's a
     * monster/rogue-run loot hub now. Applied to the loaded spawn data before the normal
     * finalize pass:
     *  - every NON-COMBAT record (no Attack option) inside the city box is removed;
     *  - the old garrison (Guard/Trainee Guard/etc.) is removed too, even though attackable;
     *  - each removed GROUND-floor spot respawns as a random monster from a themed pool
     *    (rogues, muggers, undead, dark wizards — the scum that moved in), so the streets
     *    stay populated and every kill feeds the normal drop tables;
     *  - upstairs (plane > 0) records are simply dropped (no monsters stuck in bedrooms);
     *  - the PvP safe carve-outs ([PvpZones]: the two banks + Grand Exchange) are LEFT ALONE —
     *    bankers/clerks survive behind their doors, so the loot hub still has a bank.
     * Monsters that already spawned in Varrock (rats, thieves, black knights…) are untouched.
     */
    private fun applyFallenVarrock() {
        // Pool ids are boot-verified: must resolve, be player-attackable, have real stats and
        // not be claimed by bespoke content (a war line claiming an id drops it from the pool).
        val pool = FALLEN_POOL.mapNotNull { (name, weight) ->
            val id = runCatching { getRSCM(name) }.getOrNull()
            if (id == null) {
                logger.warn { "[FALLEN VARROCK] unknown npc name '$name' — dropped from pool." }
                return@mapNotNull null
            }
            val def = getNpc(id)
            val usable = def.actions.any { it == "Attack" } && def.combatLevel > 0 &&
                !repo.hasNpcDeathHandler(id) && WorldSpawns.statsFor(id) != null
            if (!usable) {
                logger.warn { "[FALLEN VARROCK] '$name' unusable (not attackable / unstatted / bespoke-owned) — dropped from pool." }
                null
            } else {
                id to weight
            }
        }
        val totalWeight = pool.sumOf { it.second }
        var replaced = 0
        var dropped = 0
        for (list in WorldSpawns.byRegion.values) {
            val iter = list.listIterator()
            while (iter.hasNext()) {
                val rec = iter.next()
                if (!FALLEN_VARROCK.contains(rec.x, rec.z)) continue
                if (!PvpZones.isWilderness(Tile(rec.x, rec.z, 0))) continue // banks/GE survive
                val def = getNpc(rec.id)
                val combat = def.actions.any { it == "Attack" } && def.combatLevel > 0
                if (combat && def.name.lowercase() !in FALLEN_GARRISON) continue // real monsters stay
                if (rec.plane > 0 || totalWeight <= 0) {
                    iter.remove()
                    dropped++
                    continue
                }
                var r = world.random(totalWeight - 1)
                var pick = pool.first().first
                for ((id, w) in pool) {
                    if (r < w) {
                        pick = id
                        break
                    }
                    r -= w
                }
                iter.set(WorldSpawns.Rec(pick, rec.x, rec.z, 0, FALLEN_WALK))
                replaced++
            }
        }
        logger.info {
            "[FALLEN VARROCK] city stripped: $replaced civilian/guard spots now spawn monsters " +
                "(${pool.size}-kind pool), $dropped records dropped (upstairs/no pool)."
        }
    }

    /**
     * The presence gate. A region is wanted while any REAL player (PkBots roam roads
     * around the clock and must not hold towns live on an empty server) is inside it
     * or an adjacent region (~64 tiles of lead — npcs are up before the player can
     * see them). Unwanted live regions linger [IDLE_SWEEPS_TO_DESPAWN] sweeps so a
     * player bouncing around a town edge doesn't churn spawns. Live regions also
     * refill slots whose monster was killed ([RESPAWN_SWEEPS]).
     */
    private fun sweep() {
        val wanted = HashSet<Int>()
        world.players.forEach { p ->
            if (p.index < 0 || p is PkBot) return@forEach
            val rx = p.tile.x shr 6
            val rz = p.tile.z shr 6
            for (dx in -1..1) for (dz in -1..1) wanted.add(((rx + dx) shl 8) or (rz + dz))
        }

        for (regionId in wanted) {
            if (regionId !in live && WorldSpawns.byRegion.containsKey(regionId)) activate(regionId)
            live[regionId]?.idleSweeps = 0
        }

        val it = live.iterator()
        while (it.hasNext()) {
            val (regionId, region) = it.next()
            if (regionId in wanted) {
                refill(region)
                continue
            }
            if (++region.idleSweeps >= IDLE_SWEEPS_TO_DESPAWN) {
                for (slot in region.slots) {
                    val n = slot.npc ?: continue
                    if (n.index >= 0 && world.npcs.contains(n)) world.remove(n)
                    slot.npc = null
                }
                it.remove()
            }
        }
    }

    /** Detect killed monsters (engine removed them; respawns is off) and refill after a delay. */
    private fun refill(region: LiveRegion) {
        for (slot in region.slots) {
            val n = slot.npc
            if (n != null) {
                if (n.index < 0 || !world.npcs.contains(n)) { // died since last sweep
                    slot.npc = null
                    slot.respawnIn = RESPAWN_SWEEPS
                }
            } else if (slot.respawnIn > 0 && --slot.respawnIn == 0) {
                slot.npc = spawn(slot.rec)
            }
        }
    }

    private fun activate(regionId: Int) {
        val recs = WorldSpawns.byRegion[regionId] ?: return

        // Far regions have no collision until a player streams them in (the war-troop
        // freeze lesson); idempotent force-load so npcs can wander from the first tick.
        runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(regionId)) }
            .onFailure { logger.error(it) { "[WORLDSPAWNS] collision load failed for region $regionId" } }

        // Hand-placed npcs (shop hub, quest givers, war content) win: a record is skipped
        // when the same npc id already stands within DEDUPE_RADIUS of its tile.
        val existing = ArrayList<Npc>()
        world.npcs.forEach { n ->
            if (n != null && ((n.tile.x shr 6) shl 8 or (n.tile.z shr 6)) == regionId) existing += n
        }

        val slots = ArrayList<Slot>(recs.size)
        for (rec in recs) {
            val dup = existing.any {
                it.id == rec.id && it.tile.height == rec.plane &&
                    Math.abs(it.tile.x - rec.x) <= DEDUPE_RADIUS && Math.abs(it.tile.z - rec.z) <= DEDUPE_RADIUS
            }
            if (dup) continue
            val slot = Slot(rec)
            slot.npc = spawn(rec)
            slots += slot
        }
        live[regionId] = LiveRegion(slots)
    }

    private fun spawn(rec: WorldSpawns.Rec): Npc {
        val npc = Npc(rec.id, Tile(rec.x, rec.z, rec.plane), world)
        npc.walkRadius = rec.walkRadius
        world.spawn(npc)
        // AFTER world.spawn — setNpcDefaults derives respawns from the combat def's
        // respawnDelay and would clobber it. Engine respawn stays OFF: this plugin owns
        // the full lifecycle (refill on death, remove on gate close).
        npc.respawns = false
        npc.setActive(true)
        return npc
    }

    private companion object {
        const val SWEEP_TICKS = 5 // 3s between gate checks — a walking player crosses <11 tiles
        const val IDLE_SWEEPS_TO_DESPAWN = 20 // ~1 min empty before a region's npcs stand down
        const val DEDUPE_RADIUS = 6
        const val RESPAWN_SWEEPS = 10 // ~30s from kill to respawn
        const val MAX_UNSTATTED_LEVEL = 20 // stat-less monsters above this are pruned, not 10hp piñatas
        const val AGGRO_RADIUS = 4
        const val FALLBACK_DEATH = 836 // generic death animation

        // ---- Fallen Varrock (see applyFallenVarrock) ----
        /** The walled city + gates. TUNABLE — everything inside is "the fallen city". */
        val FALLEN_VARROCK = Area(3155, 3376, 3300, 3520)

        /** The demolished Grand Exchange footprint — every npc spawn inside is stripped (see stripGrandExchange). */
        val GE_RUINS = Area(3149, 3468, 3190, 3517)

        /** GE + its western approach path, where the Castle-Wars recruiters stand (just outside [GE_RUINS]). */
        val GE_APPROACH = Area(3138, 3468, 3190, 3517)

        /** Castle-Wars faction recruiters at the GE — stripped by id within [GE_APPROACH] (they sit west of the box). */
        val GE_RECRUITERS = listOf("npc.saradominist_recruiter", "npc.zamorakian_recruiter")

        /** The old garrison — removed by NAME even though attackable (the city has no lawful guard). */
        val FALLEN_GARRISON = setOf("guard", "trainee guard", "museum guard", "guard captain")

        /** Weighted monster pool the emptied streets respawn as. Rogue-heavy on purpose. */
        val FALLEN_POOL = listOf(
            "npc.mugger" to 4,
            "npc.thug" to 4,
            "npc.highwayman" to 3,
            "npc.rogue_526" to 3,
            "npc.zombie" to 2,
            "npc.skeleton" to 2,
            "npc.dark_wizard" to 2,
            "npc.chaos_druid" to 2,
            "npc.black_knight" to 1,
            "npc.bandit_690" to 1,
        )

        /** Streets-roaming radius for the replacement monsters (civilians wandered 2). */
        const val FALLEN_WALK = 6
    }
}
