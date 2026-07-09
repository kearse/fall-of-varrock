package org.alter.plugins.content.bosses

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Boots the [BossLairs] catalog: flags multi-way regions and **force-loads every lair's
 * collision** so the boss (and anything it spawns/chases) is pathable even before a player
 * is nearby — the fix proven for war troops (`AttackDirector.forceLoadBattlefieldRegions`,
 * `CityFrontierPlugin.forceLoadFrontierRegions`). Idempotent:
 * [org.alter.game.fs.DefinitionSet.loadRegions] skips already-active regions.
 */
class BossLairsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Multi-combat flags are a config concern → set them up front (mirrors KbdConfigsPlugin).
        for (lair in BossLairs.all) {
            if (!lair.multi) continue
            for (region in regionsOf(lair.box)) {
                runCatching { setMultiCombatRegion(region = region) }
            }
        }

        // Collision force-load must wait until the base valid-regions have loaded.
        onWorldInit { forceLoadLairRegions() }
    }

    private fun forceLoadLairRegions() {
        val regions = sortedSetOf<Int>()
        for (lair in BossLairs.all) regions += regionsOf(lair.box)
        if (regions.isEmpty()) return
        runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }
            .onFailure { logger.error(it) { "[BOSS] lair region force-load failed" } }
        logger.info { "[BOSS] force-loaded ${regions.size} lair region(s) for ${BossLairs.all.size} lair(s)." }
    }

    /** Every region id a `[baseX, baseZ, w, h]` tile box overlaps. */
    private fun regionsOf(box: IntArray): List<Int> {
        val out = ArrayList<Int>()
        val rxMin = box[0] shr 6; val rxMax = (box[0] + box[2] - 1) shr 6
        val rzMin = box[1] shr 6; val rzMax = (box[1] + box[3] - 1) shr 6
        for (rx in rxMin..rxMax) for (rz in rzMin..rzMax) out += (rx shl 8) or rz
        return out
    }
}
