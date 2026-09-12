package org.alter.plugins.content.minigames.gotr

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey

/**
 * **Guardians of the Rift** — classic OSRS (March 2022), built from the wiki mechanics page and
 * the strategy guide (2026-09-03) against the rev-228 cache. See `docs/pvm/minigames-b-spec.md`
 * for the full spec and the FoV adjustments (imbuing at the temple's guardian portal instead of
 * a teleport to the real altar; rift guardians and combination runes are v2).
 */
object Gotr {

    const val REGION = 14484
    val TEMPLE = Area(3584, 9472, 3647, 9535)
    /** North of the barrier is the game; south is the lobby. */
    const val GAME_MIN_Z = 9484
    val LOBBY = Tile(3613, 9478, 0)
    val GAME_ENTRY = Tile(3613, 9487, 0)
    val GUARDIAN_TILE = Tile(3615, 9501, 0)
    val RIFT_SPAWN = Tile(3614, 9522, 0)

    const val RUNECRAFT_REQ = 27
    const val AGILITY_REQ_LARGE = 56

    // timings (ticks)
    const val LOBBY_WAIT = 100
    const val LOBBY_WAIT_AFTER_GAME = 17
    const val PREP_TICKS = 200
    const val FIRST_PORTALS = 267
    const val PORTAL_INTERVAL = 233
    const val PORTAL_OPEN = 167
    const val HUGE_PORTAL_INTERVAL = 200
    const val HUGE_PORTAL_OPEN = 42
    const val MAX_GAME_TICKS = 1500

    // scoring
    const val STONES_PER_PLAYER = 250
    const val ENERGY_PER_STONE = 2
    const val ENERGY_CAP_PER_TYPE = 1000
    const val MIN_ENERGY_FOR_XP = 300
    const val XP_PER_LEVEL = 45.0
    const val MINING_XP_CAP_FRAGMENTS = 250
    const val REPAIR_FRAGMENTS = 12
    const val REPAIR_ENERGY = 25
    const val PLACE_ENERGY = 2
    const val SHOCK_POWER = 60
    const val SHOCK_DAMAGE = 50
    const val LEECH_DRAIN_PERCENT = 1

    // objects
    const val BARRIER_KEY = "object.barrier_43700"
    const val BANK_CHEST_KEY = "object.bank_chest_43697"
    const val WORKBENCH_KEY = "object.workbench_43754"
    const val UNCHARGED_CELLS_KEY = "object.uncharged_cells_43732" // 43731 is a Take-only sibling; the temple's table is 43732 (Take-10 / Take-1)
    const val WEAK_CELLS_KEY = "object.weak_cells"
    /**
     * Slot states, all 1×1 shape-22 objects at the cell tiles: empty = "Inactive cell tile" (no
     * option — a cell is USED on it), broken = "Cell tile (broken)" (Repair), and the four
     * powered tiers "weak / medium / strong / overpowered cell tile" (Place-cell to strengthen).
     */
    const val INACTIVE_TILE_ID = 43739 // the "Inactive cell tile" variant WITH Place-cell (43738 has no option)
    const val BROKEN_TILE_ID = 43736
    const val INACTIVE_TILE_KEY = "object.inactive_cell_tile_43739"
    val TILE_IDS = mapOf(Cell.WEAK to 43740, Cell.MEDIUM to 43741, Cell.STRONG to 43742, Cell.OVERCHARGED to 43743)
    val SLOT_KEYS = listOf(INACTIVE_TILE_KEY, "object.cell_tile_broken", "object.weak_cell_tile", "object.medium_cell_tile", "object.strong_cell_tile", "object.overpowered_cell_tile")
    const val DEPOSIT_POOL_KEY = "object.deposit_pool"
    const val HUGE_PORTAL_ID = 43729
    val HUGE_PORTAL_TILE = Tile(3599, 9503, 0)
    val HUGE_PORTAL_LANDING = Tile(3592, 9502, 0)
    val RUBBLE_KEYS = listOf("object.rubble_43724", "object.rubble_43726")
    val RUBBLE_EAST_LANDING = Tile(3637, 9503, 0)
    val RUBBLE_WEST_LANDING = Tile(3632, 9503, 0)

    enum class Node(val keys: List<String>, val minFrags: Int, val maxFrags: Int, val huge: Boolean = false, val large: Boolean = false) {
        PARTS(listOf("object.guardian_parts", "object.guardian_parts_43716"), 1, 1),
        REMAINS(listOf("object.guardian_remains", "object.guardian_remains_43718"), 1, 2),
        LARGE(listOf("object.large_guardian_remains"), 2, 3, large = true),
        HUGE(listOf("object.huge_guardian_remains"), 3, 4, huge = true),
    }

    /** Barrier slots across the rift's approach (row z 9519, x 3607..3621 every other tile — 8 tiles). */
    val CELL_TILES: List<Tile> = (0 until 8).map { Tile(3607 + it * 2, 9519, 0) }

    // ───────────────────────────── altars ─────────────────────────────

    enum class Kind { ELEMENTAL, CATALYTIC }

    data class Altar(val name: String, val objKey: String, val rune: String, val level: Int, val xp: Double, val kind: Kind, val tile: Tile)

    val ALTARS = listOf(
        Altar("Air", "object.guardian_of_air", "item.air_rune", 1, 5.0, Kind.ELEMENTAL, Tile(3617, 9494, 0)),
        Altar("Water", "object.guardian_of_water", "item.water_rune", 5, 6.0, Kind.ELEMENTAL, Tile(3623, 9500, 0)),
        Altar("Earth", "object.guardian_of_earth", "item.earth_rune", 9, 6.5, Kind.ELEMENTAL, Tile(3623, 9505, 0)),
        Altar("Fire", "object.guardian_of_fire", "item.fire_rune", 14, 7.0, Kind.ELEMENTAL, Tile(3617, 9511, 0)),
        Altar("Mind", "object.guardian_of_mind", "item.mind_rune", 2, 5.5, Kind.CATALYTIC, Tile(3612, 9494, 0)),
        Altar("Body", "object.guardian_of_body", "item.body_rune", 20, 7.5, Kind.CATALYTIC, Tile(3608, 9496, 0)),
        Altar("Cosmic", "object.guardian_of_cosmic", "item.cosmic_rune", 27, 8.0, Kind.CATALYTIC, Tile(3621, 9496, 0)),
        Altar("Chaos", "object.guardian_of_chaos", "item.chaos_rune", 35, 8.5, Kind.CATALYTIC, Tile(3606, 9500, 0)),
        Altar("Nature", "object.guardian_of_nature", "item.nature_rune", 44, 9.0, Kind.CATALYTIC, Tile(3621, 9509, 0)),
        Altar("Law", "object.guardian_of_law", "item.law_rune", 54, 9.5, Kind.CATALYTIC, Tile(3608, 9509, 0)),
        Altar("Death", "object.guardian_of_death", "item.death_rune", 65, 10.0, Kind.CATALYTIC, Tile(3606, 9505, 0)),
        Altar("Blood", "object.guardian_of_blood", "item.blood_rune", 77, 23.8, Kind.CATALYTIC, Tile(3612, 9511, 0)),
    )

    /** Cell tier by altar level (weak ≤5, medium ≤14, strong ≤44, overcharged 54+). */
    enum class Cell(val item: String, val hp: Int, val strengthen: Int) {
        WEAK("item.weak_cell", 40, 0),
        MEDIUM("item.medium_cell", 70, 7),
        STRONG("item.strong_cell", 110, 13),
        OVERCHARGED("item.overcharged_cell", 160, 22),
    }

    fun cellFor(altarLevel: Int): Cell = when {
        altarLevel <= 5 -> Cell.WEAK
        altarLevel <= 14 -> Cell.MEDIUM
        altarLevel <= 44 -> Cell.STRONG
        else -> Cell.OVERCHARGED
    }

    // ───────────────────────────── items / npcs ─────────────────────────────

    const val FRAGMENTS = "item.guardian_fragments"
    const val ESSENCE = "item.guardian_essence"
    const val UNCHARGED_CELL = "item.uncharged_cell"
    const val ELEMENTAL_STONE = "item.elemental_guardian_stone"
    const val CATALYTIC_STONE = "item.catalytic_guardian_stone"
    const val PEARLS = "item.abyssal_pearls"
    val GAME_ITEMS = listOf(FRAGMENTS, ESSENCE, UNCHARGED_CELL, ELEMENTAL_STONE, CATALYTIC_STONE) + Cell.values().map { it.item }

    const val GREAT_GUARDIAN_KEY = "npc.the_great_guardian"
    const val REWARDS_GUARDIAN_KEY = "npc.rewards_guardian"
    val REWARDS_GUARDIAN_TILE = Tile(3609, 9476, 0)
    const val FELIX_KEY = "npc.apprentice_felix"
    val FELIX_TILE = Tile(3617, 9477, 0)

    /** The attackable Abyss models (the GotR-specific ids carry no Attack option in 228). */
    const val LEECH_KEY = "npc.abyssal_leech"
    const val WALKER_KEY = "npc.abyssal_walker"
    const val GUARDIAN_KEY = "npc.abyssal_guardian"

    // ───────────────────────────── rewards (wiki, weights /140) ─────────────────────────────

    data class Reward(val key: String, val min: Int, val max: Int, val weight: Int)

    val REWARD_TABLE = listOf(
        Reward(PEARLS, 14, 16, 18),
        Reward("item.air_rune", 400, 500, 4), Reward("item.water_rune", 400, 500, 4),
        Reward("item.earth_rune", 400, 500, 4), Reward("item.fire_rune", 400, 500, 4),
        Reward("item.mind_rune", 250, 400, 4), Reward("item.body_rune", 80, 150, 4),
        Reward("item.chaos_rune", 61, 150, 10), Reward("item.cosmic_rune", 20, 30, 10),
        Reward("item.nature_rune", 28, 150, 10), Reward("item.law_rune", 5, 120, 10),
        Reward("item.death_rune", 5, 120, 10), Reward("item.blood_rune", 5, 120, 10),
        Reward("item.intricate_pouch", 1, 1, 5),
        Reward("item.abyssal_ashes", 1, 1, 1),
        Reward("item.needle", 1, 1, 1),
        Reward("POUCH", 1, 1, 15),
        Reward("TALISMAN", 1, 1, 16),
    )
    val POUCHES = listOf("item.small_pouch", "item.medium_pouch", "item.large_pouch", "item.giant_pouch")
    val TALISMANS = listOf(
        "item.air_talisman_noted" to 48, "item.water_talisman_noted" to 48, "item.earth_talisman_noted" to 48, "item.fire_talisman_noted" to 48,
        "item.mind_talisman_noted" to 64, "item.body_talisman_noted" to 64, "item.chaos_talisman_noted" to 64,
        "item.cosmic_talisman_noted" to 64, "item.nature_talisman_noted" to 64, "item.elemental_talisman" to 16,
    )

    data class Rare(val key: String, val oneIn: Int, val once: Boolean = false)

    val RARES = listOf(
        Rare("item.atlaxs_diary", 20, once = true),
        Rare("item.catalytic_talisman", 200),
        Rare("item.abyssal_needle", 300, once = true),
        Rare("item.abyssal_lantern", 700),
        Rare("item.abyssal_red_dye", 1200),
        Rare("item.abyssal_green_dye", 1200),
        Rare("item.abyssal_blue_dye", 1200),
        Rare("item.abyssal_protector", 4000),
    )
    val LOGGED = setOf("item.atlaxs_diary", "item.abyssal_needle", "item.abyssal_lantern", "item.abyssal_red_dye", "item.abyssal_green_dye", "item.abyssal_blue_dye", "item.abyssal_protector", "item.hat_of_the_eye", "item.robe_top_of_the_eye", "item.robe_bottoms_of_the_eye", "item.boots_of_the_eye")

    const val PEARLS_PER_SEARCH = 25

    /** Temple Supplies (Apprentice Felix) — behind [SHOP_ENABLED] until Team 2 rules on the currency shelf. */
    const val SHOP_ENABLED = true
    data class Ware(val name: String, val key: String, val pearls: Int)
    val SHOP = listOf(
        Ware("Hat of the eye", "item.hat_of_the_eye", 400),
        Ware("Robe top of the eye", "item.robe_top_of_the_eye", 350),
        Ware("Robe bottoms of the eye", "item.robe_bottoms_of_the_eye", 350),
        Ware("Boots of the eye", "item.boots_of_the_eye", 250),
        Ware("Abyssal lantern", "item.abyssal_lantern", 1500),
        Ware("Abyssal needle", "item.abyssal_needle", 750),
        Ware("Catalytic talisman", "item.catalytic_talisman", 100),
        Ware("Elemental talisman", "item.elemental_talisman", 50),
    )

    val ELEMENTAL_POINTS = AttributeKey<Int>(persistenceKey = "gotr_elemental")
    val CATALYTIC_POINTS = AttributeKey<Int>(persistenceKey = "gotr_catalytic")
    val GAMES = AttributeKey<Int>(persistenceKey = "gotr_games")
    val ONCE_REWARDS = AttributeKey<String>(persistenceKey = "gotr_once")
}
