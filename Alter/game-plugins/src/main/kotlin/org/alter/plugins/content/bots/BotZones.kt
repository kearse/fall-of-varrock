package org.alter.plugins.content.bots

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.combat.PvpZones

/**
 * Data-driven registry of wilderness "PK bot" spawn zones.
 *
 * A zone is an open-world [Area] that, while a real player is near, maintains a small population of
 * roaming [PkBot]s drawn from a depth-tiered loadout pool — so leaving a city gets more dangerous
 * the deeper you go. Adding a zone = one [BotZoneConfig]; [BotSpawnPlugin] picks it up automatically
 * (mirrors [org.alter.plugins.content.war.CityFrontiers] / `Sieges`).
 *
 * Geometry is intentionally generous: muster points are filtered to walkable land at runtime by
 * [org.alter.plugins.content.war.StaticTerrain] (the in-code twin of the `mapDump`), and spawns are
 * jittered off walls by `World.findRandomTileAround` — so a box overlapping swamp/river simply
 * self-selects to its open ground (same division of labour as the hobgoblin frontier).
 */

/** A weighted pool of loadout keys (resolved via [BotLoadouts.get]). */
data class BotTier(val loadouts: List<Pair<String, Int>>) {
    init { require(loadouts.isNotEmpty()) { "BotTier must have at least one loadout" } }

    /** Roll a loadout key by weight. */
    fun roll(): String {
        val total = loadouts.sumOf { it.second }
        var r = (0 until total).random()
        for ((key, weight) in loadouts) {
            if (r < weight) return key
            r -= weight
        }
        return loadouts.first().first
    }
}

data class BotZoneConfig(
    val key: String,
    val displayName: String,
    /** The open-world rectangle the zone occupies. */
    val area: Area,
    /**
     * Fixed loadout pool for this zone, or `null` (default) to TIER DYNAMICALLY by our custom
     * wilderness level at each spawn tile ([BotZones.tierForWildLevel]) — the standard wilderness
     * behaviour, so danger scales with depth without hand-banding boxes.
     */
    val tier: BotTier? = null,
    /** Target live-bot population while the zone is active. */
    val target: Int,
    /** Spacing (tiles) between candidate muster points across [area]. */
    val spacing: Int = 6,
    /** How far a bot wanders from its spawn while idle. */
    val roamRadius: Int = 8,
    /** How far a bot chases a player from its spawn before giving up (tether). */
    val leashRadius: Int = 18,
    /** Zone-tick cycles to wait after a death before refilling that slot. */
    val respawnDelayTicks: Int = 10,
    /** A real player within [area] expanded by this many tiles activates the zone. */
    val activationPadding: Int = 32,
)

object BotZones {

    // Tier pools. Edge zones lean classic/budget; deep zones are elite NHers.
    // Depth-tiered loadout pools: weak metal PKers near the entrance, escalating to rune, then the
    // elite (above-rune) NHers in the real wilderness.
    private val T_BRONZE = BotTier(listOf("bronze_pker" to 2, "iron_pker" to 2))
    private val T_STEEL = BotTier(listOf("steel_pker" to 2, "black_pker" to 2))
    private val T_MITHRIL = BotTier(listOf("mithril_pker" to 2, "adamant_pker" to 1))
    // From rune up, the budget mage/ranger/DHer variants join so you can't just pray one style.
    private val T_RUNE = BotTier(listOf(
        "rune_pker" to 2, "adamant_pker" to 1, "range_mid" to 1, "mage_mid" to 1, "dharok_mid" to 1,
    ))
    // Elite tiers field the full variety: melee NHers, freezers, rangers, DHers, blood mages, claws.
    private val T_ELITE = BotTier(listOf(
        "elite_nh" to 1, "claws_brid" to 1, "range_elite" to 1, "mage_elite" to 1,
        "ancient_mage" to 1, "dharok_dher" to 1, "classic_hybrid" to 1,
    ))
    private val T_ELITE_DEEP = BotTier(listOf(
        "elite_nh" to 2, "claws_brid" to 1, "range_elite" to 1, "mage_elite" to 1,
        "ancient_mage" to 1, "dharok_dher" to 1,
    ))

    /**
     * Loadout tier for a given custom wilderness level ([PvpZones.wildernessLevel], depth north from
     * the wild's south edge). This is the single source of truth for "tier PKers by our custom
     * wilderness level" — a bot's danger is decided by how deep it spawned, not by a hand-picked box:
     * weak metal PKers at the frontier, escalating to elite "above-rune" NHers in the deep wild.
     */
    fun tierForWildLevel(level: Int): BotTier = when {
        level <= 6 -> T_BRONZE     // frontier / shallow
        level <= 12 -> T_STEEL
        level <= 20 -> T_MITHRIL
        level <= 30 -> T_RUNE      // rune + budget mage/range/DHer variety
        level <= 44 -> T_ELITE     // full elite variety
        else -> T_ELITE_DEEP       // deepest wild — the scariest NHers
    }

    /** Tiles wider than this get split, so each zone activates locally (a player at one end of the wild
     *  doesn't spawn bots at the other) and spreads density across the whole custom wild. */
    private const val CELL = 160

    /**
     * PKer populations tiled directly over the custom PvP-wilderness expanse ([PvpZones.mainWilderness]
     * — "the line" the user drew, south edge z3258 at the top of Lumbridge). We chop that whole
     * rectangle into [CELL]-tile cells and drop one colony per cell, so bots populate the ENTIRE custom
     * wild — leave Lumbridge in any direction into the red and you'll meet PKers, not just deep north.
     *
     * Correct-by-construction, no hand-drawn boxes to drift off the boundary:
     *  - `tier` is left null → every spawn is tiered by the custom wilderness level at its tile
     *    ([tierForWildLevel]): weak metal PKers at the z3258 frontier, elite NHers deep north.
     *  - the colony's muster filter keeps only walkable AND [PvpZones.isWilderness] tiles, so cells that
     *    land on a safe carve-out (Falador, GE, Varrock/Edgeville banks, Lumbridge core) or on water/
     *    cliffs simply yield 0 muster and idle. The boot log prints each cell's count.
     *
     * Density tuning: [CELL] size + the per-cell [BotZoneConfig.target] below. To exclude a spot
     * (e.g. Varrock streets), carve it out in [PvpZones] and the bots auto-follow.
     */
    val all: List<BotZoneConfig> = buildList {
        val red = PvpZones.mainWilderness
        var col = 0
        var x = red.bottomLeftX
        while (x <= red.topRightX) {
            var row = 0
            var z = red.bottomLeftY
            while (z <= red.topRightY) {
                val x2 = minOf(x + CELL - 1, red.topRightX)
                val z2 = minOf(z + CELL - 1, red.topRightY)
                add(
                    BotZoneConfig(
                        key = "wild_${col}_${row}",
                        displayName = "Wilderness $col-$row",
                        area = Area(x, z, x2, z2),
                        target = 1, // one PKer per cell; presence-gated so cost tracks active players
                        roamRadius = 12,
                        leashRadius = 22,
                        activationPadding = 20,
                    ),
                )
                row++
                z += CELL
            }
            col++
            x += CELL
        }

        // FALLEN VARROCK — the city fell and is now the loot hub where the rogues congregate, so it
        // gets a dedicated, denser colony ON TOP of the grid cells that cover it. Tier stays dynamic
        // (wild level ~15-33 across the city → mithril/rune-band PKers); the muster filter's
        // walkable + isWilderness check self-trims the banks/GE carve-outs and the buildings.
        add(
            BotZoneConfig(
                key = "fallen_varrock",
                displayName = "Fallen Varrock",
                area = Area(3155, 3376, 3300, 3520),
                target = 3,
                spacing = 5,
                roamRadius = 14,
                leashRadius = 28,
                activationPadding = 24,
            ),
        )
    }

    /** Even grid of candidate muster tiles across [area] at [spacing] (walkability filtered later). */
    fun boxStaging(area: Area, spacing: Int): List<Tile> {
        val step = spacing.coerceAtLeast(1)
        val cells = ArrayList<Tile>()
        var x = area.bottomLeftX
        while (x <= area.topRightX) {
            var z = area.bottomLeftY
            while (z <= area.topRightY) {
                cells += Tile(x, z, 0)
                z += step
            }
            x += step
        }
        return cells
    }
}
