package org.alter.plugins.content.minigames.wintertodt

import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import dev.openrune.cache.CacheManager

/**
 * **Wintertodt** — classic OSRS, ported from the Kronos donor (the wintertodt activity package)
 * and squared against the wiki. Region 6462 is a *bridge-flagged* map in rev 228: its
 * plane-1 objects register at plane 0, so every coordinate here is the donor's plane-0
 * coordinate.
 *
 * Rules kept: 50 Firemaking through the Doors of Dinh; four braziers with a pyromancer
 * each; chop bruma roots (Woodcutting) → optionally fletch to kindling (Fletching) →
 * feed a burning brazier (Firemaking: root 10 pts, kindling 25); light 25 / repair 25
 * (Construction); heal a pyromancer with a rejuvenation potion (herb + concoction) 30;
 * the Wintertodt's energy falls 5 per lit brazier per two ticks and creeps back when none
 * burn; cold damage, snowfall area attacks, brazier blow-outs and pyromancer freezes scale
 * with warm clothing and lit braziers exactly per the donor formulas. 500 points = a
 * supply crate (plus a proportional chance of one more); Firemaking XP 100 × level at the
 * end. Points reset when you leave.
 */
object Wintertodt {

    const val REGION = 6462
    val AREA = Area(1600, 3968, 1663, 4031)
    /** North of this line the cold bites (the arena proper). */
    const val ARENA_MIN_Z = 3988

    val OUTSIDE = Tile(1630, 3958, 0)
    val INSIDE = Tile(1630, 3982, 0)
    val CENTRE = Tile(1630, 4007, 0)
    val STORM_TILE = Tile(1627, 4004, 0)

    const val FIREMAKING_REQ = 50
    const val MAX_ENERGY = 3500
    const val REST_TICKS = 100
    const val CRATE_POINTS = 500

    // objects
    const val EMPTY_BRAZIER = "object.brazier_29312"
    const val BROKEN_BRAZIER = "object.brazier_29313"
    const val BURNING_BRAZIER = "object.burning_brazier_29314"
    const val EMPTY_ID = 29312
    const val BROKEN_ID = 29313
    const val BURNING_ID = 29314
    const val ACTIVE_STORM = 29308
    const val INACTIVE_STORM = 29309
    const val SNOW_EFFECT = 26690
    const val SNOW_CENTRE = 29325
    const val SNOW_RING = 29324

    // npcs
    const val PYROMANCER_KEY = "npc.pyromancer"
    const val INCAPACITATED_KEY = "npc.incapacitated_pyromancer"
    const val PYROMANCER_ID = 7371
    const val INCAPACITATED_ID = 7372
    const val FLAME_ID = 7373
    const val IGNISIA_KEY = "npc.ignisia"
    val IGNISIA_TILE = Tile(1633, 3959, 0)
    const val PYRO_MAX_HP = 30

    // items
    const val ROOT = "item.bruma_root"
    const val KINDLING = "item.bruma_kindling"
    const val UNF_POTION = "item.rejuvenation_potion_unf"
    const val HERB = "item.bruma_herb"
    val POTIONS = listOf("item.rejuvenation_potion_4", "item.rejuvenation_potion_3", "item.rejuvenation_potion_2", "item.rejuvenation_potion_1")
    const val SUPPLY_CRATE = "item.supply_crate"
    const val VIAL = "item.vial"
    val GAME_ITEMS = listOf(ROOT, KINDLING, UNF_POTION, HERB) + POTIONS

    data class BrazierSpot(val tile: Tile, val pyroTile: Tile, val flameDx: Int, val flameDz: Int, val name: String)

    val BRAZIERS = listOf(
        BrazierSpot(Tile(1620, 3997, 0), Tile(1619, 3996, 0), 0, 0, "south-west"),
        BrazierSpot(Tile(1620, 4015, 0), Tile(1619, 4018, 0), 0, 2, "north-west"),
        BrazierSpot(Tile(1638, 4015, 0), Tile(1641, 4018, 0), 2, 2, "north-east"),
        BrazierSpot(Tile(1638, 3997, 0), Tile(1641, 3996, 0), 2, 0, "south-east"),
    )

    val PYRO_DOWN_TEXT = listOf("My flame burns low.", "Mummy!", "I think I'm dying.", "We are doomed.", "Ugh, help me!")

    // points (donor / wiki)
    const val PTS_LIGHT = 25
    const val PTS_FIX = 25
    const val PTS_ROOT = 10
    const val PTS_KINDLING = 25
    const val PTS_HEAL = 30

    val POINTS = AttributeKey<Int>()
    val LIFETIME_POINTS = AttributeKey<Int>(persistenceKey = "wintertodt_points")
    val SUBDUED = AttributeKey<Int>(persistenceKey = "wintertodt_subdued")
    val HIGHSCORE = AttributeKey<Int>(persistenceKey = "wintertodt_highscore")

    // ───────────────────────────── warmth & damage (donor formulas) ─────────────────────────────

    private val WARM_KEYWORDS = listOf(
        "pyromancer", "warm gloves", "bruma torch", "tome of fire", "fire cape", "infernal cape", "santa",
        "bunny", "firemaking cape", "staff of fire", "fire battlestaff", "mystic fire staff", "clue hunter",
        "lava dragon", "obsidian cape", "lit bug lantern", "infernal max", "fire max", "yak-hide", "polar camo",
        "lumberjack", "chicken", "bomber", "red boater", "red cape", "red beret", "hunter", "gnome scarf",
    )

    fun warmItemsWorn(p: Player): Int {
        var n = 0
        for (i in 0 until p.equipment.capacity) {
            val item = p.equipment[i] ?: continue
            val name = CacheManager.getItem(item.id).name?.lowercase() ?: continue
            if (WARM_KEYWORDS.any { name.contains(it) }) n++
        }
        return minOf(4, n)
    }

    fun coldDamage(p: Player, lit: Int): Int {
        val hp = p.getSkills().getBaseLevel(Skills.HITPOINTS) + 1
        val fm = maxOf(1, p.getSkills().getBaseLevel(Skills.FIREMAKING))
        return ((16.0 - warmItemsWorn(p) - 2 * minOf(3, lit)) * hp / fm).toInt().coerceAtLeast(0)
    }

    fun brazierDamage(p: Player): Int {
        val hp = p.getSkills().getBaseLevel(Skills.HITPOINTS) + 1
        val fm = maxOf(1, p.getSkills().getBaseLevel(Skills.FIREMAKING))
        return (((10.0 - warmItemsWorn(p)) * hp / fm).toInt() * 2).coerceAtLeast(0)
    }

    fun areaDamage(p: Player): Int {
        val hp = p.getSkills().getBaseLevel(Skills.HITPOINTS) + 1
        val fm = maxOf(1, p.getSkills().getBaseLevel(Skills.FIREMAKING))
        return (((10.0 - warmItemsWorn(p)) * hp / fm).toInt() * 3).coerceAtLeast(0)
    }

    // ───────────────────────────── supply crate ─────────────────────────────

    /** One roll of the crate's supply table (no coins — FoV reward rule). */
    data class Supply(val key: String, val min: Int, val max: Int, val weight: Int, val minLevel: Int = 1)

    val SUPPLIES = listOf(
        Supply("item.oak_logs_noted", 10, 25, 6),
        Supply("item.maple_logs_noted", 8, 20, 6, 45),
        Supply("item.yew_logs_noted", 5, 15, 5, 60),
        Supply("item.magic_logs_noted", 3, 8, 4, 75),
        Supply("item.coal_noted", 10, 25, 6),
        Supply("item.mithril_ore_noted", 5, 12, 5, 55),
        Supply("item.adamantite_ore_noted", 3, 8, 4, 70),
        Supply("item.grimy_ranarr_weed_noted", 2, 5, 5),
        Supply("item.grimy_toadflax_noted", 2, 5, 4),
        Supply("item.grimy_snapdragon_noted", 1, 3, 3, 60),
        Supply("item.ranarr_seed", 1, 2, 3),
        Supply("item.snapdragon_seed", 1, 1, 2, 60),
        Supply("item.torstol_seed", 1, 1, 1, 75),
        Supply("item.magic_seed", 1, 1, 1, 75),
        Supply("item.raw_shark_noted", 4, 10, 5),
        Supply("item.uncut_sapphire", 1, 3, 4),
        Supply("item.uncut_emerald", 1, 2, 3),
        Supply("item.uncut_ruby", 1, 2, 2),
        Supply("item.uncut_diamond", 1, 1, 1),
        Supply("item.pure_essence_noted", 30, 80, 5),
        Supply("item.saltpetre_noted", 5, 20, 3),
        Supply("item.dragon_bones_noted", 2, 5, 2),
    )
    const val CRATE_ROLLS = 3

    data class Unique(val key: String, val oneIn: Int)

    val UNIQUES = listOf(
        Unique("item.burnt_page", 45),
        Unique("item.pyromancer_garb", 150),
        Unique("item.pyromancer_robe", 150),
        Unique("item.pyromancer_hood", 150),
        Unique("item.pyromancer_boots", 150),
        Unique("item.warm_gloves", 150),
        Unique("item.bruma_torch", 150),
        Unique("item.tome_of_fire_empty", 1000),
        Unique("item.phoenix", 5000),
    )
    val LOGGED = setOf("item.pyromancer_garb", "item.pyromancer_robe", "item.pyromancer_hood", "item.pyromancer_boots", "item.warm_gloves", "item.bruma_torch", "item.tome_of_fire_empty", "item.phoenix")

    fun worn(p: Player, slot: EquipmentType): Int? = p.equipment[slot.id]?.id
}
