package org.alter.plugins.content.skills.thieving.stall

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Spawns the tiered Thieving stall row in the **Mire working yard** (west of the yard bank,
 * approached from the south). The row originally lived in Falador's north square; it moved home
 * with the rest of the supply skilling — the yard is the war-supply hub, so the stalls join the
 * bank + processing stations there. The existing [StallThievingPlugin] binds
 * "Steal-from" globally by object id (via the stalls.json service) and handles the empty/respawn
 * swap — so this plugin only SPAWNS the stall objects; no bind logic. Each stall key is guarded so a
 * missing id is skipped, not fatal.
 *
 * Tiles verified walkable against the cache collision dump (region 12849: z3190 x3238-3246 is a
 * clean run, with the z3189 approach row clear x3227-3247).
 * Donor-exclusive stalls (cosmetic/pet) are intentionally NOT here — reserved for the W6 Donor Zone.
 */
class SwampStallSpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** (stall object key, x, z) in the Mire working yard (z3190 clear run, W of the bank) — ladder is in stalls.json. */
    private val stalls = listOf(
        Triple("object.veg_stall", 3238, 3190),
        Triple("object.bakers_stall", 3239, 3190),
        Triple("object.silk_stall", 3240, 3190),
        Triple("object.seed_stall", 3241, 3190),
        Triple("object.fur_stall", 3242, 3190),
        Triple("object.fish_stall", 3243, 3190),
        Triple("object.silver_stall", 3244, 3190),
        Triple("object.spice_stall", 3245, 3190),
        Triple("object.gem_stall", 3246, 3190),
    )

    init {
        onWorldInit {
            var spawned = 0
            stalls.forEach { (key, x, z) ->
                val id = runCatching { getRSCM(key) }.getOrNull() ?: run {
                    logger.warn { "swamp-stalls: '$key' not in cache; skipped." }; return@forEach
                }
                world.spawn(DynamicObject(id = id, type = OBJ_TYPE, rot = 0, tile = Tile(x, z, 0)))
                spawned++
            }
            logger.info { "swamp-stalls: spawned $spawned thieving stalls in the Mire working yard." }
        }
    }

    private companion object {
        const val OBJ_TYPE = 10
    }
}
