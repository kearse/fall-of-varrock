package org.alter.plugins.content.combat

import dev.openrune.cache.CacheManager
import dev.openrune.cache.MAPS
import dev.openrune.cache.filestore.loadLocations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Auto-protects every bank that falls inside the wilderness. Scans the cache loc data for
 * Bank booth / Bank chest / Bank deposit box objects across the wild region range and registers a
 * small safe radius around each via [PvpZones.safeAround] — so no bank is ever a PvP spot, however
 * far the wilderness border is dropped.
 *
 * Runs once at construction: [CacheManager] is ready by then, and this map-decrypted cache decodes
 * locs with empty XTEA keys (same approach as [org.alter.plugins.content.war.StaticTerrain] and the
 * `mapDump` tool). Banks OUTSIDE the wilderness are already safe by default, so we only scan the
 * region band that overlaps the wild.
 */
class BankSafezonePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        var banks = 0
        val keys = IntArray(4) // map-decrypted cache → locs decode with empty keys
        val seen = HashSet<Long>()
        for (rx in REGION_X) {
            for (ry in REGION_Y) {
                val data = try {
                    CacheManager.cache.data(MAPS, "l${rx}_$ry", keys)
                } catch (e: Exception) {
                    null
                } ?: continue
                val baseX = rx shl 6
                val baseZ = ry shl 6
                try {
                    loadLocations(data) { loc ->
                        if (loc.height != 0) return@loadLocations
                        val name = CacheManager.getObjectOrDefault(loc.id).name ?: return@loadLocations
                        if (!isBank(name)) return@loadLocations
                        val wx = baseX + loc.localX
                        val wz = baseZ + loc.localY
                        if (seen.add((wx.toLong() shl 20) or wz.toLong())) {
                            PvpZones.safeAround(Tile(wx, wz), BANK_SAFE_RADIUS)
                            banks++
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Bank scan failed for region l${rx}_$ry (skipped)." }
                }
            }
        }
        logger.info { "Bank safe-zones: protected $banks bank object(s) inside the wilderness band." }
    }

    private fun isBank(name: String): Boolean = when (name.lowercase()) {
        "bank booth", "bank chest", "bank deposit box" -> true
        else -> false
    }

    private companion object {
        const val BANK_SAFE_RADIUS = 8           // tiles of safety around each bank object
        val REGION_X = 46..53                    // x 2944..3519 — covers the wilderness width
        val REGION_Y = 50..62                    // z 3200..4031 — covers z3258..3968
    }
}
