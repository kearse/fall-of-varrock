package org.alter.plugins.content.bosses.lairs

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **shared-world lair bosses** — Kronos port #7, a package port of the four classic
 * "walk in and fight" bosses: King Black Dragon, Giant Mole, Kalphite Queen and the
 * Dagannoth Kings (+ their spinolyps). One registry, one plugin trio; adding a lair boss =
 * adding a [LairBoss] here plus its fight in [LairBossesCombatPlugin].
 *
 * Every number is the donor's: spawn tiles from the `data/npcs/spawns` JSON, stats from
 * `data/npcs/combat/<Name>.json`, loot from `data/npcs/drops/eco/<Name>.json` folded into
 * our three-tier [DropTable]. Uniques keep their OSRS 1/128 odds; pets and the KBD
 * visage/pickaxe use the Vorkath-pilot scale (1/1000 where OSRS is 1/3000-5000).
 */
object LairBosses {

    data class LairBoss(
        val key: String,
        val name: String,
        /** The npc that carries the drop table on death (KQ: the second form). */
        val lootKey: String,
        /** Every npc form spawned/managed for this boss (spawn tile + walk radius). */
        val spawns: List<Spawn>,
        val drops: DropTable,
        val tickets: Int,
        val pet: String?,
        val petOneIn: Int,
        val mainRolls: Int = 1,
    )

    data class Spawn(val npcKey: String, val tile: Tile, val walkRadius: Int, val engineRespawn: Boolean = true)

    private fun coins(min: Int, max: Int, w: Int) = DropEntry("item.coins_995", min, max, weight = w)

    // ───────────────────────────── King Black Dragon ─────────────────────────────

    /** KBD lair island, region 9033 (multi). Spawn from the pre-purge KbdConfigsPlugin; TUNE. */
    val KBD_SPAWN = Tile(2274, 4698, 0)
    val KBD_LANDING = Tile(2271, 4680, 0)

    val KBD = LairBoss(
        key = "kbd", name = "King Black Dragon", lootKey = "npc.king_black_dragon",
        spawns = listOf(Spawn("npc.king_black_dragon", KBD_SPAWN, walkRadius = 5)),
        drops = DropTable(
            always = listOf(DropEntry("item.dragon_bones", 1, 1), DropEntry("item.black_dragonhide", 2, 2)),
            // Kronos King_black_dragon.json: Weapons/Armour, Runes/Ammunition, Other — equal
            // sub-table weight, folded flat (weights scaled to a common denominator).
            main = listOf(
                DropEntry("item.rune_longsword", 1, 1, weight = 25),
                DropEntry("item.adamant_platebody", 1, 1, weight = 25),
                DropEntry("item.adamant_kiteshield", 1, 1, weight = 10),
                DropEntry("item.dragon_med_helm", 1, 1, weight = 10),
                DropEntry("item.fire_rune", 300, 300, weight = 16),
                DropEntry("item.air_rune", 300, 300, weight = 16),
                DropEntry("item.blood_rune", 30, 30, weight = 11),
                DropEntry("item.iron_arrow", 690, 690, weight = 11),
                DropEntry("item.law_rune", 30, 30, weight = 5),
                DropEntry("item.runite_bolts", 10, 25, weight = 5),
                DropEntry("item.dragon_dart_tip", 5, 14, weight = 4),
                DropEntry("item.dragon_arrowtips", 5, 14, weight = 4),
                DropEntry("item.dragon_javelin_heads", 15, 15, weight = 4),
                DropEntry("item.amulet_of_power", 1, 1, weight = 10),
                DropEntry("item.yew_logs_noted", 150, 150, weight = 10),
                DropEntry("item.shark", 4, 4, weight = 10),
                DropEntry("item.gold_ore_noted", 100, 100, weight = 17),
                DropEntry("item.runite_limbs", 1, 1, weight = 7),
                DropEntry("item.runite_bar", 1, 1, weight = 7),
                DropEntry("item.adamantite_bar", 3, 3, weight = 7),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 2),
            ),
            rare = listOf(
                DropEntry("item.kbd_heads", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 1000, announce = true, log = true),
                DropEntry("item.draconic_visage", 1, 1, oneInN = 1000, announce = true, log = true),
            ),
        ),
        tickets = 20, pet = "item.prince_black_dragon", petOneIn = 1000,
    )

    // ───────────────────────────── Giant Mole ─────────────────────────────

    /** Falador mole lair, regions 6992/6993 (multi). Kronos giant_mole_lair.json: 1759,5184 walk 6. */
    val MOLE_SPAWN = Tile(1759, 5184, 0)
    val MOLE_LANDING = Tile(1760, 5175, 0)

    /** Kronos GiantMole.BURROW_POINTS — offsets from the spawn tile the mole surfaces at. */
    val MOLE_BURROW_POINTS = listOf(
        -21 to 38, -15 to 22, -19 to 1, -15 to -14, -20 to -33, -3 to -33,
        1 to -22, 12 to -11, 10 to 15, 22 to 35, 18 to 51,
    )

    val GIANT_MOLE = LairBoss(
        key = "giant_mole", name = "Giant Mole", lootKey = "npc.giant_mole",
        spawns = listOf(Spawn("npc.giant_mole", MOLE_SPAWN, walkRadius = 6)),
        drops = DropTable(
            always = listOf(
                DropEntry("item.big_bones", 1, 1),
                DropEntry("item.mole_claw", 1, 1),
                DropEntry("item.mole_skin", 1, 3),
            ),
            // Kronos Giant_mole.json, five equal sub-tables folded flat.
            main = listOf(
                DropEntry("item.air_rune", 105, 105, weight = 18),
                DropEntry("item.fire_rune", 105, 105, weight = 18),
                DropEntry("item.blood_rune", 15, 15, weight = 9),
                DropEntry("item.death_rune", 7, 7, weight = 9),
                DropEntry("item.law_rune", 15, 15, weight = 3),
                DropEntry("item.iron_arrow", 690, 690, weight = 3),
                DropEntry("item.adamant_longsword", 1, 1, weight = 36),
                DropEntry("item.mithril_axe", 1, 1, weight = 18),
                DropEntry("item.mithril_battleaxe", 1, 1, weight = 6),
                DropEntry("item.mithril_platebody", 1, 1, weight = 36),
                DropEntry("item.rune_med_helm", 1, 1, weight = 18),
                DropEntry("item.amulet_of_strength", 1, 1, weight = 6),
                DropEntry("item.iron_ore_noted", 100, 100, weight = 30),
                DropEntry("item.mithril_bar", 1, 1, weight = 30),
                DropEntry("item.shark", 4, 4, weight = 19),
                DropEntry("item.yew_logs_noted", 100, 100, weight = 19),
                DropEntry("item.oyster_pearls", 1, 1, weight = 19),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 3),
            ),
        ),
        tickets = 15, pet = "item.baby_mole", petOneIn = 1000,
    )

    // ───────────────────────────── Kalphite Queen ─────────────────────────────

    /** Kalphite lair, region 13972 (multi). Kronos kalphite_lair.json: 3475,9491 walk 6. */
    val KQ_SPAWN = Tile(3475, 9491, 0)
    val KQ_LANDING = Tile(3480, 9483, 0)
    const val KQ_FORM_1 = "npc.kalphite_queen_963"
    const val KQ_FORM_2 = "npc.kalphite_queen_965"
    const val KQ_RESPAWN_TICKS = 50

    val KALPHITE_QUEEN = LairBoss(
        key = "kalphite_queen", name = "Kalphite Queen", lootKey = KQ_FORM_2,
        // Both forms are hand-managed (form 1 dies into form 2; form 2 dies into loot + a
        // delayed form-1 respawn) — never engine-respawned.
        spawns = listOf(Spawn(KQ_FORM_1, KQ_SPAWN, walkRadius = 6, engineRespawn = false)),
        drops = DropTable(
            // Kronos Kalphite_queen.json, six equal sub-tables folded flat.
            main = listOf(
                DropEntry("item.rune_chainbody", 1, 1, weight = 12),
                DropEntry("item.lava_battlestaff", 1, 1, weight = 12),
                DropEntry("item.red_dhide_body", 1, 1, weight = 6),
                DropEntry("item.rune_knifep_5667", 25, 25, weight = 3),
                DropEntry("item.battlestaff_noted", 10, 30, weight = 3),
                DropEntry("item.monkfish", 3, 3, weight = 9),
                DropEntry("item.shark", 2, 2, weight = 6),
                DropEntry("item.super_combat_potion2", 1, 1, weight = 6),
                DropEntry("item.ranging_potion3", 1, 1, weight = 6),
                DropEntry("item.superantipoison2", 1, 1, weight = 3),
                DropEntry("item.dark_crab", 2, 2, weight = 3),
                DropEntry("item.saradomin_brew4", 1, 1, weight = 1),
                DropEntry("item.super_restore4", 1, 1, weight = 1),
                DropEntry("item.prayer_potion4", 2, 2, weight = 1),
                DropEntry("item.watermelon_seed", 25, 25, weight = 12),
                DropEntry("item.torstol_seed", 2, 2, weight = 9),
                DropEntry("item.magic_seed", 2, 2, weight = 6),
                DropEntry("item.papaya_tree_seed", 2, 2, weight = 3),
                DropEntry("item.palm_tree_seed", 2, 2, weight = 1),
                DropEntry("item.blood_rune", 100, 100, weight = 9),
                DropEntry("item.mithril_arrow", 500, 500, weight = 6),
                DropEntry("item.rune_arrow", 250, 250, weight = 3),
                DropEntry("item.death_rune", 150, 150, weight = 1),
                DropEntry("item.uncut_ruby_noted", 25, 25, weight = 6),
                DropEntry("item.uncut_emerald_noted", 25, 25, weight = 3),
                DropEntry("item.uncut_diamond_noted", 25, 25, weight = 1),
                DropEntry("item.potato_cactus_noted", 100, 100, weight = 12),
                DropEntry("item.wine_of_zamorak_noted", 60, 60, weight = 12),
                coins(15_000, 20_000, 12),
                DropEntry("item.ensouled_kalphite_head", 1, 1, weight = 9),
                DropEntry("item.runite_bar_noted", 3, 3, weight = 9),
                DropEntry("item.magic_logs_noted", 60, 60, weight = 6),
                DropEntry("item.grapes_noted", 100, 100, weight = 6),
                DropEntry("item.bucket_of_sand_noted", 100, 100, weight = 6),
                DropEntry("item.cactus_spine_noted", 10, 10, weight = 6),
                DropEntry("item.gold_ore_noted", 250, 250, weight = 3),
                DropEntry("item.weapon_poison_noted", 5, 5, weight = 3),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 1),
            ),
            rare = listOf(
                DropEntry("item.dragon_chainbody", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.dragon_2h_sword", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.kq_head", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.jar_of_sand", 1, 1, oneInN = 128, log = true),
            ),
        ),
        tickets = 25, pet = "item.kalphite_princess", petOneIn = 1000,
    )

    // ───────────────────────────── Dagannoth Kings ─────────────────────────────

    /** Waterbirth Dungeon kings' chamber, region 11589 (multi). Kronos waterbirth.json (the 4448 set). */
    val DKS_LANDING = Tile(2898, 4450, 0)

    private val DKS_ALWAYS = listOf(DropEntry("item.dagannoth_bones", 1, 1), DropEntry("item.dagannoth_hide", 1, 1))

    val DAGANNOTH_REX = LairBoss(
        key = "dagannoth_rex", name = "Dagannoth Rex", lootKey = "npc.dagannoth_rex",
        spawns = listOf(Spawn("npc.dagannoth_rex", Tile(2915, 4445, 0), walkRadius = 3)),
        drops = DropTable(
            always = DKS_ALWAYS,
            // Kronos Dagannoth_rex.json, six equal sub-tables folded flat.
            main = listOf(
                DropEntry("item.fremennik_blade", 1, 1, weight = 11),
                DropEntry("item.mithril_warhammer", 1, 1, weight = 11),
                DropEntry("item.mithril_2h_sword", 1, 1, weight = 11),
                DropEntry("item.mithril_pickaxe", 1, 1, weight = 11),
                DropEntry("item.adamant_axe", 1, 1, weight = 2),
                DropEntry("item.rune_axe", 1, 1, weight = 2),
                DropEntry("item.steel_kiteshield", 1, 1, weight = 11),
                DropEntry("item.steel_platebody", 1, 1, weight = 5),
                DropEntry("item.adamant_platebody", 1, 1, weight = 5),
                DropEntry("item.fremennik_shield", 1, 1, weight = 5),
                DropEntry("item.fremennik_helm", 1, 1, weight = 5),
                DropEntry("item.rockshell_plate", 1, 1, weight = 5),
                DropEntry("item.rockshell_legs", 1, 1, weight = 5),
                DropEntry("item.ring_of_life", 1, 1, weight = 5),
                DropEntry("item.antifire_potion2", 1, 1, weight = 16),
                DropEntry("item.prayer_potion2", 1, 1, weight = 8),
                DropEntry("item.super_attack2", 1, 1, weight = 8),
                DropEntry("item.super_strength2", 1, 1, weight = 8),
                DropEntry("item.super_defence2", 1, 1, weight = 3),
                DropEntry("item.zamorak_brew2", 1, 1, weight = 3),
                DropEntry("item.restore_potion2", 1, 1, weight = 3),
                DropEntry("item.adamantite_bar", 1, 1, weight = 14),
                DropEntry("item.mithril_ore_noted", 25, 25, weight = 14),
                DropEntry("item.coal_noted", 100, 100, weight = 14),
                DropEntry("item.iron_ore_noted", 150, 150, weight = 5),
                DropEntry("item.steel_bar_noted", 15, 38, weight = 5),
                DropEntry("item.bass", 5, 5, weight = 30),
                DropEntry("item.swordfish", 5, 5, weight = 15),
                DropEntry("item.shark", 5, 5, weight = 5),
                coins(126, 3000, 16),
                DropEntry("item.grimy_ranarr_weed", 1, 1, weight = 16),
                DropEntry("item.ensouled_dagannoth_head", 1, 1, weight = 16),
                DropEntry("item.clue_scroll_hard", 1, 1, weight = 2),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 2),
            ),
            rare = listOf(
                DropEntry("item.berserker_ring", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.warrior_ring", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.dragon_axe", 1, 1, oneInN = 128, announce = true, log = true),
            ),
        ),
        tickets = 15, pet = "item.pet_dagannoth_rex", petOneIn = 1000,
    )

    val DAGANNOTH_PRIME = LairBoss(
        key = "dagannoth_prime", name = "Dagannoth Prime", lootKey = "npc.dagannoth_prime",
        spawns = listOf(Spawn("npc.dagannoth_prime", Tile(2911, 4453, 0), walkRadius = 3)),
        drops = DropTable(
            always = DKS_ALWAYS,
            // Kronos Dagannoth_prime.json, five equal sub-tables folded flat.
            main = listOf(
                DropEntry("item.air_battlestaff", 1, 1, weight = 13),
                DropEntry("item.earth_battlestaff", 1, 1, weight = 13),
                DropEntry("item.water_battlestaff", 1, 1, weight = 13),
                DropEntry("item.battlestaff_noted", 10, 30, weight = 13),
                DropEntry("item.air_talisman_noted", 1, 80, weight = 12),
                DropEntry("item.earth_talisman_noted", 1, 80, weight = 12),
                DropEntry("item.water_talisman_noted", 1, 80, weight = 12),
                DropEntry("item.air_rune", 155, 155, weight = 4),
                DropEntry("item.mud_rune", 32, 44, weight = 4),
                DropEntry("item.death_rune", 22, 85, weight = 4),
                DropEntry("item.blood_rune", 22, 85, weight = 4),
                DropEntry("item.fremennik_shield", 1, 1, weight = 18),
                DropEntry("item.fremennik_helm", 1, 1, weight = 9),
                DropEntry("item.skeletal_top", 1, 1, weight = 9),
                DropEntry("item.skeletal_bottoms", 1, 1, weight = 9),
                DropEntry("item.farseer_helm", 1, 1, weight = 9),
                DropEntry("item.belladonna_seed", 1, 1, weight = 6),
                DropEntry("item.cactus_seed", 1, 1, weight = 6),
                DropEntry("item.poison_ivy_seed", 1, 1, weight = 6),
                DropEntry("item.irit_seed", 1, 1, weight = 6),
                DropEntry("item.toadflax_seed", 1, 1, weight = 6),
                DropEntry("item.avantoe_seed", 1, 1, weight = 6),
                DropEntry("item.kwuarm_seed", 1, 1, weight = 6),
                DropEntry("item.cadantine_seed", 1, 1, weight = 2),
                DropEntry("item.lantadyme_seed", 1, 1, weight = 2),
                DropEntry("item.dwarf_weed_seed", 1, 1, weight = 2),
                DropEntry("item.snapdragon_seed", 1, 1, weight = 2),
                coins(972, 3000, 13),
                DropEntry("item.pure_essence_noted", 150, 150, weight = 13),
                DropEntry("item.grimy_ranarr_weed", 1, 1, weight = 13),
                DropEntry("item.shark", 5, 5, weight = 9),
                DropEntry("item.ensouled_dagannoth_head", 1, 1, weight = 9),
                DropEntry("item.clue_scroll_hard", 1, 1, weight = 1),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 1),
            ),
            rare = listOf(
                DropEntry("item.seers_ring", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.mud_battlestaff", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.dragon_axe", 1, 1, oneInN = 128, announce = true, log = true),
            ),
        ),
        tickets = 15, pet = "item.pet_dagannoth_prime", petOneIn = 1000,
    )

    val DAGANNOTH_SUPREME = LairBoss(
        key = "dagannoth_supreme", name = "Dagannoth Supreme", lootKey = "npc.dagannoth_supreme",
        spawns = listOf(Spawn("npc.dagannoth_supreme", Tile(2904, 4448, 0), walkRadius = 2)),
        drops = DropTable(
            always = DKS_ALWAYS,
            // Kronos Dagannoth_supreme.json, five equal sub-tables folded flat.
            main = listOf(
                DropEntry("item.adamant_axe", 1, 1, weight = 24),
                DropEntry("item.steel_arrow", 54, 98, weight = 6),
                DropEntry("item.iron_arrow", 218, 590, weight = 6),
                DropEntry("item.iron_knife", 214, 500, weight = 6),
                DropEntry("item.steel_knife", 54, 132, weight = 6),
                DropEntry("item.mithril_knife", 31, 69, weight = 6),
                DropEntry("item.rune_thrownaxe", 5, 8, weight = 6),
                DropEntry("item.rune_javelin", 1, 10, weight = 6),
                DropEntry("item.runite_bolts", 2, 14, weight = 6),
                DropEntry("item.red_dhide_vambraces", 1, 1, weight = 14),
                DropEntry("item.fremennik_shield", 1, 1, weight = 8),
                DropEntry("item.fremennik_helm", 1, 1, weight = 8),
                DropEntry("item.spined_body", 1, 1, weight = 8),
                DropEntry("item.spined_chaps", 1, 1, weight = 8),
                DropEntry("item.archer_helm", 1, 1, weight = 2),
                DropEntry("item.belladonna_seed", 1, 1, weight = 6),
                DropEntry("item.cactus_seed", 1, 1, weight = 6),
                DropEntry("item.poison_ivy_seed", 1, 1, weight = 6),
                DropEntry("item.irit_seed", 1, 1, weight = 6),
                DropEntry("item.toadflax_seed", 1, 1, weight = 6),
                DropEntry("item.avantoe_seed", 1, 1, weight = 6),
                DropEntry("item.kwuarm_seed", 1, 1, weight = 6),
                DropEntry("item.cadantine_seed", 1, 1, weight = 2),
                DropEntry("item.lantadyme_seed", 1, 1, weight = 2),
                DropEntry("item.dwarf_weed_seed", 1, 1, weight = 2),
                DropEntry("item.torstol_seed", 1, 1, weight = 2),
                coins(900, 3000, 5),
                DropEntry("item.opal_bolt_tips", 1, 24, weight = 5),
                DropEntry("item.oyster_pearls", 1, 1, weight = 5),
                DropEntry("item.shark", 5, 5, weight = 5),
                DropEntry("item.yew_logs_noted", 94, 140, weight = 5),
                DropEntry("item.maple_logs_noted", 25, 140, weight = 5),
                DropEntry("item.grimy_ranarr_weed", 1, 1, weight = 5),
                DropEntry("item.ensouled_dagannoth_head", 1, 1, weight = 5),
                DropEntry("item.clue_scroll_hard", 1, 1, weight = 5),
                DropEntry("item.feather", 1, 453, weight = 2),
                DropEntry("item.runite_limbs", 1, 1, weight = 2),
                DropEntry("item.clue_scroll_elite", 1, 1, weight = 1),
            ),
            rare = listOf(
                DropEntry("item.archers_ring", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.seercull", 1, 1, oneInN = 128, announce = true, log = true),
                DropEntry("item.dragon_axe", 1, 1, oneInN = 128, announce = true, log = true),
            ),
        ),
        tickets = 15, pet = "item.pet_dagannoth_supreme", petOneIn = 1000,
    )

    /** The twelve spinolyps ringing the kings' chamber (Kronos waterbirth.json, the 4448 set). */
    val SPINOLYPS: List<Tile> = listOf(
        Tile(2906, 4464, 0), Tile(2914, 4464, 0), Tile(2924, 4461, 0), Tile(2929, 4454, 0),
        Tile(2931, 4442, 0), Tile(2925, 4436, 0), Tile(2915, 4433, 0), Tile(2906, 4434, 0),
        Tile(2899, 4439, 0), Tile(2898, 4455, 0), Tile(2900, 4459, 0), Tile(2902, 4398, 0),
    )
    const val SPINOLYP_KEY = "npc.spinolyp"

    val all: List<LairBoss> = listOf(KBD, GIANT_MOLE, KALPHITE_QUEEN, DAGANNOTH_REX, DAGANNOTH_PRIME, DAGANNOTH_SUPREME)

    /** Regions force-loaded at world init (collision before any player arrives). */
    val REGIONS = intArrayOf(9033, 6992, 6993, 13972, 11589)

    /** Multi-way lairs (OSRS): every one of the four. */
    val MULTI_REGIONS = intArrayOf(9033, 6992, 6993, 13972, 11589)

    fun byLootKey(lootKey: String): LairBoss? = all.firstOrNull { it.lootKey == lootKey }
}
