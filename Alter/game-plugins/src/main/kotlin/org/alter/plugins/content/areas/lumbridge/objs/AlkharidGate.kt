package org.alter.plugins.content.areas.lumbridge.objs

import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * The Al-Kharid toll gate is **removed** — there is no toll and no wall. Both gate halves at
 * (3268, 3227) and (3268, 3228) are deleted on world init so the road to Al-Kharid is permanently
 * open: players, companions and bots can all path straight through to the NPCs on the far side.
 *
 * Deletion happens in [onWorldInit]; `world.getObject` force-loads the region (createIfNeeded) so
 * the static gate objects exist by the time we remove them, and removing the object also clears its
 * collision flag on that tile (matches the construction/agility "spawn in onWorldInit" pattern).
 */
class AlkharidGate(
    r: PluginRepository, world: World, server: Server
) : KotlinPlugin(r, world, server) {

    /** The two static gate halves, type 0 (the wall/door slot). */
    private val gateTiles = arrayOf(Tile(3268, 3227), Tile(3268, 3228))

    init {
        onWorldInit {
            gateTiles.forEach { tile ->
                world.getObject(tile, type = 0)?.let { world.remove(it) }
            }
        }
    }
}
