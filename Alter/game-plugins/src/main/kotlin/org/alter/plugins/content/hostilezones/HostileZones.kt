package org.alter.plugins.content.hostilezones

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef
import org.alter.plugins.content.bosses.DropTable

/**
 * **Hostile Zones** — the generic extraction framework (design authority 05 §8: *enter dangerous
 * area → gather loot → survive players/NPCs → extract*; docs/hostile-zones.md).
 *
 * A hostile zone is hostile ground turned open-world PvP loot grounds. Each one gets, from data
 * alone:
 *  - **PvP zoning** — the box is red at [HostileZoneConfig.wildLevel] (or the depth level when
 *    null) and optionally single-combat ([org.alter.plugins.content.combat.PvpZones] reads [all]);
 *  - **loot spots** ([LootDistrict] / [LootSpot], the [HostileLootPlugin] engine);
 *  - the warned **supply drop** ([SupplyDropPlugin], [HostileZoneConfig.rareTable]);
 *  - **occupiers** — NPC garrison lines ([OccupierLine]) mustered by [HostileOccupierPlugin] into
 *    one `war.HostileZone` while a player is near;
 *  - **raiders** — an optional pinned PK-bot colony ([RaiderColony], appended to `BotZones.all`);
 *  - **extraction points** ([ExtractionPoint], [HostileExtractionPlugin]) — the channelled exit
 *    that teleports the raider out and broadcasts the haul. Walking out of the box is still legal
 *    and silent; the points are the announced, bragging-rights exit.
 *
 * [HostileZoneKind] is the flavour + defaults for the places the design authority names:
 * Wilderness forts, the Edgeville frontier, Rogue strongholds, fallen settlements, optional Varrock
 * pockets. Adding a zone = one [HostileZoneConfig] in [HostileZoneCatalog]; nothing else changes.
 *
 * **This object and the catalog are PURE DATA.** They must never reference
 * [org.alter.plugins.content.combat.PvpZones] (it reads [all] lazily — a reference back is a
 * classload cycle), `BotZones` (whose object init reads [all]), `RogueKnights`, or the RSCM
 * (`getRSCM`): item / npc / object names stay strings and the consuming plugins resolve them.
 */

/** What kind of hostile ground a zone is — flavour text + defaults, never behaviour branches. */
enum class HostileZoneKind(
    val display: String,
    /** Default fixed wilderness level for the box; null = inherit the depth level. */
    val defaultWildLevel: Int?,
    val defaultSingle: Boolean,
    /** The entry banner's description of the ground. */
    val entryLine: String,
) {
    WILDERNESS_FORT("wilderness fort", null, false, "a fortified wilderness position"),
    FRONTIER("frontier", 10, false, "contested frontier ground"),
    ROGUE_STRONGHOLD("rogue stronghold", null, true, "rogue-held ground"),
    FALLEN_SETTLEMENT("fallen settlement", 30, false, "a fallen settlement"),
    VARROCK_POCKET("Varrock pocket", 20, true, "a lawless pocket of the fallen city"),
}

/** One authored ground-loot spawn point. Snapped to walkable at boot; rerolled from the
 *  district's table [respawnTicks] after it's taken. */
data class LootSpot(val x: Int, val z: Int, val respawnTicks: Int = 200)

/** A named quarter of a zone: its own loot table + spawn spots, and the vocabulary the supply-drop
 *  warnings use ("a supply drop falls on the Wild Bandit Stronghold — the Tents"). */
data class LootDistrict(
    val key: String,
    val display: String,
    /** Authored rectangle. TUNABLE — verify with `::zone` in-game. */
    val area: Area,
    /** The district's gear theme, rolled per loot-spot respawn. */
    val table: DropTable,
    val spots: List<LootSpot>,
) {
    /** Rough middle of the district (Area.centre is off-by-half — compute it ourselves). */
    val center: Tile get() = Tile(
        (area.bottomLeftX + area.topRightX) / 2,
        (area.bottomLeftY + area.topRightY) / 2,
    )
}

/**
 * A channelled exit: a dynamic object spawned at [tile] whose [option] starts the extraction
 * channel. [objectName] is an RSCM object key (`object.<name>`); the plugin verifies the verb
 * exists in the cache at boot and logs loudly (never throws) if it doesn't.
 */
data class ExtractionPoint(
    val tile: Tile,
    val objectName: String,
    val option: String,
    /** How the channel names it: "You begin to slip out through <label>…". */
    val label: String,
    val rot: Int = 0,
)

/**
 * One NPC garrison line of a zone. The npc id gets ONE global combat def + ONE death handler
 * server-wide, so it must not be used by any frontier / march-target line or world-spawn pool —
 * pick ids with zero rows in `data/cfg/spawns/npc_spawns.json` (the occupier plugin skips a line
 * whose id is already claimed, loudly).
 */
data class OccupierLine(
    val npcName: String,
    val count: Int,
    val combatDef: NpcCombatDef,
    /** Grid spacing for the muster points across [area] (or the zone box). */
    val spacing: Int = 4,
    /** Hand-placed muster tiles instead of the grid. */
    val explicitStaging: List<Tile>? = null,
    /** Sub-box to muster in; null = the whole zone. */
    val area: Area? = null,
    val walkRadius: Int = 5,
    /** 1v1 against players (the camp reads as a single-combat gauntlet, not a swarm). */
    val singleCombat: Boolean = true,
    val combatLevelOverride: Int? = null,
    /** Zone ticks before a dead slot refills. */
    val respawnDelay: Int = 20,
    val displayNoun: String = "rogue",
    /** Kill loot, spawned killer-owned on the death tile. Null = nothing. */
    val lootTable: DropTable? = null,
)

/** An optional pinned PK-bot colony over the zone (loadout weights as strings — resolved by BotZones). */
data class RaiderColony(
    val loadouts: List<Pair<String, Int>>,
    val target: Int = 2,
    val spacing: Int = 5,
    val roamRadius: Int = 8,
    val leashRadius: Int = 20,
    val activationPadding: Int = 24,
)

data class HostileZoneConfig(
    val key: String,
    val display: String,
    val kind: HostileZoneKind,
    /** The full-PvP box. TUNABLE. */
    val area: Area,
    /** Fixed wilderness level inside the box (sets the PvP attack range); null = inherit the
     *  depth level, so a box inside the deep wild is never softer than its surroundings. */
    val wildLevel: Int? = kind.defaultWildLevel,
    val singleCombat: Boolean = kind.defaultSingle,
    val districts: List<LootDistrict>,
    /** The warned supply-drop pool — the battlefield-makers. Rolled once per event. */
    val rareTable: DropTable,
    val extractionPoints: List<ExtractionPoint>,
    /** Where a successful extraction lands the raider. Must be OUTSIDE the box, on safe ground. */
    val exitTile: Tile,
    val occupiers: List<OccupierLine> = emptyList(),
    val raiders: RaiderColony? = null,
    val supplyDrop: Boolean = true,
    /** Boot-time switch: a disabled zone registers NOTHING (no red box, spots, objects, occupiers). */
    val enabled: Boolean = HostileZones.LIVE,
)

object HostileZones {

    /** Operator decision 2026-09-02: the first zone ships LIVE. Flip to park every catalog zone. */
    const val LIVE = true

    private val registry = LinkedHashMap<String, HostileZoneConfig>()

    /** ONE list instance, refreshed in place, so PvpZones' lazy reference to it stays valid. */
    private val enabledView = ArrayList<HostileZoneConfig>()

    /** Every registered zone, enabled or not (for `::hostile list`). */
    val configured: List<HostileZoneConfig> get() = registry.values.toList()

    /** The ENABLED zones — the only list any other system reads. */
    val all: List<HostileZoneConfig> = enabledView

    fun register(cfg: HostileZoneConfig) {
        require(!registry.containsKey(cfg.key)) { "Hostile zone '${cfg.key}' registered twice" }
        require(!cfg.area.contains(cfg.exitTile)) { "Hostile zone '${cfg.key}': exitTile must be outside the box" }
        registry[cfg.key] = cfg
        if (cfg.enabled) enabledView += cfg
    }

    fun at(tile: Tile): HostileZoneConfig? = enabledView.firstOrNull { it.area.contains(tile) }

    fun at(x: Int, z: Int): HostileZoneConfig? = enabledView.firstOrNull { it.area.contains(x, z) }

    fun byKey(key: String): HostileZoneConfig? = registry[key]

    init {
        HostileZoneCatalog.all.forEach(::register)
    }
}
