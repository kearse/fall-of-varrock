package org.alter.plugins.content.skills.thieving.stall

import dev.openrune.cache.CacheManager.getObject
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
 * north of Falador's north gate). Thieving is a CRIME â€” you don't steal in your own capital
 * (Lumbridge), so the stalls live in Falador.
 *
 * The row spent 2026-09-01 â†’ 2026-09-13 in the Mire working yard: Falador had been flipped to a
 * raid city (full-PvP streets), which turned the "Safe Zone" Thieving teleport into a PK ambush.
 * Block 1 PR-3 restored Falador as a safe hub and put the raid-city framework dormant
 * (`RaidCities.all` is empty, `PvpZones` carves Falador out), so the reason for the move is gone
 * and the stalls are back where they belong.
 *
 * The existing [StallThievingPlugin] binds "Steal-from" globally by object id (via the stalls.json
 * service) and handles the empty/respawn swap â€” so this plugin only SPAWNS the stall objects; no
 * bind logic. Each stall key is guarded so a missing id is skipped, not fatal.
 *
 * Tiles verified walkable against the cache collision dump (region 11829, z3396 is a clean run).
 * Clear of the White Wall checkpoint: `WhiteWallCheckpoint` dressing/posts top out at x2971 on this
 * row, four tiles west of the first stall, and its raiders only ever target players on the quest's
 * DEFEND step â€” keep any new dressing east of x2974 off z3396.
 * Donor-exclusive stalls (cosmetic/pet) are intentionally NOT here â€” reserved for the W6 Donor Zone.
 */
class SwampStallSpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /**
     * (stall object key, x, z) in Falador's north square â€” the ladder of tiers is in stalls.json.
     *
     * Two things were wrong here until 2026-09-18 ("thieving stalls are still messed up"):
     *
     *  1. **Six of the nine were not clickable.** Each of these keys names one def out of a stall
     *     FAMILY, and only some defs in a family carry a `Steal-from` action. The base ids picked
     *     here for silk (629), seed (6947), fur (632), silver (628), spice (633) and gem (631) have
     *     *no actions at all* in rev-228, and `StallThievingPlugin` only binds defs that expose one
     *     â€” so six of the nine stalls stood there inert with no right-click. Each is now spawned as
     *     a sibling def that does carry the option; every id here is already listed in stalls.json,
     *     so tier, xp and loot table are unchanged.
     *  2. **They overlapped.** Every stall is 2x2 (the baker's is 2x1) but they were spawned one
     *     tile apart, so each one's east half sat inside its neighbour. Spacing is now 3 tiles: two
     *     for the stall, one to walk between them.
     *
     * Collision verified against the region 11829 dump: z3396 is walkable across the whole run, and
     * z3397 â€” which the 2x2 stalls also occupy â€” is clear from x2957 east, well before the row
     * starts. The row ends at x3000, inside the square.
     */
    private val stalls = listOf(
        Triple("object.veg_stall", 2975, 3396),          // 4706 â€” carries Steal-from
        Triple("object.bakers_stall", 2978, 3396),       // 6945 â€” carries Steal-from
        Triple("object.silk_stall_11729", 2981, 3396),   // base 629 has no actions
        Triple("object.seed_stall_7053", 2984, 3396),    // base 6947 has no actions
        Triple("object.fur_stall_4278", 2987, 3396),     // base 632 has no actions
        Triple("object.fish_stall", 2990, 3396),         // 4277 â€” carries Steal-from
        Triple("object.silver_stall_6164", 2993, 3396),  // base 628 has no actions
        Triple("object.spice_stall_11733", 2996, 3396),  // base 633 has no actions
        Triple("object.gem_stall_6162", 2999, 3396),     // base 631 has no actions
    )

    init {
        onWorldInit {
            var spawned = 0
            stalls.forEach { (key, x, z) ->
                val id = runCatching { getRSCM(key) }.getOrNull() ?: run {
                    logger.warn { "swamp-stalls: '$key' not in cache; skipped." }; return@forEach
                }
                // A stall def with no Steal-from action can never be bound by StallThievingPlugin,
                // so it would stand here as scenery. Six of the nine did exactly that until
                // 2026-09-18 â€” say so at boot rather than leaving it to a player report.
                val actions = runCatching { getObject(id).actions }.getOrNull()
                    ?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
                if (actions.none { it.replace('-', ' ').equals("steal from", ignoreCase = true) }) {
                    logger.warn {
                        "swamp-stalls: '$key' (id $id) has no Steal-from action (cache actions=$actions) â€” " +
                            "it will spawn but cannot be thieved. Pick a sibling def that carries the option."
                    }
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

