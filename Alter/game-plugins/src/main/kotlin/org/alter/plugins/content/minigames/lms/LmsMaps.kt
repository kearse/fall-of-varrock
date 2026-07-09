package org.alter.plugins.content.minigames.lms

import org.alter.game.model.Area
import org.alter.game.model.Tile

/**
 * The **Last Man Standing** battle-royale maps.
 *
 * Each entry copies a chunk-aligned slice of the real OSRS **Deserted Island** (the authentic LMS
 * landmass, in the far instance-band regions 13658/13659 — a ruined village of houses, dead trees,
 * campfires and chests, verified against the cache map dump). A game copies [sourceArea] into a fresh
 * [org.alter.plugins.content.raids.RaidInstance]; competitors are scattered across the walkable ground
 * (found by a live flood-fill from [center], NOT by hand-picked coords — the island is irregular), and
 * the shrinking fog closes in on [center].
 *
 * To add a map, drop another [LmsMap] into [ALL] — the engine rotates through the list per game.
 */
data class LmsMap(
    /** Player-facing name (shown on entry + in the winner broadcast). */
    val name: String,
    /** Chunk-aligned source slice copied into each instance (RaidInstance snaps to chunk bounds). */
    val sourceArea: Area,
    /** The island's open heart: seed for the walkable-tile flood-fill AND the fog's focal point. */
    val center: Tile,
) {
    /** Half-width (in tiles) of the fully-open safe zone at game start — should blanket the island. */
    val startFogRadius: Int get() = 40
}

object LmsMaps {

    /** Region 13658 — the southern half of the LMS island (the ruined village core). */
    private val DESERTED_ISLAND = LmsMap(
        name = "Deserted Island",
        sourceArea = Area(3392, 5760, 3455, 5823),
        center = Tile(3423, 5790, 0),
    )

    /** Region 13659 — the northern half (broken up by water inlets: a tighter, riskier arena). */
    private val THE_RUINS = LmsMap(
        name = "The Ruins",
        sourceArea = Area(3392, 5824, 3455, 5887),
        center = Tile(3400, 5830, 0),
    )

    /** Rotation order — games cycle through these. */
    val ALL: List<LmsMap> = listOf(DESERTED_ISLAND, THE_RUINS)
}
