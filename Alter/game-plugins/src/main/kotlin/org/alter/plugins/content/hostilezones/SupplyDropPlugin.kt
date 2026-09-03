package org.alter.plugins.content.hostilezones

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_GROUNDITEM_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.StaticTerrain
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **warned supply drop** — the hostile zones' battlefield-maker ([HostileZones]). Roughly
 * every hour a server-wide warning names a zone and district ("a supply drop falls on the Wild
 * Bandit Stronghold — the Tents — in 5 minutes"); at zero the crate's contents land on a random
 * tile there as ONE public high-value item, and whoever walks out with it is broadcast to the
 * realm. Five minutes is enough for the whole playerbase to converge — which is the point.
 *
 * Two-phase timer machine, same shape as [org.alter.plugins.content.war.MarchPlugin]:
 * IDLE —(cadence + jitter)→ warn broadcast —(WARN_TICKS)→ the drop lands → back to IDLE.
 * `::rarespawn` (admin) / `::hostile drop` advance the current leg immediately for testing.
 *
 * The client's `lofsupplydrop` world-map marker rides the [MAP_PREFIX] machine lines — the
 * prefix and grammar are frozen (no client deploy needed to add zones).
 */
class SupplyDropPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private enum class State { IDLE, WARNED }
    private var state = State.IDLE
    /** The warned target, picked at the warning broadcast. */
    private var targetZone: HostileZoneConfig? = null
    private var targetDistrict: LootDistrict? = null
    /** The live, unclaimed drop (cleared on claim or when it times out and respawns anew). */
    private var liveDrop: GroundItem? = null
    /** The machine map-marker lines ([MAP_PREFIX]) matching the current phase, replayed to
     *  late log-ins so their world map catches up (see the client's `lofsupplydrop` plugin). */
    private var warnMapLine: String? = null
    private var dropMapLine: String? = null

    /** Zones that take part in the rotation. */
    private val zones: List<HostileZoneConfig> get() = HostileZones.all.filter { it.supplyDrop }

    init {
        val timer = TimerKey()
        onWorldInit {
            // Dormant while no zone takes supply drops — nothing to warn about, no timer.
            if (zones.isEmpty()) {
                logger.info { "[SUPPLY DROP] no hostile zones with supply drops — disabled." }
            } else {
                world.timers[timer] = cadence()
                logger.info { "[SUPPLY DROP] cadence armed across ${zones.size} zone(s)." }
            }
        }
        onTimer(timer) {
            when (state) {
                State.IDLE -> {
                    warn()
                    world.timers[timer] = WARN_TICKS
                }
                State.WARNED -> {
                    land()
                    world.timers[timer] = cadence()
                }
            }
        }

        // The claim broadcast: fires for the player who picks the live drop up.
        onGlobalItemPickup {
            val drop = liveDrop ?: return@onGlobalItemPickup
            val picked = player.attr[INTERACTING_GROUNDITEM_ATTR]?.get() ?: return@onGlobalItemPickup
            if (picked !== drop) return@onGlobalItemPickup
            liveDrop = null
            dropMapLine = null
            val name = runCatching { getItem(drop.item).name }.getOrNull() ?: "the supply drop"
            Announce.broadcast(world, "<col=ffcc00>${player.username} has claimed $name from the supply drop — and now has to make it out alive!</col>")
            Announce.broadcast(world, "${MAP_PREFIX}CLEAR")
        }

        // Late log-ins get the current phase's map marker replayed (the broadcast that carried
        // it went out before they were online).
        onLogin {
            val line = dropMapLine?.takeIf { liveDrop?.let { d -> world.isSpawned(d) } == true } ?: warnMapLine
            line?.let { player.message(it, ChatMessageType.BROADCAST) }
        }

        HostileRuntime.supplyAdvance = {
            if (zones.isEmpty()) {
                "No hostile zones take supply drops — dormant."
            } else {
                world.timers[timer] = 1
                "Supply-drop cycle advanced (${if (state == State.IDLE) "warning" else "landing"} next tick)."
            }
        }

        onCommand("rarespawn", Privilege.ADMIN_POWER, description = "Force the supply-drop cycle forward (test)") {
            player.message("<col=4f9b4f>[test] ${HostileRuntime.supplyAdvance?.invoke()}</col>")
        }
    }

    /** Ticks until the next warning: the base hour with a ±10-minute jitter. */
    private fun cadence(): Int = BASE_TICKS + world.random(-JITTER_TICKS..JITTER_TICKS)

    private fun warn() {
        val pool = zones
        if (pool.isEmpty()) return // dormant: nothing to land a drop on
        val zone = pool[world.random(pool.size - 1)]
        val district = zone.districts[world.random(zone.districts.size - 1)]
        targetZone = zone
        targetDistrict = district
        state = State.WARNED
        val mins = WARN_TICKS * 6 / 600
        Announce.broadcast(
            world,
            "<col=ffae00>A supply drop falls on ${zone.display} — ${district.display} — in ~$mins minutes! " +
                "It is PvP ground: whoever takes it must carry it out.</col>",
        )
        // Map marker (client `lofsupplydrop`): the WARN phase marks the district's centre —
        // the convergence point, not the exact tile (that isn't picked until it lands).
        val c = district.center
        warnMapLine = "${MAP_PREFIX}WARN:${zone.display}:${district.display}:${c.x}:${c.z}:${WARN_TICKS * 6 / 10}"
        warnMapLine?.let { Announce.broadcast(world, it) }
    }

    private fun land() {
        state = State.IDLE
        val zone = targetZone ?: return
        val district = targetDistrict ?: return
        targetZone = null
        targetDistrict = null

        val tile = pickDropTile(district) ?: run {
            logger.warn { "[SUPPLY DROP] no landing tile found in ${zone.key}/${district.key} — drop skipped." }
            return
        }
        val rolled = zone.rareTable.roll(world).firstOrNull() ?: return
        val id = runCatching { getRSCM(rolled.item) }.getOrNull() ?: run {
            logger.warn { "[SUPPLY DROP] unresolvable item '${rolled.item}' — drop skipped." }
            return
        }

        // A previous drop nobody claimed is superseded (avoid two live crates confusing the claim hook).
        liveDrop?.let { old -> if (world.isSpawned(old)) world.remove(old) }

        val item = GroundItem(id, rolled.amount, tile)
        item.despawnDelayOverride = DROP_LIFETIME_TICKS // sits ~an hour, then the wild keeps it
        world.spawn(item)
        liveDrop = item
        Announce.broadcast(
            world,
            "<col=ffcc00>The supply drop is DOWN in ${district.display} of ${zone.display}! First to reach it takes it.</col>",
        )
        // Map marker: the landing swaps the district-centre WARN marker for the exact tile.
        warnMapLine = null
        dropMapLine = "${MAP_PREFIX}DROP:${zone.display}:${district.display}:${tile.x}:${tile.z}"
        dropMapLine?.let { Announce.broadcast(world, it) }
        logger.info { "[SUPPLY DROP] ${rolled.item} x${rolled.amount} landed at (${tile.x},${tile.z}) in ${zone.key}/${district.key}." }
    }

    /**
     * A random walkable tile inside the district: random picks snapped by [StaticTerrain],
     * rejected if a safe pocket swallowed them — a crate inside a bank would be a risk-free claim.
     */
    private fun pickDropTile(district: LootDistrict): Tile? {
        val area = district.area
        repeat(PICK_ATTEMPTS) {
            val x = area.bottomLeftX + world.random(area.topRightX - area.bottomLeftX)
            val z = area.bottomLeftY + world.random(area.topRightY - area.bottomLeftY)
            val snapped = StaticTerrain.nearestWalkable(x, z, maxRadius = 4) ?: return@repeat
            val tile = Tile(snapped.first, snapped.second, 0)
            if (!area.contains(tile)) return@repeat
            if (PvpZones.isSafe(tile)) return@repeat
            return tile
        }
        // Fall back to the district's centre snap (never inside a bank by authoring).
        val c = district.center
        val snapped = StaticTerrain.nearestWalkable(c.x, c.z, maxRadius = 12) ?: return null
        return Tile(snapped.first, snapped.second, 0)
    }

    private companion object {
        /** Base warning-to-warning cadence (~60 min at 0.6s ticks). TUNE. */
        const val BASE_TICKS = 6000
        /** ± jitter on the cadence (~10 min) so the hour never becomes clockwork. */
        const val JITTER_TICKS = 1000
        /** Warning lead time (~5 min) — enough for the server to converge on the district. */
        const val WARN_TICKS = 500
        /** How long an unclaimed drop lies there (~1 h). */
        const val DROP_LIFETIME_TICKS = 6000
        /** Random-tile attempts before falling back to the district centre. */
        const val PICK_ATTEMPTS = 24

        /** Machine-line prefix for the client's `lofsupplydrop` world-map markers. Rides the
         *  BROADCAST channel like `FOV_INTRO:` (hidden from chat by the client's ticker filter).
         *  FROZEN — the client hard-codes it. Grammar: `WARN:<zone>:<district>:<x>:<z>:<seconds>`
         *  (district centre), `DROP:<zone>:<district>:<x>:<z>` (exact tile), `CLEAR` (claimed). */
        const val MAP_PREFIX = "FOV_RAID:"
    }
}
