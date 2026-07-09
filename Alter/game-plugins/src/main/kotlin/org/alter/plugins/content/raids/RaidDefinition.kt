package org.alter.plugins.content.raids

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropTable

/**
 * Declarative data for one raid/wave encounter (R0). **Adding a raid = adding a [RaidDefinition]
 * to [Raids].** Stages run in order: a stage's NPCs spawn, and when they're all dead the next
 * stage begins; after the last stage the party is rewarded and the instance torn down.
 *
 * Tiles ([entryTile], spawn tiles) are **source-arena coordinates** — the controller translates
 * them into the live instance via [RaidInstance.translate]. Encounter NPCs should have a combat
 * def registered (like the boss plugins) so they spawn with real stats.
 */
data class RaidSpawn(val npcKey: String, val tile: Tile, val count: Int = 1)

data class RaidStage(val announce: String, val spawns: List<RaidSpawn>)

data class RaidDefinition(
    val key: String,            // also the entry command (::<key>)
    val name: String,
    /** Chunk-aligned arena to instance-copy from the live map. */
    val sourceArea: Area,
    /** Where the party lands inside the arena (source coords). */
    val entryTile: Tile,
    /** Global tile players are sent to on completion/exit/death. */
    val exitTile: Tile,
    /** How many map levels (0..n-1) the arena spans. */
    val levels: Int,
    val stages: List<RaidStage>,
    val reward: DropTable,
    val points: Int,
    val maxParty: Int = 1, // solo-first; raises when RaidParty lands
)
