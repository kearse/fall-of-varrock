package org.alter.plugins.content.raidzones

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.priv.Privilege
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
 * The raid-city **loot-spot engine** — the "scav loot" half of the Tarkov loop
 * ([RaidCities]). Every authored [LootSpot] keeps one public [GroundItem] on the streets,
 * rolled from its district's themed [DropTable]; when a raider grabs it, the spot rerolls a
 * FRESH pick [LootSpot.respawnTicks] later — so spots stay worth revisiting and no two runs
 * loot the same city.
 *
 * Engine notes:
 *  - The engine's own ground-item respawn ([GroundItem.respawnCycles]) clones the SAME item
 *    forever, so this plugin owns the lifecycle instead: `respawnCycles` stays -1 and
 *    [GroundItem.despawnDelayOverride] is maxed so an unlooted spawn never times out.
 *  - Presence-gated per city (the [org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin]
 *    sweep lesson): an empty city's spots don't tick, so loot can't be conjured or hoarded
 *    on a dead server.
 *  - Spots are snapped to walkable at boot ([StaticTerrain]) and audited against the safe
 *    carve-outs — loot inside a bank pocket would be a risk-free farm ([auditSafeSpots] runs a
 *    tick after world init, once [org.alter.plugins.content.combat.BankSafezonePlugin]'s bank
 *    scan has registered its carve-outs).
 *
 * Extraction pockets: any [RaidCityConfig.extractions] boxes are registered as safe carve-outs
 * here at construction (banks are picked up automatically by BankSafezonePlugin).
 */
class RaidLootPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class SpotState(val city: RaidCityConfig, val table: DropTable, val tile: Tile) {
        var live: GroundItem? = null
        /** World cycle the next roll lands at; 0 = spawn on the next sweep. */
        var respawnAt = 0
        var respawnTicks = GroundItem.DEFAULT_RESPAWN_CYCLES
    }

    private val spots = ArrayList<SpotState>()
    private var skippedSpots = 0

    init {
        // Explicit extraction pockets (constructor-time, like BankSafezonePlugin, so anything
        // that later reads PvpZones — bot muster filters, death drops — already sees them).
        RaidCities.all.forEach { city -> city.extractions.forEach { PvpZones.addSafeArea(it) } }

        val timer = TimerKey()
        onWorldInit {
            buildSpots()
            world.timers[timer] = SWEEP_TICKS
        }
        onTimer(timer) {
            sweep()
            world.timers[timer] = SWEEP_TICKS
        }

        onCommand("raidloot", Privilege.DEV_POWER, description = "Extraction-zone loot spot states (add 'reset' to force-refill)") {
            val reset = player.getCommandArgs().firstOrNull() == "reset"
            if (RaidCities.all.isEmpty()) player.message("[raidloot] no extraction zones configured — the loot-spot engine is dormant.")
            for (city in RaidCities.all) {
                val mine = spots.filter { it.city === city }
                val live = mine.count { it.live != null }
                if (reset) {
                    mine.forEach { it.respawnAt = 0 }
                    player.message("[raidloot] ${city.display}: ${mine.size} spots force-queued for refill.")
                } else {
                    player.message("[raidloot] ${city.display}: $live/${mine.size} spots live (${if (cityActive(city)) "ACTIVE" else "dormant"}).")
                }
            }
            if (skippedSpots > 0) player.message("[raidloot] $skippedSpots authored spot(s) were skipped at boot (unwalkable/safe) — see server log.")
        }
    }

    /** Snap every authored spot to walkable ground and queue its first roll. */
    private fun buildSpots() {
        for (city in RaidCities.all) {
            for (district in city.districts) {
                for (spot in district.spots) {
                    val snapped = if (StaticTerrain.isWalkable(spot.x, spot.z)) {
                        spot.x to spot.z
                    } else {
                        StaticTerrain.nearestWalkable(spot.x, spot.z, maxRadius = SNAP_RADIUS)
                    }
                    if (snapped == null) {
                        skippedSpots++
                        logger.warn { "[RAID LOOT] ${city.key}/${district.key} spot (${spot.x},${spot.z}) has no walkable ground within $SNAP_RADIUS tiles — skipped." }
                        continue
                    }
                    val state = SpotState(city, district.table, Tile(snapped.first, snapped.second, 0))
                    state.respawnTicks = spot.respawnTicks
                    spots += state
                }
            }
        }
        logger.info { "[RAID LOOT] ${spots.size} loot spot(s) armed across ${RaidCities.all.size} extraction zone(s), $skippedSpots skipped." }
        auditSafeSpots()
    }

    /** Flag spots the safe carve-outs swallowed (bank radius crept over a street spot). */
    private fun auditSafeSpots() {
        // Run one tick later: BankSafezonePlugin registers its carve-outs at construction, but
        // being defensive about plugin construction order costs nothing.
        world.queue {
            wait(1)
            val safe = spots.filter { PvpZones.isSafe(it.tile) }
            if (safe.isNotEmpty()) {
                logger.warn {
                    "[RAID LOOT] ${safe.size} loot spot(s) sit on SAFE ground (risk-free farm — move them): " +
                        safe.take(10).joinToString { "${it.city.key}(${it.tile.x},${it.tile.z})" }
                }
            }
        }
    }

    /** A city ticks only while a real player is inside its padded box. */
    private fun cityActive(city: RaidCityConfig): Boolean {
        val pad = ACTIVATION_PADDING
        var active = false
        world.players.forEach { p ->
            if (active || p.index < 0 || p is PkBot) return@forEach // bots don't hold a city's loot live
            val t = p.tile
            if (t.x >= city.area.bottomLeftX - pad && t.x <= city.area.topRightX + pad &&
                t.z >= city.area.bottomLeftY - pad && t.z <= city.area.topRightY + pad
            ) {
                active = true
            }
        }
        return active
    }

    private fun sweep() {
        val activeCities = RaidCities.all.filter { cityActive(it) }
        if (activeCities.isEmpty()) return
        for (state in spots) {
            if (activeCities.none { it === state.city }) continue

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
            logger.warn { "[RAID LOOT] unresolvable item '${rolled.item}' rolled at (${state.tile.x},${state.tile.z})." }
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
        /** A real player this close to a city's box keeps its loot ticking. */
        const val ACTIVATION_PADDING = 32
    }
}
