package org.alter.plugins.content.hostilezones

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.CityFrontiers
import org.alter.plugins.content.war.Faction
import org.alter.plugins.content.war.HostileZone
import org.alter.plugins.content.war.MonsterPack
import org.alter.plugins.content.war.StaticTerrain
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The hostile zones' **occupiers** — each zone's [OccupierLine]s become ONE `war.HostileZone`
 * garrison (the frontier engine: slot-based in-place respawn, aggro, 1v1 sweep, presence gate),
 * mustered while a real player is within [ACTIVATION_PADDING] of the box and despawned when
 * nobody is — the same gate as the loot spots, so loot and guards wake together.
 *
 * Modelled on the frontier builder ([org.alter.plugins.content.war.CityFrontierPlugin]) but
 * independent of it: this plugin owns its own zones and timers and never touches
 * `Frontiers` / the campaign engine (an occupied zone is not a March target). Rules carried over:
 *  - ONE global combat def + ONE death handler per npc id server-wide — a line whose id is already
 *    claimed (a frontier line, a world-spawn pool, a bespoke boss) is skipped with a loud ERROR
 *    rather than throwing the whole plugin away;
 *  - the cache must agree the npc is player-attackable (an "Attack" option + a combat level);
 *  - far-flung regions are force-loaded at world init so the garrison can path with nobody near;
 *  - `aggroCheck` stays the engine's trivial one — never put logic there (a throw kills the loop).
 */
class HostileOccupierPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val zones = ArrayList<HostileZone>()
    private var anySingle = false

    init {
        val seen = HashSet<Int>()
        for (cfg in HostileZones.all) {
            try {
                val packs = ArrayList<MonsterPack>()
                for (line in cfg.occupiers) {
                    val id = runCatching { getRSCM(line.npcName) }.getOrNull()
                    if (id == null) {
                        logger.error { "[HOSTILE OCCUPIERS] '${cfg.key}': unknown npc '${line.npcName}' — line skipped." }
                        continue
                    }
                    if (!seen.add(id) || r.npcCombatDefs.containsKey(id) || hasNpcDeathHandler(id)) {
                        logger.error { "[HOSTILE OCCUPIERS] '${cfg.key}': npc ${line.npcName} already has a combat def / death handler elsewhere — line skipped (one owner per npc id)." }
                        continue
                    }
                    setCombatDef(line.npcName, line.combatDef)
                    val def = getNpc(id)
                    if (def.combatLevel <= 0 || def.actions.none { it == "Attack" }) {
                        logger.error { "[HOSTILE OCCUPIERS] '${cfg.key}': npc ${line.npcName} is NOT player-attackable (combatLevel=${def.combatLevel}, actions=${def.actions.filterNotNull()})." }
                    }
                    val raw = line.explicitStaging ?: CityFrontiers.gridArea(line.area ?: cfg.area, line.spacing)
                    val walkable = raw.filter { StaticTerrain.isWalkable(it.x, it.z) && !PvpZones.isSafe(it) }
                    val staging = CityFrontiers.capEvenly(walkable, line.count)
                    if (staging.isEmpty()) {
                        logger.error { "[HOSTILE OCCUPIERS] '${cfg.key}': ${line.npcName} has no walkable muster ground — line skipped." }
                        continue
                    }
                    packs += MonsterPack(
                        npcName = line.npcName,
                        count = staging.size,
                        staging = staging,
                        walkRadius = line.walkRadius,
                        respawnDelay = line.respawnDelay,
                        faction = Faction.ENEMY,
                        singleCombat = line.singleCombat,
                        combatLevelOverride = line.combatLevelOverride,
                    )
                    if (line.singleCombat) anySingle = true
                    registerLoot(line)
                    logger.info { "[HOSTILE OCCUPIERS] '${cfg.display}' ${line.npcName}: ${staging.size} muster point(s)." }
                }
                if (packs.isEmpty()) continue
                val pad = ACTIVATION_PADDING
                val b = cfg.area
                val padded = Area(b.bottomLeftX - pad, b.bottomLeftY - pad, b.topRightX + pad, b.topRightY + pad)
                val zone = HostileZone(
                    name = cfg.display,
                    packs = packs,
                    safeArea = null,
                    keep = null,
                    activeWhen = { w -> w.players.any { it !is PkBot && it.index >= 0 && padded.contains(it.tile) } },
                )
                zones += zone
                HostileRuntime.occupiers[cfg.key] = zone
            } catch (e: Throwable) {
                logger.error(e) { "[HOSTILE OCCUPIERS] '${cfg.key}' failed to build; skipped (other zones unaffected)." }
            }
        }

        onWorldInit { forceLoadRegions(world) }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            zones.forEach { it.tick(world) }
            world.timers[timer] = TICK
        }
        if (anySingle) {
            val sweepTimer = TimerKey()
            onWorldInit { world.timers[sweepTimer] = 1 }
            onTimer(sweepTimer) {
                zones.forEach { it.enforceSingleCombatTick(world) }
                world.timers[sweepTimer] = 1
            }
        }
    }

    /** Kill loot: the line's table, killer-owned on the death tile (no coins minted here). */
    private fun registerLoot(line: OccupierLine) {
        val table = line.lootTable ?: return
        onNpcDeath(line.npcName) {
            val mob = npc
            val killer = mob.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            if (killer is PkBot) return@onNpcDeath
            table.roll(world).forEach { rolled ->
                val id = runCatching { getRSCM(rolled.item) }.getOrNull() ?: return@forEach
                world.spawn(GroundItem(id, rolled.amount, mob.tile, killer))
            }
        }
    }

    /** Build collision for every region a zone overlaps so its guards path with nobody nearby. */
    private fun forceLoadRegions(world: World) {
        val regions = sortedSetOf<Int>()
        for (cfg in HostileZones.all) {
            if (cfg.occupiers.isEmpty()) continue
            val reach = 8
            val box = cfg.area
            val xMin = box.bottomLeftX - reach; val xMax = box.topRightX + reach
            val zMin = box.bottomLeftY - reach; val zMax = box.topRightY + reach
            for (rx in (xMin shr 6)..(xMax shr 6)) for (rz in (zMin shr 6)..(zMax shr 6)) regions += (rx shl 8) or rz
        }
        if (regions.isEmpty()) return
        runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }
            .onFailure { logger.error(it) { "[HOSTILE OCCUPIERS] region force-load failed" } }
        logger.info { "[HOSTILE OCCUPIERS] force-loaded ${regions.size} region(s) for ${zones.size} garrison(s)." }
    }

    private companion object {
        /** Garrison upkeep cadence (~3s), same as the frontiers. */
        const val TICK = 5
        /** A real player this close to a zone's box keeps its garrison live (= the loot engine's). */
        const val ACTIVATION_PADDING = 32
    }
}
