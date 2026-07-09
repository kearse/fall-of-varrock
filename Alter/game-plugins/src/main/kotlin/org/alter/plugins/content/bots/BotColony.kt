package org.alter.plugins.content.bots

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.StaticTerrain

private val logger = KotlinLogging.logger {}

/**
 * Maintains one [BotZoneConfig]'s population of roaming [PkBot]s.
 *
 * Bot analogue of [org.alter.plugins.content.war.HostileZone] (top-up-to-count + respawn delay),
 * but it spawns Players via [BotManager] instead of NPCs, and is **lazily activated**: it only
 * holds a population while a real player is near the zone, despawning everything after a grace
 * period when empty. That keeps the per-tick cost proportional to active players, so the zone count
 * (and per-zone [BotZoneConfig.target]) can grow later without a rewrite.
 *
 * Walkable muster points are computed once from the cache terrain ([StaticTerrain]) — the same
 * decode as the `mapDump` — so bots never muster in water/cliffs; `findRandomTileAround` jitters
 * each spawn off walls/objects at runtime.
 */
class BotColony(private val cfg: BotZoneConfig) {

    private val pool = mutableListOf<PkBot>()
    private var ticks = 0
    private var respawnReadyAt = 0
    private var emptyTicks = 0

    /** Activation box: the zone plus its padding. */
    private val activation: Area = Area(
        cfg.area.bottomLeftX - cfg.activationPadding,
        cfg.area.bottomLeftY - cfg.activationPadding,
        cfg.area.topRightX + cfg.activationPadding,
        cfg.area.topRightY + cfg.activationPadding,
    )

    /**
     * Muster points, computed once at construction (cache terrain is available by then). A tile
     * qualifies only if it is BOTH walkable AND a live PvP wilderness tile ([PvpZones.isWilderness]) —
     * so a bot can never muster on a safe tile or a safe carve-out (bank/GE/town core) even if the
     * zone box overhangs one. This is the structural guarantee that PKers stay in the PKing areas.
     */
    private val muster = BotZones.boxStaging(cfg.area, cfg.spacing)
        .filter { StaticTerrain.isWalkable(it.x, it.z) && PvpZones.isWilderness(it) }

    init {
        logger.info { "Bot zone '${cfg.displayName}': ${muster.size} walkable muster points (target ${cfg.target})." }
    }

    fun tick(world: World) {
        try {
            ticks++
            prune()
            if (playerNear(world)) {
                emptyTicks = 0
                maintain(world)
            } else if (pool.isNotEmpty() && ++emptyTicks >= EMPTY_GRACE_TICKS) {
                despawnAll(world)
            }
        } catch (e: Exception) {
            logger.error(e) { "BotColony '${cfg.displayName}' tick failed (skipped)" }
        }
    }

    private fun prune() {
        val before = pool.size
        pool.removeAll { it.index < 0 || it.isDead() }
        if (pool.size < before) respawnReadyAt = ticks + cfg.respawnDelayTicks // something died/despawned
    }

    private fun playerNear(world: World): Boolean {
        var near = false
        world.players.forEach { p ->
            if (!near && p !is PkBot && p.isOnline && !p.invisible && activation.contains(p.tile)) near = true
        }
        return near
    }

    private fun maintain(world: World) {
        if (muster.isEmpty() || ticks < respawnReadyAt) return
        while (pool.size < cfg.target) {
            val anchor = activator(world)
            // Anti-swarm: don't pile more PKers onto a player who already has enough near them (grid
            // cells overlap, so several colonies can otherwise dogpile the same player at a boundary).
            if (anchor != null && botsNear(anchor) >= MAX_BOTS_NEAR_PLAYER) return
            val point = spawnPoint(anchor, pool.size)
            val tile = world.findRandomTileAround(point, radius = 4) ?: point
            // Tier by our custom wilderness level at the spawn tile: shallow-wild spawns roll a weak
            // metal PKer, deep-wild spawns roll elite NHers. A zone may pin a fixed tier instead.
            val tier = cfg.tier ?: BotZones.tierForWildLevel(PvpZones.wildernessLevel(tile))
            val loadout = BotLoadouts.get(tier.roll())
            if (loadout == null) {
                logger.error { "Bot zone '${cfg.displayName}': unknown loadout key from tier; skipping." }
                return
            }
            val bot = BotManager.spawn(world, loadout, tile) ?: return // player limit reached
            bot.homeTile = tile
            bot.roamRadius = cfg.roamRadius
            bot.leashRadius = cfg.leashRadius
            bot.zoneKey = cfg.key
            pool += bot
        }
    }

    /**
     * Where to place the next bot. A cell is big (up to [BotZones.CELL] tiles), so instead of a fixed
     * corner we spawn as close to the player who triggered the cell as the [SPAWN_MIN]..[SPAWN_MAX]
     * band allows — a PKer appears near you and comes hunting, not a cell away. Falls back to the
     * nearest muster point past [SPAWN_MIN], then to a round-robin muster point if nobody's pinpointable.
     */
    private fun spawnPoint(anchor: Player?, idx: Int): Tile {
        if (anchor != null) {
            val band = muster.filter { it.getDistance(anchor.tile) in SPAWN_MIN..SPAWN_MAX }
            if (band.isNotEmpty()) return band.random()
            muster.filter { it.getDistance(anchor.tile) >= SPAWN_MIN }
                .minByOrNull { it.getDistance(anchor.tile) }
                ?.let { return it }
        }
        return muster[idx % muster.size]
    }

    /** A real player inside the activation box — the one the cell is spawning a PKer for. */
    private fun activator(world: World): Player? {
        var found: Player? = null
        world.players.forEach { p ->
            if (found == null && p !is PkBot && p.isOnline && !p.invisible && activation.contains(p.tile)) found = p
        }
        return found
    }

    /** Live PKer bots (any zone) currently within [DENSITY_RADIUS] tiles of [anchor]. */
    private fun botsNear(anchor: Player): Int =
        BotManager.active.count { it.index >= 0 && !it.isDead() && it.tile.isWithinRadius(anchor.tile, DENSITY_RADIUS) }

    private fun despawnAll(world: World) {
        pool.toList().forEach { BotManager.despawn(world, it) }
        pool.clear()
        emptyTicks = 0
    }

    private companion object {
        /** Zone-ticks with no nearby player before the colony despawns (keeps slots/CPU free). */
        const val EMPTY_GRACE_TICKS = 12 // ~36s at a 5-game-tick zone cadence

        /** A spawned PKer appears this many tiles from the triggering player: far enough not to pop
         *  on top of them, close enough to notice and engage (aggro range is 15). */
        const val SPAWN_MIN = 12
        const val SPAWN_MAX = 26

        /** Anti-swarm: colonies stop spawning near a player who already has this many PKers within
         *  [DENSITY_RADIUS] — so you meet a PKer or two, not a mob (grid cells otherwise dogpile). */
        const val MAX_BOTS_NEAR_PLAYER = 2
        const val DENSITY_RADIUS = 22
    }
}
