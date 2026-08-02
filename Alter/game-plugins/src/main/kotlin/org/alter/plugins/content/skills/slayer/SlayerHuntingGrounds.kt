package org.alter.plugins.content.skills.slayer

import org.alter.game.model.Tile

/**
 * Where each combat contract's target hunts — a known-walkable tile in that monster's hunting
 * ground, anchored to real spawns. Keyed by the same RSCM npc key stored in
 * [org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR].
 *
 * Two consumers, which is why this lives outside [SlayerPlugin]:
 *  - `::slayertele` ([SlayerPlugin]) teleports the player here, and
 *  - [SlayerHudPlugin] publishes it as the packed guide-target varp the client's Task Helper
 *    (`net.runelite.client.plugins.loftaskhelper`) points its arrow at.
 *
 * A task with no entry here (e.g. skeletons, which still lack an accessible spawn) simply has no
 * marker: `::slayertele` says so and the client draws no arrow. TUNABLE.
 */
object SlayerHuntingGrounds {
    private val grounds: Map<String, Tile> = mapOf(
        "npc.goblin" to Tile(3247, 3244, 0),       // goblin field, east of the castle
        "npc.rat_2854" to Tile(3206, 3202, 0),     // castle courtyard rats
        "npc.giant_rat" to Tile(3163, 3173, 0),    // the western swamp
        "npc.giant_spider" to Tile(3246, 3248, 0), // the eastern spider nest
        "npc.man" to Tile(3216, 3219, 0),          // townsfolk by the castle
        "npc.woman" to Tile(3217, 3205, 0),
        "npc.cow" to Tile(3178, 3316, 0),          // the cow field, north-west
        "npc.chicken" to Tile(3172, 3293, 0),      // the farm pen
        "npc.guard" to Tile(3221, 3222, 0),        // castle guards
        "npc.zombie" to Tile(3231, 3191, 0),       // the Mire undead corner, SW of the yard house (SwampHubPlugin)
    )

    fun of(npcKey: String): Tile? = grounds[npcKey]
}
