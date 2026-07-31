package org.alter.plugins.content.raidzones

import org.alter.game.model.Area
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * **Al Kharid** as a raid city — the desert gate town, cut off since the fall and picked over
 * by scorpion swarms and the desert gangs (its takeover pool lives in
 * [org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin]).
 *
 * Gear theme: the scimitar ladder (iron → rune), gems, silks and desert trade goods — a
 * dps/valuables city against Falador's armoury. The Lumbridge↔Al Kharid river crossing is
 * already a PK pocket in [org.alter.plugins.content.combat.PvpZones], so the walk in is the
 * gateway into raid territory. All boxes/spots TUNABLE — verify with `::zone`.
 */
object AlKharidRaid {

    private fun scimitars(metals: List<String>, weight: Int = 1): List<DropEntry> =
        metals.map { DropEntry("item.${it}_scimitar", weight = weight) }

    /** Desert trade goods — the sell-on-return valuables layer. */
    private val TRADE_GOODS = listOf(
        DropEntry("item.silk", min = 2, max = 8, weight = 6),
        DropEntry("item.uncut_sapphire", weight = 4),
        DropEntry("item.uncut_emerald", weight = 3),
        DropEntry("item.uncut_ruby", weight = 2),
        DropEntry("item.gold_bar", min = 1, max = 3, weight = 4),
        DropEntry("item.coins_995", min = 200, max = 800, weight = 8),
    )

    /** Run-sustain for the desert: kebabs are the street food, plus the potion staples. */
    private val SUPPLIES = listOf(
        DropEntry("item.kebab", min = 2, max = 5, weight = 6),
        DropEntry("item.swordfish", min = 1, max = 3, weight = 4),
        DropEntry("item.strength_potion4", weight = 3),
        DropEntry("item.antipoison4", weight = 3), // the scorpions poison
        DropEntry("item.energy_potion4", weight = 2),
    )

    private val RARE_FINDS = listOf(
        DropEntry("item.adamant_scimitar", oneInN = 30),
        DropEntry("item.rune_scimitar", oneInN = 90),
        DropEntry("item.sapphire", oneInN = 15),
        DropEntry("item.emerald", oneInN = 20),
        DropEntry("item.ruby", oneInN = 30),
        DropEntry("item.diamond", oneInN = 60),
        DropEntry("item.fire_battlestaff", oneInN = 60),
    )

    private fun districtTable(main: List<DropEntry>) = DropTable(main = main, rare = RARE_FINDS)

    private val BAZAAR_MAIN =
        scimitars(listOf("iron", "steel"), weight = 4) +
            scimitars(listOf("mithril"), weight = 2) +
            TRADE_GOODS + SUPPLIES
    private val MARKET_MAIN =
        TRADE_GOODS + TRADE_GOODS + // valuables-heavy
            scimitars(listOf("iron", "steel"), weight = 2) + SUPPLIES
    private val PALACE_MAIN =
        scimitars(listOf("steel"), weight = 3) +
            scimitars(listOf("mithril"), weight = 3) +
            listOf(
                DropEntry("item.staff_of_fire", weight = 2),
                DropEntry("item.chaos_rune", min = 5, max = 15, weight = 3),
                DropEntry("item.nature_rune", min = 4, max = 12, weight = 3),
                DropEntry("item.law_rune", min = 2, max = 6, weight = 2),
                DropEntry("item.death_rune", min = 2, max = 8, weight = 2),
            ) + SUPPLIES

    val config = RaidCityConfig(
        key = "al-kharid",
        display = "Al Kharid",
        // Gate at ~(3268,3227) down past the palace toward the Shantay road. TUNABLE.
        area = Area(3265, 3136, 3330, 3240),
        raidWildLevel = 25,
        districts = listOf(
            // North — the Gate Bazaar: the toll gate, tannery and crafting stalls.
            RaidDistrict(
                key = "gate-bazaar", display = "the Gate Bazaar",
                area = Area(3265, 3195, 3330, 3240),
                table = districtTable(BAZAAR_MAIN),
                spots = listOf(
                    LootSpot(3272, 3225), LootSpot(3280, 3230), LootSpot(3293, 3220),
                    LootSpot(3305, 3230), LootSpot(3288, 3210), LootSpot(3275, 3202),
                    LootSpot(3298, 3205), LootSpot(3315, 3225),
                ),
            ),
            // West — the Silk Market: the shop row around the bank (bank itself auto-safe).
            RaidDistrict(
                key = "silk-market", display = "the Silk Market",
                area = Area(3265, 3155, 3299, 3194),
                table = districtTable(MARKET_MAIN),
                spots = listOf(
                    LootSpot(3285, 3175), LootSpot(3290, 3183), LootSpot(3282, 3190),
                    LootSpot(3270, 3186), LootSpot(3295, 3170), LootSpot(3288, 3160),
                    LootSpot(3296, 3190), LootSpot(3292, 3158),
                ),
            ),
            // East + south — the Palace Quarter: the Emir's palace and the south road.
            RaidDistrict(
                key = "palace-quarter", display = "the Palace Quarter",
                area = Area(3300, 3136, 3330, 3194),
                table = districtTable(PALACE_MAIN),
                spots = listOf(
                    LootSpot(3315, 3170), LootSpot(3308, 3185), LootSpot(3320, 3160),
                    LootSpot(3312, 3145), LootSpot(3325, 3180), LootSpot(3305, 3190),
                    LootSpot(3318, 3152), LootSpot(3310, 3158),
                ),
            ),
        ),
        aggro = RaidAggro(radius = 8, targetDelay = 4, timer = 600),
        // The warned supply drop: the desert's prestige loot. All announce. TUNABLE.
        rareTable = DropTable(
            main = listOf(
                DropEntry("item.rune_scimitar", weight = 4, announce = true),
                DropEntry("item.diamond", min = 2, max = 4, weight = 3, announce = true),
                DropEntry("item.dragon_scimitar", weight = 2, announce = true),
                DropEntry("item.dragon_dagger", weight = 2, announce = true),
            ),
        ),
    )
}
