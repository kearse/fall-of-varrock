package org.alter.plugins.content.raidzones

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropTable

/**
 * **Raid cities** — the Tarkov loop on top of the fallen world (see docs/raid-cities.md).
 *
 * A raid city is an overrun city turned open-world PvP ground: gear spawns at authored street
 * spots ([LootSpot]), a rare supply drop lands on a warned timer ([RaidCityConfig.rareTable]),
 * and the only safety inside the walls is the banks (auto-protected by
 * [org.alter.plugins.content.combat.BankSafezonePlugin]) plus any explicit [RaidCityConfig.extractions].
 * Death on the streets follows wilderness rules — the killer takes your loot — so every run is
 * a get-in, grab, extract gamble against the occupiers, the PK bots and everyone else raiding.
 *
 * Adding a city = one [RaidCityConfig] in [RaidCities.all] (mirrors
 * [org.alter.plugins.content.bots.BotZones] / `CityFrontiers` — data in, no new plugin code).
 * Varrock is deliberately NOT here: the demon war owns it; raids happen in the other cities.
 *
 * IMPORTANT: this object must never reference [org.alter.plugins.content.combat.PvpZones] —
 * PvpZones reads [RaidCities.all] (raid cities are PvP ground), and a reference back would be
 * a classload cycle.
 */

/** One authored ground-loot spawn point. Snapped to walkable at boot; rerolled from the
 *  district's table [respawnTicks] after it's taken. */
data class LootSpot(val x: Int, val z: Int, val respawnTicks: Int = 200)

/** A named quarter of a raid city: its own loot table + spawn spots, and the vocabulary the
 *  rare-drop warnings use ("a supply drop falls on Falador — the Old Market"). */
data class RaidDistrict(
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

/** How hard the city's occupiers hunt raiders (applied per-NPC by
 *  [org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin] to already-aggressive ids). */
data class RaidAggro(
    /** Tiles an occupier spots a raider from (world default is 4). */
    val radius: Int = 8,
    /** Ticks between target re-scans (world default is 8 — raid cities hunt faster). */
    val targetDelay: Int = 4,
    /** Cycles before an occupier loses interest (world default is 200). */
    val timer: Int = 600,
)

data class RaidCityConfig(
    val key: String,
    val display: String,
    /** The full-PvP raid box. TUNABLE. */
    val area: Area,
    /** Fixed wilderness level inside the box — sets the PvP attack range (and skull/teleport
     *  behaviour that keys off wild level). */
    val raidWildLevel: Int = 30,
    val districts: List<RaidDistrict>,
    /** Extra safe pockets beyond the banks (banks are auto-carved by BankSafezonePlugin). */
    val extractions: List<Area> = emptyList(),
    val aggro: RaidAggro = RaidAggro(),
    /** The warned supply-drop pool — the battlefield-makers. Rolled once per event. */
    val rareTable: DropTable,
)

object RaidCities {

    val all: List<RaidCityConfig> = listOf(FaladorRaid.config, AlKharidRaid.config)

    fun at(tile: Tile): RaidCityConfig? = all.firstOrNull { it.area.contains(tile) }

    fun at(x: Int, z: Int): RaidCityConfig? = all.firstOrNull { it.area.contains(x, z) }
}
