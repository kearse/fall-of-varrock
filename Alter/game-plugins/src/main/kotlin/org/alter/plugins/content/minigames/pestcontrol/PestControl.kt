package org.alter.plugins.content.minigames.pestcontrol

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey

/**
 * **Pest Control** — classic OSRS, ported from the Kronos donor (the pestcontrol activity package).
 * Three landers at the Void Knights' Outpost (region 10537) dispatch games into a private
 * copy of the battleground (region 10536): four shielded portals, a Void Knight to defend,
 * pests streaming from every unshielded portal, a twenty-minute clock. Destroy all four
 * portals with enough activity and the Order pays Pest Control points, spent with the
 * Void Knight at the outpost for Void Knight equipment.
 *
 * FoV tuning: the party minimum is one (low population — the constant is right here),
 * departures every 60 s while someone waits, no coin reward (FoV rule), Pest Control points
 * 10 / 15 / 20 per win (the donor paid 14 / 24 / 30; OSRS 2 / 3 / 4 — Team 2 review).
 */
object PestControl {

    const val MIN_PARTY = 1
    const val DEPARTURE_TICKS = 100
    const val LIFESPAN_TICKS = 2000
    const val ACTIVITY_NEEDED = 50

    val ARENA = Area(2624, 2560, 2687, 2623)
    val KNIGHT_TILE = Tile(2656, 2592, 0)
    val SQUIRE_TILE = Tile(2660, 2608, 0)
    val SPAWN_BASE = Tile(2656, 2610, 0)

    const val SQUIRE_KEY = "npc.squire_2949"
    const val SHOP_KNIGHT_KEY = "npc.void_knight_1756"
    val SHOP_KNIGHT_TILE = Tile(2661, 2651, 0)

    enum class Lander(
        val title: String,
        val gangplank: String,
        val ladder: String,
        val combatReq: Int,
        val portalHp: Int,
        val points: Int,
        val knightKey: String,
        val exit: Tile,
        val pests: List<String>,
    ) {
        NOVICE(
            "Novice", "object.gangplank_14315", "object.ladder_14314", 40, 200, 10, "npc.void_knight_2950", Tile(2657, 2639, 0),
            listOf("npc.splatter", "npc.splatter_1690", "npc.torcher", "npc.torcher_1715", "npc.torcher_1716", "npc.torcher_1717", "npc.spinner", "npc.spinner_1710", "npc.defiler", "npc.defiler_1725", "npc.defiler_1726", "npc.defiler_1727", "npc.ravager", "npc.ravager_1705", "npc.brawler", "npc.shifter", "npc.shifter_1695", "npc.shifter_1696", "npc.shifter_1697"),
        ),
        INTERMEDIATE(
            "Intermediate", "object.gangplank_25631", "object.ladder_25629", 70, 250, 15, "npc.void_knight_2951", Tile(2644, 2644, 0),
            listOf("npc.splatter_1691", "npc.splatter_1692", "npc.torcher_1718", "npc.torcher_1719", "npc.torcher_1720", "npc.torcher_1721", "npc.spinner_1711", "npc.spinner_1713", "npc.defiler_1728", "npc.defiler_1729", "npc.ravager_1705", "npc.ravager_1706", "npc.brawler_1735", "npc.shifter_1696", "npc.shifter_1697", "npc.shifter_1698", "npc.shifter_1699"),
        ),
        VETERAN(
            "Veteran", "object.gangplank_25632", "object.ladder_25630", 100, 250, 20, "npc.void_knight_2952", Tile(2638, 2653, 0),
            listOf("npc.splatter_1692", "npc.splatter_1693", "npc.torcher_1720", "npc.torcher_1721", "npc.torcher_1723", "npc.spinner_1712", "npc.spinner_1713", "npc.defiler_1730", "npc.defiler_1731", "npc.defiler_1732", "npc.defiler_1733", "npc.ravager_1707", "npc.ravager_1708", "npc.brawler_1736", "npc.brawler_1737", "npc.shifter_1700", "npc.shifter_1701", "npc.shifter_1702", "npc.shifter_1703"),
        ),
    }

    data class PortalDef(val name: String, val shielded: String, val open: String, val tile: Tile)

    val PORTALS = listOf(
        PortalDef("Purple", "npc.portal_1743", "npc.portal_1747", Tile(2628, 2591, 0)),
        PortalDef("Blue", "npc.portal_1744", "npc.portal_1748", Tile(2680, 2588, 0)),
        PortalDef("Yellow", "npc.portal_1745", "npc.portal_1749", Tile(2669, 2570, 0)),
        PortalDef("Red", "npc.portal_1746", "npc.portal_1750", Tile(2645, 2569, 0)),
    )

    /** Every pest id (1689–1738): handler-owned, so no ambient rows and no generic loot. */
    val ALL_PESTS: List<String> = Lander.values().flatMap { it.pests }.distinct()
    val ALL_SPLATTERS = ALL_PESTS.filter { it.startsWith("npc.splatter") }
    val ALL_SPINNERS = ALL_PESTS.filter { it.startsWith("npc.spinner") }
    val ALL_SHIFTERS = ALL_PESTS.filter { it.startsWith("npc.shifter") }
    val ALL_TORCHERS = ALL_PESTS.filter { it.startsWith("npc.torcher") }
    val ALL_DEFILERS = ALL_PESTS.filter { it.startsWith("npc.defiler") }

    data class Reward(val name: String, val key: String, val cost: Int)

    val REWARDS = listOf(
        Reward("Void knight mace", "item.void_knight_mace", 250),
        Reward("Void knight top", "item.void_knight_top", 250),
        Reward("Void knight robe", "item.void_knight_robe", 250),
        Reward("Void knight gloves", "item.void_knight_gloves", 150),
        Reward("Void mage helm", "item.void_mage_helm", 200),
        Reward("Void ranger helm", "item.void_ranger_helm", 200),
        Reward("Void melee helm", "item.void_melee_helm", 200),
        Reward("Elite void top", "item.elite_void_top", 200),
        Reward("Elite void robe", "item.elite_void_robe", 200),
    )
    /** Elite upgrades consume the plain piece (OSRS: 200 points + the base item). */
    val ELITE_BASE = mapOf("item.elite_void_top" to "item.void_knight_top", "item.elite_void_robe" to "item.void_knight_robe")

    /** "Pest Control points" in code and player text — never "commendations" (that word is the war team's Commendation currency). */
    val PC_POINTS = AttributeKey<Int>(persistenceKey = "pest_points")
    val NOVICE_WINS = AttributeKey<Int>(persistenceKey = "pest_novice_wins")
    val INTERMEDIATE_WINS = AttributeKey<Int>(persistenceKey = "pest_intermediate_wins")
    val VETERAN_WINS = AttributeKey<Int>(persistenceKey = "pest_veteran_wins")
    val ACTIVITY = AttributeKey<Int>()
}
