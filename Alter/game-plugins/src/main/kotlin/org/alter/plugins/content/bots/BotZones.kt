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
 * (mirrors [org.alter.plugins.content.war.CityFrontiers]).
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
    /**
     * SAFE-GROUND camp: when true the colony musters on walkable tiles even where
     * [PvpZones.isWilderness] is false, and every spawned bot gets [PkBot.ambushEverywhere] so it
     * aggros on its safe-tile post (the goblin-camp ambusher pattern, zone-ified). Used by the
     * organized rogue-knight camps outside the red (the Bandit Hideout west of Lumbridge) —
     * players who die there get the normal safe-zone reclaim pile, so the starter camp is where
     * you LEARN to fight PKers cheaply. Requires a pinned [tier] (never depth-roll a safe camp).
     */
    val allowSafe: Boolean = false,
) {
    init { require(!allowSafe || tier != null) { "allowSafe zone '$key' must pin a tier (wild level is 0 on safe ground)" } }
}

object BotZones {

    // Depth ladder (see [tierForWildLevel]). Wild 1–10 is a low-level METAL-armour ladder sized for the
    // new (cb 1–20) players who leave Lumbridge; past level 10 it becomes a random spread of authentic
    // PK sets — budget → mid → high (maxers) → elite meta — with mixed fight styles. No bot uses a
    // Voidwaker.

    // Wild 1–10: metal-armour fodder, weighted so the very edge is mostly bronze/iron and dragon is rare.
    private val T_METAL = BotTier(listOf(
        "bronze_pker" to 5, "iron_pker" to 5, "steel_pker" to 4, "black_pker" to 4,
        "mithril_pker" to 3, "adamant_pker" to 2, "rune_pker" to 2, "dragon_pker" to 1,
    ))
    // Wild 11–20: budget PK sets — a low-def pure, a 45-def whip zerker, a rune-clad main.
    private val T_BUDGET = BotTier(listOf("budget_pure" to 2, "budget_zerker" to 2, "budget_main" to 1))
    // Wild 21–30: mid mains — the classic whip hybrid, a mid ranger, a mid freezer, a budget DHer,
    // plus the void ranger and dark-bow sniper gimmick builds for variety.
    private val T_MID = BotTier(listOf(
        "classic_hybrid" to 2, "range_mid" to 1, "mage_mid" to 1, "dharok_mid" to 1,
        "void_ranger" to 1, "dbow_sniper" to 1,
    ))
    // Wild 31–40: high / maxer tier — Bandos maxers (AGS + tentacle/claws), blood mage, claws brid,
    // plus the Statius bruiser, Morrigan ballista and revenant-weapon raider wildy builds.
    private val T_HIGH = BotTier(listOf(
        "max_main" to 2, "max_tent" to 1, "claws_brid" to 1, "ancient_mage" to 1,
        "statius_bruiser" to 1, "morrigan_skirmisher" to 1, "rev_raider" to 1,
    ))
    // Wild 41+: elite meta — tribrid NHer, kodai freezer, masori ranger, Dharok DHer, plus the two
    // elite Ancient Warrior builds (Vesta duelist / Zuriel storm mage).
    private val T_ELITE = BotTier(listOf(
        "elite_nh" to 2, "mage_elite" to 1, "range_elite" to 1, "dharok_dher" to 1, "claws_brid" to 1,
        "vesta_duelist" to 1, "zuriel_mage" to 1,
    ))

    // ===================== ORGANIZED ROGUE-KNIGHT CAMPS (rogue-knight ladder §docs) =====================
    // Fixed-tier ambient populations at each camp on the ladder, so every camp reads as an organized
    // warband of a recognisable level band — the named knights (bots/knights/) stand among them.

    /** Bandit Hideout (SAFE, west of Lumbridge): the Bronze Knights — bronze/iron/steel fodder for
     *  brand-new players learning to fight PKers with a reclaimable death. */
    private val T_BANDIT_HIDEOUT = BotTier(listOf(
        "bronze_pker" to 4, "iron_pker" to 3, "steel_pker" to 2,
    ))

    /** Draynor outskirts (SAFE): the road rabble on the coast road south of the village — a
     *  half-step up from the hideout on the hunt's safe road west. */
    private val T_DRAYNOR = BotTier(listOf(
        "iron_pker" to 3, "steel_pker" to 4, "black_pker" to 2,
    ))

    /** South of Port Sarim (SAFE, the jail road): the last safe rung before the docks warband. */
    private val T_SARIM_ROAD = BotTier(listOf(
        "black_pker" to 4, "mithril_pker" to 3, "adamant_pker" to 1,
    ))

    /** Port Sarim (SAFE, the docks): the port raiders' warband — a step up from the hideout,
     *  matched to the camp's knights (black metal through the budget-pure bracket). */
    private val T_PORT_SARIM = BotTier(listOf(
        "black_pker" to 4, "mithril_pker" to 3, "adamant_pker" to 2, "budget_pure" to 1,
    ))

    /** Wild Bandit Camp (deep wild, multi): the high-tier warband — maxers and wildy-set builds. */
    private val T_WILD_BANDIT_CAMP = BotTier(listOf(
        "max_main" to 2, "max_tent" to 2, "statius_bruiser" to 2, "morrigan_skirmisher" to 2, "rev_raider" to 2,
    ))

    /** The Rogue Commander's Redoubt (deep wild 45+): the elite guard around the top of the ladder. */
    private val T_REDOUBT = BotTier(listOf(
        "elite_nh" to 2, "mage_elite" to 1, "range_elite" to 1, "vesta_duelist" to 2, "zuriel_mage" to 2,
    ))

    /**
     * ROGUE tier — the capped pool for [Fallen Varrock][fallen_varrock], the scripted first-run
     * zone of the Act II "Rogue Problem" quest. New Squires are steered here straight off War-Prep,
     * so its "Rogue Knights" are deliberately beatable: metal-armour fodder up to adamant plus a
     * couple of budget PK sets — NO rune/dragon metal, NO mid/high/elite meta, none of them pray.
     * Fixing the tier here (instead of letting the city's wild depth roll mithril→rune→elite) is
     * what keeps the quest survivable while the rest of the wilderness stays scary by depth.
     */
    private val T_ROGUE = BotTier(listOf(
        "bronze_pker" to 4, "iron_pker" to 4, "steel_pker" to 4, "black_pker" to 3,
        "mithril_pker" to 3, "adamant_pker" to 2, "budget_pure" to 2, "budget_zerker" to 1,
    ))

    /**
     * Loadout tier for a given custom wilderness level ([PvpZones.wildernessLevel], depth north from
     * the wild's south edge). The single source of truth for "tier PKers by depth": a bot's danger is
     * decided by how deep it spawned. Wild 1–10 is a metal-armour ladder for new players; past 10 the
     * gear escalates budget → mid → high → elite as you push deeper.
     */
    fun tierForWildLevel(level: Int): BotTier = when {
        level <= 10 -> T_METAL     // frontier — low-level metal-armour fodder for new players
        level <= 20 -> T_BUDGET    // budget PK sets (pure / zerker / rune main)
        level <= 30 -> T_MID       // mid mains (whip hybrid / range / mage / DHer)
        level <= 40 -> T_HIGH      // high / maxers (Bandos AGS/tent/claws, blood mage)
        else -> T_ELITE            // deepest wild — the elite meta NHers
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

        // ============== ORGANIZED ROGUE-KNIGHT CAMPS (ladder camps 1/2/4/5; camp 3 is Fallen ==============
        // Varrock below). Tiles are TUNE — verify with ::zone in-game. The two safe camps ride the
        // allowSafe/ambushEverywhere path (reclaimable deaths — the learning camps); the two deep
        // camps are ordinary wilderness zones with pinned high/elite pools, denser than the grid.

        // Camp 1 — the BANDIT HIDEOUT west of Lumbridge (safe ground, south of the red's edge).
        add(
            BotZoneConfig(
                key = "bandit_hideout",
                displayName = "Bandit Hideout",
                area = Area(3095, 3215, 3125, 3245),
                tier = T_BANDIT_HIDEOUT,
                target = 3,
                spacing = 4,
                roamRadius = 6,
                leashRadius = 16,
                activationPadding = 24,
                allowSafe = true,
            ),
        )
        // THE SAFE ROAD WEST — ladder camps between the hideout and Port Sarim (RogueKnights.DRAYNOR
        // hosts Sir Oswin, SARIM_ROAD hosts Sir Malrik), so the Sergeant's assignments march the
        // whole road. The Act II hunt counts rogue-family kills anywhere, so a fresh Squire can also
        // fill the tally on reclaim-death ground here instead of braving Fallen Varrock's wilderness.
        add(
            BotZoneConfig(
                key = "draynor",
                displayName = "Draynor rogues",
                // Southern outskirts + coast road; top edge stays south of the bank (z3240+). TUNE.
                area = Area(3068, 3210, 3094, 3239),
                tier = T_DRAYNOR,
                target = 3,
                spacing = 4,
                roamRadius = 6,
                leashRadius = 16,
                activationPadding = 24,
                allowSafe = true,
            ),
        )
        add(
            BotZoneConfig(
                key = "sarim_road",
                displayName = "Port Sarim south road",
                // The jail + the road south toward Mudskipper Point. TUNE.
                area = Area(3000, 3150, 3030, 3180),
                tier = T_SARIM_ROAD,
                target = 3,
                spacing = 4,
                roamRadius = 6,
                leashRadius = 16,
                activationPadding = 24,
                allowSafe = true,
            ),
        )
        // Camp 2 — PORT SARIM (safe ground, the docks). The camp's ROGUE-HUNTING population: a
        // small warband of tier rogues around the named ladder knights, feeding the camp-clearance
        // gate exactly like every other camp. Deliberately separate from the SIEGE battle scene
        // (`areas/portsarim/PortSiegePlugin` — Knights of Lumbridge vs Rogue Raider NPC waves):
        // raids are their own triggered content and never mix with the Sergeant's hunt.
        add(
            BotZoneConfig(
                key = "port_sarim",
                displayName = "Port Sarim rogues",
                area = Area(3026, 3187, 3056, 3217),
                tier = T_PORT_SARIM,
                target = 3,
                spacing = 4,
                roamRadius = 6,
                leashRadius = 16,
                activationPadding = 24,
                allowSafe = true,
            ),
        )

        // Camp 4 — the WILD BANDIT CAMP (deep wilderness, multi): the high-tier warband.
        add(
            BotZoneConfig(
                key = "wild_bandit_camp",
                displayName = "Wild Bandit Camp",
                area = Area(3020, 3675, 3055, 3705),
                tier = T_WILD_BANDIT_CAMP,
                target = 3,
                spacing = 5,
                roamRadius = 8,
                leashRadius = 20,
                activationPadding = 24,
            ),
        )
        // Camp 5 — the ROGUE COMMANDER'S REDOUBT (deepest wild): the elite guard.
        add(
            BotZoneConfig(
                key = "rogue_redoubt",
                displayName = "Rogue Commander's Redoubt",
                area = Area(2995, 3865, 3035, 3900),
                tier = T_REDOUBT,
                target = 3,
                spacing = 5,
                roamRadius = 8,
                leashRadius = 20,
                activationPadding = 24,
            ),
        )

        // FALLEN VARROCK — the city fell and is now the loot hub where the rogues congregate, so it
        // gets a dedicated, denser colony ON TOP of the grid cells that cover it. This is also the
        // scripted hunting ground for the Act II "Rogue Problem" quest, so its rogues are PINNED to
        // the capped [T_ROGUE] pool (beatable metal/budget PKers, no rune/elite) instead of rolling
        // the city's wild depth up to elite — players reported fresh Squires being farmed by geared
        // Rogue Knights here. The chase leash/roam are also tightened so a rogue can't drag a quester
        // clear across the shallow streets (the "coming in too far" complaint). The muster filter's
        // walkable + isWilderness check still self-trims the banks/GE carve-outs and the buildings.
        add(
            BotZoneConfig(
                key = "fallen_varrock",
                displayName = "Fallen Varrock",
                area = Area(3155, 3376, 3300, 3520),
                tier = T_ROGUE,     // capped: no rune/elite geared rogues in the quest zone
                target = 3,
                spacing = 5,
                roamRadius = 8,     // was 14 — keep rogues near their post
                leashRadius = 14,   // was 28 — stop dragging questers across the city
                activationPadding = 24,
            ),
        )
        // (Extraction zones — `raidzones/RaidCities` — used to add a pinned "raiders" colony per zone
        // here. The framework is dormant with zero locations; re-add the loop when one is chosen.)
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
