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
 * Spawns the tiered Thieving stall row in **Falador's north square** (the open cobbled ground just
 * north of Falador's north gate). Thieving is a CRIME — you don't steal in your own capital
 * (Lumbridge), but Falador is a neutral city, so the stalls live there. The existing
 * [StallThievingPlugin] binds "Steal-from" globally by object id (via the stalls.json service) and
 * handles the empty/respawn swap — so this plugin only SPAWNS the stall objects; no bind logic. Each
 * stall key is guarded so a missing id is skipped, not fatal.
 *
 * Tiles verified walkable against the cache collision dump (region 11829, z3396 is a clean run).
 * Donor-exclusive stalls (cosmetic/pet) are intentionally NOT here — reserved for the W6 Donor Zone.
 */
class SwampStallSpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** (stall object key, x, z) in Falador's north square (z3396 clear run) — ladder is in stalls.json. */
    private val stalls = listOf(
        Triple("object.veg_stall", 2975, 3396),
        Triple("object.bakers_stall", 2976, 3396),
        Triple("object.silk_stall", 2977, 3396),
        Triple("object.seed_stall", 2978, 3396),
        Triple("object.fur_stall", 2979, 3396),
        Triple("object.fish_stall", 2980, 3396),
        Triple("object.silver_stall", 2981, 3396),
        Triple("object.spice_stall", 2982, 3396),
        Triple("object.gem_stall", 2983, 3396),
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
            logger.info { "swamp-stalls: spawned $spawned thieving stalls in Falador's north square." }
        }
    }

    private companion object {
        const val OBJ_TYPE = 10
    }
}
