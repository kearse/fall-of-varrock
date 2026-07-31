package org.alter.plugins.content.raidzones

import org.alter.game.model.Area
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * **Fallen Falador** as a raid city — the knight city the Varrock exiles overrun
 * ([org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin.FALLEN_FALADOR]).
 *
 * Gear theme: the White Knights' armoury — white/steel gear on the streets, mithril in the
 * better quarters, adamant/rune as the rare finds. District boxes tile the whole city and are
 * keyed to the [org.alter.plugins.content.war.District] names so the war lore, `::districts`
 * and the raid warnings all speak the same map. All boxes/spots TUNABLE — verify with `::zone`.
 */
object FaladorRaid {

    /** Cross every metal with every piece into `item.<metal>_<piece>` (the
     *  [org.alter.plugins.content.war.CityFrontierPlugin] gear-table pattern). */
    private fun gear(metals: List<String>, pieces: List<String>, weight: Int = 1): List<DropEntry> =
        metals.flatMap { m -> pieces.map { p -> DropEntry("item.${m}_$p", weight = weight) } }

    private val KNIGHT_PIECES = listOf(
        "sword", "longsword", "scimitar", "warhammer", "battleaxe",
        "chainbody", "platebody", "full_helm", "platelegs", "kiteshield",
    )
    private val RARE_PIECES = listOf("scimitar", "longsword", "battleaxe", "platebody", "full_helm", "kiteshield")

    /** Shared consumables every quarter yields — the run-sustain layer. */
    private val SUPPLIES = listOf(
        DropEntry("item.swordfish", min = 1, max = 3, weight = 6),
        DropEntry("item.lobster", min = 1, max = 4, weight = 6),
        DropEntry("item.strength_potion4", weight = 3),
        DropEntry("item.attack_potion4", weight = 3),
        DropEntry("item.prayer_potion4", weight = 2),
        DropEntry("item.coins_995", min = 150, max = 600, weight = 8),
    )

    private fun districtTable(main: List<DropEntry>, rare: List<DropEntry>) = DropTable(
        main = main + SUPPLIES,
        rare = rare,
    )

    /** Rare finds shared city-wide: the adamant/rune staples that make a run worth the walk. */
    private val RARE_FINDS =
        gear(listOf("adamant"), RARE_PIECES).map { it.copy(oneInN = 40) } +
            gear(listOf("rune"), RARE_PIECES).map { it.copy(oneInN = 120) }

    // District themes: the market quarters lean supplies/coin, the castle quarters lean armoury.
    private val MARKET_MAIN =
        gear(listOf("steel"), KNIGHT_PIECES, weight = 3) +
            gear(listOf("white"), KNIGHT_PIECES, weight = 2) +
            gear(listOf("mithril"), KNIGHT_PIECES, weight = 1)
    private val ARMOURY_MAIN =
        gear(listOf("white"), KNIGHT_PIECES, weight = 3) +
            gear(listOf("mithril"), KNIGHT_PIECES, weight = 2) +
            gear(listOf("steel"), KNIGHT_PIECES, weight = 1)

    val config = RaidCityConfig(
        key = "falador",
        display = "Falador",
        // Same box as WorldSpawnsPlugin.FALLEN_FALADOR (and the old PvpZones carve-out).
        area = Area(2942, 3300, 3066, 3400),
        raidWildLevel = 30,
        districts = listOf(
            // West — the Old Market, around the west bank (the bank itself is auto-safe).
            RaidDistrict(
                key = "old-market", display = "the Old Market",
                area = Area(2942, 3350, 2985, 3400),
                table = districtTable(MARKET_MAIN, RARE_FINDS),
                spots = listOf(
                    LootSpot(2960, 3372), LootSpot(2966, 3381), LootSpot(2952, 3390),
                    LootSpot(2957, 3395), LootSpot(2972, 3390), LootSpot(2980, 3376),
                    LootSpot(2963, 3365), LootSpot(2984, 3390),
                ),
            ),
            // South-west — the Museum Quarter and the White Knights' Castle approaches.
            RaidDistrict(
                key = "museum-quarter", display = "the Museum Quarter",
                area = Area(2942, 3300, 2999, 3349),
                table = districtTable(ARMOURY_MAIN, RARE_FINDS),
                spots = listOf(
                    LootSpot(2977, 3341), LootSpot(2985, 3338), LootSpot(2994, 3331),
                    LootSpot(2966, 3335), LootSpot(2951, 3341), LootSpot(2946, 3327),
                    LootSpot(2972, 3322), LootSpot(2989, 3344),
                ),
            ),
            // North-east — the Slums around the park and the east bank (bank auto-safe).
            RaidDistrict(
                key = "slums", display = "the Slums",
                area = Area(2986, 3350, 3066, 3400),
                table = districtTable(MARKET_MAIN, RARE_FINDS),
                spots = listOf(
                    LootSpot(3030, 3370), LootSpot(3040, 3388), LootSpot(3021, 3392),
                    LootSpot(2995, 3382), LootSpot(3006, 3390), LootSpot(3050, 3375),
                    LootSpot(3060, 3390), LootSpot(3035, 3368), LootSpot(2990, 3372),
                ),
            ),
            // South-east — the East Quarter, the gate the marches enter by.
            RaidDistrict(
                key = "east-quarter", display = "the East Quarter",
                area = Area(3000, 3300, 3066, 3349),
                table = districtTable(ARMOURY_MAIN, RARE_FINDS),
                spots = listOf(
                    LootSpot(3045, 3336), LootSpot(3052, 3340), LootSpot(3060, 3330),
                    LootSpot(3030, 3340), LootSpot(3015, 3335), LootSpot(3040, 3320),
                    LootSpot(3010, 3345), LootSpot(3025, 3328),
                ),
            ),
        ),
        aggro = RaidAggro(radius = 8, targetDelay = 4, timer = 600),
        // The warned supply drop: knightly prestige loot. All announce — the whole point is
        // the server converging on it. Weights/items TUNABLE.
        rareTable = DropTable(
            main = listOf(
                DropEntry("item.rune_platebody", weight = 4, announce = true),
                DropEntry("item.rune_kiteshield", weight = 4, announce = true),
                DropEntry("item.rune_full_helm", weight = 4, announce = true),
                DropEntry("item.rune_scimitar", weight = 3, announce = true),
                DropEntry("item.rune_battleaxe", weight = 3, announce = true),
                DropEntry("item.dragon_longsword", weight = 2, announce = true),
                DropEntry("item.dragon_battleaxe", weight = 1, announce = true),
            ),
        ),
    )
}
