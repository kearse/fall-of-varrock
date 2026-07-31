package org.alter.plugins.content.combat

import org.alter.game.model.Area
import org.alter.game.model.Tile

/**
 * The PvP zoning model (from the hand-drawn map):
 *  - **Wilderness (red):** the only PvP area. Attack is enabled, unprovoked attacks skull, and death
 *    drops your loot. A big custom region (classic wild up north, extending south through Misthalin).
 *  - **Single combat (yellow):** the shallower core of the wild — one-on-one only. Everywhere else in
 *    the wild is **multi**.
 *  - **Safe:** EVERYTHING outside the red is safe by default. A few safe carve-outs sit INSIDE the red
 *    (Grand Exchange, the Varrock/Edgeville banks) and override it. Banks/cities outside the red are
 *    already safe for free.
 *  - **Depth level:** combat-level attack range scales with how many tiles you've pushed into the wild
 *    from its safe (southern) edge — deeper = wider range (anyone can hit you very deep).
 *
 * Coordinates are APPROXIMATED from the freehand map and are TUNABLE — use `::zone` in-game to read
 * the live classification + level at your tile and we refine the boxes.
 */
object PvpZones {

    private const val TILES_PER_LEVEL = 8
    private const val MAX_WILD_LEVEL = 56

    /** Depth origin (safe-side edge) — the top of Lumbridge, just N of the furnace (3226,3256). Depth
     *  (and so wilderness level) is measured north from here. */
    private const val WILD_SOUTH_EDGE_Z = 3258

    /** RED — the PvP wilderness. Southern edge dropped to the TOP OF LUMBRIDGE (z3258, right after
     *  the furnace at 3226,3256) across the whole width. Safe towns inside this band (Falador, the
     *  banks, GE) are carved out below. */
    /** Lumbridge CONTESTED FRONTIER (master design brief §4): a guarded PvP ring at the hobgoblin/
     *  knight band on the WEST and EAST sides (the north is already wilderness; the safe core below
     *  protects town + spawn + the goblin front). Level is CAPPED to [FRONTIER_LEVEL] (not open
     *  any-level PvP) since it sits at the town's latitude. CONSERVATIVE + TUNABLE — verify with ::zone. */
    private val CONTESTED: List<Area> = listOf(
        Area(3152, 3190, 3182, 3280),  // west frontier band
        Area(3290, 3190, 3320, 3280),  // east frontier band
    )
    private const val FRONTIER_LEVEL = 10

    /** The main custom PvP-wilderness expanse — south edge at the TOP OF LUMBRIDGE (z3258, "the line").
     *  This single rectangle is the authority for where the custom wild is; bot spawns tile it directly
     *  (see [org.alter.plugins.content.bots.BotZones]) so PKers populate the whole custom wild and can
     *  never drift off this boundary. Move this and both the zoning AND the bots follow. */
    val mainWilderness: Area = Area(2944, 3258, 3450, 3968)

    private val WILDERNESS: List<Area> = listOf(
        mainWilderness,                // whole wilderness, south edge at the top of Lumbridge
        Area(3245, 3214, 3267, 3256),  // east bank of the Lum (Lumbridge ↔ Al Kharid) PK pocket
    ) + CONTESTED

    /** YELLOW — single-combat areas. */
    private val SINGLE: List<Area> = listOf(
        Area(3100, 3350, 3320, 3550),  // shallow core of the main wild
        Area(3245, 3214, 3267, 3256),  // east-Lum PK pocket is single combat
    )

    /** Safe carve-outs INSIDE the red (everywhere OUTSIDE the red is already safe).
     *  Falador is NO LONGER carved out — it's a raid city now ([RaidCities]): full PvP streets,
     *  with only its banks safe (auto-carved by [BankSafezonePlugin]). */
    private val SAFE_INSIDE_RED: List<Area> = listOf(
        Area(3140, 3470, 3185, 3515), // Grand Exchange
        Area(3178, 3432, 3196, 3453), // Varrock west bank
        Area(3250, 3416, 3257, 3424), // Varrock east bank
        Area(3067, 3488, 3098, 3522), // Edgeville town (whole town safe, up to the wilderness ditch)
        // Lumbridge SAFE CORE (master design brief §4): town + spawn (3218,3218) + the goblin front
        // (gap 1–17 from the city box) — where new players train. Carved out of the wilderness so the
        // immediate frontier is NEVER PvP; only the hobgoblin/knight band beyond it is. TUNABLE.
        Area(3182, 3172, 3290, 3280),
    )

    /** Extra safe boxes registered at runtime (e.g. bank booths discovered in the world). */
    private val safeDynamic = mutableListOf<Area>()

    /**
     * RAID CITIES ([org.alter.plugins.content.raidzones.RaidCities]) — overrun cities turned
     * open-PvP loot grounds (Falador, Al Kharid). Each is red at a FIXED wilderness level
     * (its `raidWildLevel`) regardless of depth, and cities outside [mainWilderness]
     * (Al Kharid is south of the line) become red through this list. Their banks stay safe
     * via [BankSafezonePlugin]'s dynamic carve-outs.
     *
     * A `get()` (not an eager val) so classload order never matters: RaidCities never
     * references this object back, but bots/plugins touch [mainWilderness] during their own
     * class init and we must not force RaidCities' tables to build before the RSCM is up.
     */
    private val raidCities: List<Pair<Area, Int>>
        get() = org.alter.plugins.content.raidzones.RaidCities.all.map { it.area to it.raidWildLevel }

    private fun inRed(t: Tile): Boolean = WILDERNESS.any { it.contains(t) } || raidCities.any { it.first.contains(t) }
    private fun inCarveout(t: Tile): Boolean = SAFE_INSIDE_RED.any { it.contains(t) } || safeDynamic.any { it.contains(t) }

    /** True PvP-enabled wilderness tile (inside red, not a safe carve-out). */
    fun isWilderness(t: Tile): Boolean = inRed(t) && !inCarveout(t)

    /** Safe = anything that isn't live wilderness (outside red, or a carve-out inside it). */
    fun isSafe(t: Tile): Boolean = !isWilderness(t)

    fun isSingle(t: Tile): Boolean = isWilderness(t) && SINGLE.any { it.contains(t) }

    fun isMulti(t: Tile): Boolean = isWilderness(t) && SINGLE.none { it.contains(t) }

    /** Wilderness level by depth pushed into the wild (0 if not in the wild). */
    fun wildernessLevel(t: Tile): Int {
        if (!isWilderness(t)) return 0
        raidCities.firstOrNull { it.first.contains(t) }?.let { return it.second } // raid cities: fixed level
        if (CONTESTED.any { it.contains(t) }) return FRONTIER_LEVEL // §4: the town frontier is capped, not open any-level
        val depth = t.z - WILD_SOUTH_EDGE_Z
        // Isolated PK pockets south of the main wild (e.g. the east-Lum arena) are open PvP — any level.
        if (depth < 0) return MAX_WILD_LEVEL
        return (depth / TILES_PER_LEVEL + 1).coerceIn(1, MAX_WILD_LEVEL)
    }

    /** Register a safe radius around a tile (used to auto-protect bank booths inside the wild). */
    fun addSafeArea(area: Area) {
        safeDynamic += area
    }

    fun safeAround(tile: Tile, radius: Int) {
        addSafeArea(Area(tile.x - radius, tile.z - radius, tile.x + radius, tile.z + radius))
    }
}
