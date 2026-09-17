package org.alter.plugins.content.areas.wilderness

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Graveyard of Shadows** — the Wilderness undead field (player report 2026-09-13: "zombies
 * are not in wildy but where skilling area is").
 *
 * The complaint had two halves and this is the second one. The Mire's undead corner is now passive
 * and small (`areas/swamp/SwampHubPlugin.spawnUndeadCorner`); the aggressive zombies live here,
 * in the deep Wilderness, which is where the report said they belonged and where OSRS puts them.
 *
 * The site is the Graveyard of Shadows, already a named box in
 * [org.alter.plugins.content.combat.PvpZones] (3136,3656 → 3199,3703) and already flagged
 * multi-combat there. It is directly served by the **Deep Wilderness PKers** portal row, which
 * lands at (3170,3700) inside the box — so this is a real destination players already travel to,
 * not a pocket nobody visits. Larran's big chest sits at (3172,3700) on the same landing
 * (`objects/larranschest`), which makes the graveyard a coherent trip: aggressive undead to fight,
 * a chest to spend keys on, and PK bots hunting the same ground.
 *
 * Spawns are pushed **west of the landing** so a player arriving by teleport is not in aggro range
 * on the tick they appear — the same mistake that had to be fixed for Callisto (see the 2026-09
 * feedback doc). `npc.zombie` (id 26) is in the world dump, so WorldSpawnsPlugin registers its real
 * combat def (22 hp, aggressive within its 4-tile radius) and this bespoke spawn inherits it; kills
 * credit a Slayer zombie contract by cache name exactly as the Mire corner's do.
 *
 * TUNABLE: the tiles are spread across the open graveyard ground; nudge any that land on a
 * gravestone or the ruined walls.
 */
class WildernessUndeadPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        if (!runCatching { getRSCM(ZOMBIE) }.isSuccess) {
            logger.warn { "wilderness-undead: '$ZOMBIE' not in cache; graveyard not spawned." }
        } else {
            GRAVEYARD_TILES.forEach { t ->
                spawnNpc(ZOMBIE, x = t.x, z = t.z, height = t.height, walkRadius = 5, direction = Direction.SOUTH)
            }
            logger.info {
                "wilderness-undead: Graveyard of Shadows ready " +
                    "(${GRAVEYARD_TILES.size} aggressive zombies west of the deep-wild landing)."
            }
        }
    }

    private companion object {
        /** The aggressive Slayer-roster zombie (id 26) — the opposite choice to the Mire's id 64. */
        const val ZOMBIE = "npc.zombie"

        /**
         * The graveyard floor, 8–20 tiles WEST of the Deep Wilderness PKers landing (3170,3700) so
         * the arrival tile stays outside every spawn's aggro reach (walkRadius 5 + a 4-tile radius).
         * Twelve of them: enough that the field reads as a graveyard and a zombie task can be
         * finished here, spread wide enough that a player fights two or three at a time rather than
         * the whole cluster.
         */
        val GRAVEYARD_TILES = listOf(
            Tile(3160, 3698, 0), Tile(3157, 3694, 0), Tile(3162, 3691, 0),
            Tile(3154, 3689, 0), Tile(3159, 3685, 0), Tile(3164, 3684, 0),
            Tile(3151, 3684, 0), Tile(3156, 3680, 0), Tile(3161, 3677, 0),
            Tile(3153, 3675, 0), Tile(3158, 3671, 0), Tile(3163, 3670, 0),
        )
    }
}
