package org.alter.plugins.content.npcs.godwars

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **God Wars Dungeon faction warfare** — makes the dungeon's npcs fight each other by god, the
 * way they do in OSRS. The four armies ([GodWarsFaction]) each field a party of roaming
 * **followers** on the shared main-cavern battlefield; every tick they close on the nearest
 * enemy-faction follower and fight, so the centre of the dungeon is a permanent four-way brawl.
 *
 * The design mirrors the (much larger) war-system driver: this plugin owns the follower
 * instances in per-faction pools, tops them up from a fixed cap each tick (the dead stay dead
 * until replenished), and drives target selection directly — it never touches natural world
 * npcs, and it leaves the generals and their bodyguards to their own room combat + player aggro
 * ([GodWarsPlugin] / [GodWarsCombatPlugin]).
 *
 * A player who walks into the melee is fair game too: with no enemy follower in reach, a follower
 * turns on the nearest player in the cavern — authentic GWD, where every army is hostile to
 * anything not wearing their god's protection (enemy followers stay the priority). Attack styles
 * are simplified to melee for now (the same simplification the bodyguards use); per-follower
 * ranged/magic styles are a TUNE.
 */
class GodWarsFactionCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Live follower instances this plugin owns, one pool per faction. */
    private val parties: Map<GodWarsFaction.Faction, MutableList<Npc>> =
        GodWarsFaction.Faction.values().associateWith { mutableListOf() }

    private val tickTimer = TimerKey()

    /** Shared follower stats — tanky enough to trade blows for a while. Applied per-instance after
     *  spawn (never registered globally: several follower ids, e.g. orks/pyrefiends, also appear as
     *  ordinary monsters elsewhere and must keep their own defs). Levels go into `npc.stats`, which
     *  is what the combat formula actually reads. Hostility is driven by [driveCombat], not the
     *  engine's aggro timer, so no aggressive-radius is needed here. */
    private val followerDef = NpcCombatDef.DEFAULT.copy(
        attack = 120, strength = 120, defence = 110, hitpoints = 90,
        attackSpeed = 5, respawnDelay = 0,
    )

    init {
        onWorldInit {
            if (!ENABLED) return@onWorldInit
            world.timers[tickTimer] = START_DELAY
            logger.info { "[GWD] Faction warfare armed — battlefield at ${GodWarsFaction.CENTRE}" }
        }

        onTimer(tickTimer) {
            if (!ENABLED) return@onTimer
            try {
                maintainParties(world)
                driveCombat(world)
            } catch (e: Exception) {
                logger.error(e) { "[GWD] faction combat tick failed" }
            }
            world.timers[tickTimer] = TICK_INTERVAL
        }

        // ::gwdwar — teleport to the faction battlefield to watch the armies clash.
        onCommand("gwdwar", Privilege.ADMIN_POWER, description = "Teleport to the GWD faction battlefield") {
            player.moveTo(GodWarsFaction.CENTRE)
            val counts = parties.entries.joinToString(", ") { "${it.key.display}=${it.value.count { n -> isAlive(world, n) }}" }
            player.message("<col=801700>GWD battlefield:</col> $counts")
        }
    }

    // --- population: keep each army topped up from its fixed cap ---

    private fun maintainParties(world: World) {
        for ((faction, pool) in parties) {
            pool.removeAll { it.index < 0 || it.isDead() }
            val roster = GodWarsFaction.followers[faction].orEmpty()
            if (roster.isEmpty()) continue
            val muster = GodWarsFaction.MUSTER[faction] ?: GodWarsFaction.CENTRE
            val deficit = (PARTY_SIZE - pool.size).coerceAtMost(SPAWN_PER_TICK)
            repeat(deficit.coerceAtLeast(0)) {
                val key = roster[pool.size % roster.size]
                val id = runCatching { getRSCM(key) }.getOrNull() ?: return@repeat
                val tile = world.findRandomTileAround(muster, radius = MUSTER_RADIUS) ?: muster
                val npc = Npc(id, tile, world)
                npc.walkRadius = 0 // the driver moves them; the engine's wander would fight it
                npc.routeLogic = 1 // smart pathfinder, so they route around the cavern terrain
                world.spawn(npc)
                applyFollowerStats(npc)
                npc.respawns = false
                npc.setActive(true)
                pool += npc
            }
        }
    }

    /** Push the follower def + its levels + full HP onto a freshly-spawned instance. `world.spawn`
     *  seeds the def but not `npc.stats`, which the combat formula actually reads. */
    private fun applyFollowerStats(npc: Npc) {
        npc.combatDef = followerDef
        npc.stats.setMaxLevel(NpcSkills.ATTACK, followerDef.attack)
        npc.stats.setCurrentLevel(NpcSkills.ATTACK, followerDef.attack)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, followerDef.strength)
        npc.stats.setCurrentLevel(NpcSkills.STRENGTH, followerDef.strength)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, followerDef.defence)
        npc.stats.setCurrentLevel(NpcSkills.DEFENCE, followerDef.defence)
        npc.setCurrentHp(followerDef.hitpoints)
    }

    // --- combat: every follower hunts the nearest enemy-faction follower ---

    private fun driveCombat(world: World) {
        // Snapshot the whole battlefield once so target lookups are cheap and consistent.
        val alive: Map<GodWarsFaction.Faction, List<Npc>> =
            parties.mapValues { (_, pool) -> pool.filter { isAlive(world, it) } }
        val everyone = alive.values.flatten()
        if (everyone.isEmpty()) return
        val players = playersOnBattlefield(world)

        for ((faction, side) in alive) {
            val enemies = everyone.filter { GodWarsFaction.factionOf(it.id) != faction }
            for (npc in side) {
                try {
                    if (!npc.lock.canAttack() || !npc.isActive()) continue

                    // Still validly locked onto a live, in-range foe? Let the engine keep fighting it.
                    val current = npc.getCombatTarget()
                    val engaged = npc.isAttacking() && current != null &&
                        isAlivePawn(world, current) && dist(npc.tile, current.tile) <= CHASE_RANGE
                    if (engaged) continue
                    if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }

                    // Enemy followers first (this is a faction war); a nearby player is the fallback.
                    val enemy = enemies.filter { dist(npc.tile, it.tile) <= AGGRO_RANGE }
                        .minByOrNull { dist(npc.tile, it.tile) }
                        ?: players.filter { dist(npc.tile, it.tile) <= AGGRO_RANGE }
                            .minByOrNull { dist(npc.tile, it.tile) }
                    if (enemy != null) {
                        npc.attack(enemy)
                    } else if (dist(npc.tile, GodWarsFaction.CENTRE) > RALLY_RADIUS) {
                        // No enemy within reach — march to the centre so the four armies converge.
                        npc.walkTo(walkableNear(world, GodWarsFaction.CENTRE))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[GWD] follower step failed for npc id=${npc.id}" }
                }
            }
        }
    }

    /** Players currently standing on the dungeon battlefield (plane + bounds) — follower prey when
     *  no enemy follower is in reach. Snapshotted once per sweep. */
    private fun playersOnBattlefield(world: World): List<Player> {
        val out = ArrayList<Player>()
        for (i in 0 until world.players.capacity) {
            val p = world.players[i] ?: continue
            if (!p.isOnline || p.isDead()) continue
            if (p.tile.height != GodWarsFaction.PLANE || !GodWarsFaction.DUNGEON.contains(p.tile)) continue
            out += p
        }
        return out
    }

    // --- helpers ---

    private fun isAlive(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

    private fun isAlivePawn(world: World, pawn: Pawn): Boolean = when (pawn) {
        is Npc -> isAlive(world, pawn)
        is Player -> pawn.index >= 0 && pawn.isOnline && !pawn.isDead()
        else -> false
    }

    /** Nearest stand-able tile to [t] (the tile itself if already clear), searched in expanding
     *  rings — so a rally point nudged into rock/water is corrected to dry ground. */
    private fun walkableNear(world: World, t: Tile): Tile {
        if (!world.collision.isClipped(t)) return t
        for (radius in 1..SNAP_RADIUS) {
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (max(abs(dx), abs(dz)) != radius) continue // only the current ring's edge
                val c = Tile(t.x + dx, t.z + dz, t.height)
                if (!world.collision.isClipped(c)) return c
            }
        }
        return t
    }

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private companion object {
        const val ENABLED = true
        const val START_DELAY = 5 // ticks after boot before the first sweep (let regions settle)
        const val TICK_INTERVAL = 2 // ticks between sweeps (~1.2s) — cheap; pools are small
        const val PARTY_SIZE = 6 // living followers held per faction
        const val SPAWN_PER_TICK = 2 // replenish rate per faction per sweep
        const val MUSTER_RADIUS = 3 // scatter of a faction's spawns around its muster point
        const val AGGRO_RANGE = 10 // Chebyshev tiles a follower will pick up an enemy from
        const val CHASE_RANGE = 14 // keep chasing an engaged foe out to here before dropping it
        const val RALLY_RADIUS = 6 // idle followers march until this close to the battlefield centre
        const val SNAP_RADIUS = 6 // rings searched for dry ground when a rally tile is blocked
    }
}
