package org.alter.plugins.content.hostilezones

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.StaticTerrain
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The hostile-zone **loot-spot engine** — the "scav loot" half of the extraction loop
 * ([HostileZones]). Every authored [LootSpot] keeps one public [GroundItem] on the ground, rolled
 * from its district's themed [DropTable]; when a raider grabs it, the spot rerolls a FRESH pick
 * [LootSpot.respawnTicks] later — so spots stay worth revisiting and no two runs loot the same
 * zone.
 *
 * Engine notes:
 *  - The engine's own ground-item respawn ([GroundItem.respawnCycles]) clones the SAME item
 *    forever, so this plugin owns the lifecycle instead: `respawnCycles` stays -1 and
 *    [GroundItem.despawnDelayOverride] is maxed so an unlooted spawn never times out.
 *  - Presence-gated per zone (the world-spawn sweep lesson): an empty zone's spots don't tick, so
 *    loot can't be conjured or hoarded on a dead server. PK bots never count as presence.
 *  - Spots are snapped to walkable at boot ([StaticTerrain]) and audited against the safe
 *    carve-outs — loot inside a bank pocket would be a risk-free farm ([auditSafeSpots] runs a
 *    tick after world init, once the bank scan has registered its carve-outs).
 */
class HostileLootPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class SpotState(val zone: HostileZoneConfig, val table: DropTable, val tile: Tile) {
        var live: GroundItem? = null
        /** World cycle the next roll lands at; 0 = spawn on the next sweep. */
        var respawnAt = 0
        var respawnTicks = GroundItem.DEFAULT_RESPAWN_CYCLES
    }

    private val spots = ArrayList<SpotState>()
    private var skippedSpots = 0

    init {
        val timer = TimerKey()
        onWorldInit {
            buildSpots()
            world.timers[timer] = SWEEP_TICKS
        }
        onTimer(timer) {
            sweep()
            world.timers[timer] = SWEEP_TICKS
        }

        for (zone in HostileZones.all) {
            HostileRuntime.lootStatus[zone.key] = {
                val mine = spots.filter { it.zone === zone }
                "${mine.count { it.live != null }}/${mine.size} loot spots live (${if (zoneActive(zone)) "ACTIVE" else "dormant"})"
            }
            HostileRuntime.lootReset[zone.key] = { spots.filter { it.zone === zone }.forEach { it.respawnAt = 0 } }
        }
    }

    /** Snap every authored spot to walkable ground and queue its first roll. */
    private fun buildSpots() {
        for (zone in HostileZones.all) {
            for (district in zone.districts) {
                for (spot in district.spots) {
                    val snapped = if (StaticTerrain.isWalkable(spot.x, spot.z)) {
                        spot.x to spot.z
                    } else {
                        StaticTerrain.nearestWalkable(spot.x, spot.z, maxRadius = SNAP_RADIUS)
                    }
                    if (snapped == null) {
                        skippedSpots++
                        logger.warn { "[HOSTILE LOOT] ${zone.key}/${district.key} spot (${spot.x},${spot.z}) has no walkable ground within $SNAP_RADIUS tiles — skipped." }
                        continue
                    }
                    val state = SpotState(zone, district.table, Tile(snapped.first, snapped.second, 0))
                    state.respawnTicks = spot.respawnTicks
                    spots += state
                }
            }
        }
        logger.info { "[HOSTILE LOOT] ${spots.size} loot spot(s) armed across ${HostileZones.all.size} hostile zone(s), $skippedSpots skipped." }
        auditSafeSpots()
    }

    /** Flag spots the safe carve-outs swallowed (bank radius crept over a street spot). */
    private fun auditSafeSpots() {
        world.queue {
            wait(1)
            val safe = spots.filter { PvpZones.isSafe(it.tile) }
            if (safe.isNotEmpty()) {
                logger.warn {
                    "[HOSTILE LOOT] ${safe.size} loot spot(s) sit on SAFE ground (risk-free farm — move them): " +
                        safe.take(10).joinToString { "${it.zone.key}(${it.tile.x},${it.tile.z})" }
                }
            }
        }
    }

    /** A zone ticks only while a real player is inside its padded box. */
    private fun zoneActive(zone: HostileZoneConfig): Boolean {
        val pad = ACTIVATION_PADDING
        var active = false
        world.players.forEach { p ->
            if (active || p.index < 0 || p is PkBot) return@forEach
            val t = p.tile
            if (t.x >= zone.area.bottomLeftX - pad && t.x <= zone.area.topRightX + pad &&
                t.z >= zone.area.bottomLeftY - pad && t.z <= zone.area.topRightY + pad
            ) {
                active = true
            }
        }
        return active
    }

    private fun sweep() {
        val activeZones = HostileZones.all.filter { zoneActive(it) }
        if (activeZones.isEmpty()) return
        for (state in spots) {
            if (activeZones.none { it === state.zone }) continue

            val live = state.live
            if (live != null) {
                if (!world.isSpawned(live)) { // taken (or admin-cleared) since last sweep
                    state.live = null
                    state.respawnAt = world.currentCycle + state.respawnTicks
                }
                continue
            }
            if (world.currentCycle >= state.respawnAt) spawnLoot(state)
        }
    }

    private fun spawnLoot(state: SpotState) {
        val rolled = state.table.roll(world).firstOrNull() ?: return
        val id = runCatching { getRSCM(rolled.item) }.getOrNull() ?: run {
            logger.warn { "[HOSTILE LOOT] unresolvable item '${rolled.item}' rolled at (${state.tile.x},${state.tile.z})." }
            return
        }
        val item = GroundItem(id, rolled.amount, state.tile)
        item.despawnDelayOverride = Int.MAX_VALUE // sits until somebody takes the risk
        world.spawn(item)
        state.live = item
    }

    private companion object {
        /** ~6s between sweeps — pickup detection + refills; loot cadence is per-spot. */
        const val SWEEP_TICKS = 10
        /** How far an authored spot may snap to find walkable ground. */
        const val SNAP_RADIUS = 6
        /** A real player this close to a zone's box keeps its loot ticking (matches the occupiers). */
        const val ACTIVATION_PADDING = 32
    }
}
