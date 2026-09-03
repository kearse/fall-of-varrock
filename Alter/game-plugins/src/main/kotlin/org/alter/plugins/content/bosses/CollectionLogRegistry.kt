package org.alter.plugins.content.bosses

import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * The **structure** of the Collection Log: which sources (bosses) contribute which loggable
 * items. [CollectionLog] stores the flat set of obtained item ids per player; this registry
 * groups them into categories so we can show per-source progress (obtained / total) and which
 * slots are still missing — the data backbone any view (chatbox command OR cache interface)
 * consumes.
 *
 * **Single source of truth:** the [Category.items] here must mirror the `log = true` drops in
 * each boss's loot table (currently the Corporeal Beast's `uniqueTable` in
 * [org.alter.plugins.content.war.boss.BossRegistry] / [org.alter.plugins.content.war.boss.BossLoot]).
 *
 * Item/npc keys stay as RSCM strings and are resolved lazily + guarded, so an id not yet in the
 * cache is skipped rather than crashing.
 */
object CollectionLogRegistry {

    /**
     * @param npcKey npc RSCM key for the page's head icon (null = no head). Best-effort.
     * @param items the loggable drops for this source, in display order (RSCM item keys).
     */
    data class Category(
        val key: String,
        val displayName: String,
        val npcKey: String?,
        val items: List<String>,
    )

    val categories: List<Category> = listOf(
        Category(
            "corp_beast", "Corporeal Beast", "npc.corporeal_beast",
            listOf(
                "item.arcane_sigil", "item.spectral_sigil", "item.elysian_sigil",
                "item.blessed_spirit_shield", "item.draconic_visage", "item.pet_corporeal_critter",
            ),
        ),
        Category(
            "vorkath", "Vorkath", "npc.vorkath_8061",
            listOf(
                "item.vorkaths_head_21907", "item.dragonbone_necklace", "item.jar_of_decay",
                "item.skeletal_visage", "item.vorki",
            ),
        ),
        Category(
            "zulrah", "Zulrah", "npc.zulrah",
            listOf(
                "item.tanzanite_fang", "item.magic_fang", "item.serpentine_visage",
                "item.uncut_onyx", "item.jar_of_swamp", "item.tanzanite_mutagen",
                "item.magma_mutagen", "item.pet_snakeling",
            ),
        ),
        Category(
            "alchemical_hydra", "Alchemical Hydra", "npc.alchemical_hydra",
            listOf(
                "item.hydras_eye", "item.hydras_fang", "item.hydras_heart",
                "item.hydra_tail", "item.hydra_leather", "item.hydras_claw",
                "item.jar_of_chemicals", "item.ikkle_hydra",
            ),
        ),
        Category(
            "gwd_bandos", "General Graardor", "npc.general_graardor",
            listOf(
                "item.bandos_chestplate", "item.bandos_tassets", "item.bandos_boots",
                "item.bandos_hilt", "item.godsword_shard_1", "item.godsword_shard_2",
                "item.godsword_shard_3", "item.pet_general_graardor",
            ),
        ),
        Category(
            "gwd_saradomin", "Commander Zilyana", "npc.commander_zilyana",
            listOf(
                "item.saradomin_sword", "item.armadyl_crossbow", "item.saradomins_light",
                "item.saradomin_hilt", "item.godsword_shard_1", "item.godsword_shard_2",
                "item.godsword_shard_3", "item.pet_zilyana",
            ),
        ),
        Category(
            "gwd_zamorak", "K'ril Tsutsaroth", "npc.kril_tsutsaroth",
            listOf(
                "item.zamorakian_spear", "item.steam_battlestaff", "item.staff_of_the_dead",
                "item.zamorak_hilt", "item.godsword_shard_1", "item.godsword_shard_2",
                "item.godsword_shard_3", "item.pet_kril_tsutsaroth",
            ),
        ),
        Category(
            "gwd_armadyl", "Kree'arra", "npc.kreearra_3162",
            listOf(
                "item.armadyl_helmet", "item.armadyl_chestplate", "item.armadyl_chainskirt",
                "item.armadyl_hilt", "item.godsword_shard_1", "item.godsword_shard_2",
                "item.godsword_shard_3", "item.pet_kreearra",
            ),
        ),
        // Mirrors Barrows.PIECES (the 24 chest uniques, brother by brother).
        Category(
            "barrows", "Barrows Chests", "npc.dharok_the_wretched",
            listOf(
                "item.ahrims_hood", "item.ahrims_robetop", "item.ahrims_robeskirt", "item.ahrims_staff",
                "item.dharoks_helm", "item.dharoks_platebody", "item.dharoks_platelegs", "item.dharoks_greataxe",
                "item.guthans_helm", "item.guthans_platebody", "item.guthans_chainskirt", "item.guthans_warspear",
                "item.karils_coif", "item.karils_leathertop", "item.karils_leatherskirt", "item.karils_crossbow",
                "item.torags_helm", "item.torags_platebody", "item.torags_platelegs", "item.torags_hammers",
                "item.veracs_helm", "item.veracs_brassard", "item.veracs_plateskirt", "item.veracs_flail",
            ),
        ),
        // Lair-boss package (LairBosses): mirrors each table's log = true rares + pet.
        Category(
            "kbd", "King Black Dragon", "npc.king_black_dragon",
            listOf("item.kbd_heads", "item.dragon_pickaxe", "item.draconic_visage", "item.prince_black_dragon"),
        ),
        Category(
            "giant_mole", "Giant Mole", "npc.giant_mole",
            listOf("item.mole_claw", "item.mole_skin", "item.baby_mole"),
        ),
        Category(
            "kalphite_queen", "Kalphite Queen", "npc.kalphite_queen_963",
            listOf("item.dragon_chainbody", "item.dragon_2h_sword", "item.kq_head", "item.jar_of_sand", "item.kalphite_princess"),
        ),
        Category(
            "dagannoth_kings", "Dagannoth Kings", "npc.dagannoth_rex",
            listOf(
                "item.berserker_ring", "item.warrior_ring", "item.seers_ring", "item.archers_ring",
                "item.mud_battlestaff", "item.seercull", "item.dragon_axe",
                "item.pet_dagannoth_rex", "item.pet_dagannoth_prime", "item.pet_dagannoth_supreme",
            ),
        ),
        // Wilderness-boss package (WildernessBosses): each table's log = true rares + pet.
        Category("callisto", "Callisto", "npc.callisto_6609", listOf("item.dragon_pickaxe", "item.tyrannical_ring", "item.callisto_cub")),
        Category("vetion", "Vet'ion", "npc.vetion", listOf("item.dragon_pickaxe", "item.ring_of_the_gods", "item.vetion_jr")),
        Category("venenatis", "Venenatis", "npc.venenatis_6610", listOf("item.dragon_pickaxe", "item.treasonous_ring", "item.venenatis_spiderling")),
        Category("scorpia", "Scorpia", "npc.scorpia", listOf("item.odium_shard_3", "item.malediction_shard_3", "item.scorpias_offspring")),
        Category("chaos_elemental", "Chaos Elemental", "npc.chaos_elemental_2054", listOf("item.dragon_pickaxe", "item.pet_chaos_elemental")),
        Category("chaos_fanatic", "Chaos Fanatic", "npc.chaos_fanatic", listOf("item.odium_shard_1", "item.malediction_shard_1")),
        Category("crazy_archaeologist", "Crazy Archaeologist", "npc.crazy_archaeologist", listOf("item.fedora", "item.odium_shard_2", "item.malediction_shard_2")),
        // Slayer-boss package (SlayerBosses): each table's log = true rares + pet.
        Category("kraken", "Kraken", "npc.kraken", listOf("item.trident_of_the_seas_full", "item.kraken_tentacle", "item.jar_of_dirt", "item.pet_kraken")),
        Category(
            "cerberus", "Cerberus", "npc.cerberus",
            listOf("item.primordial_crystal", "item.pegasian_crystal", "item.eternal_crystal", "item.smouldering_stone", "item.jar_of_souls", "item.hellpuppy"),
        ),
        Category("thermonuclear_smoke_devil", "Thermonuclear Smoke Devil", "npc.thermonuclear_smoke_devil", listOf("item.occult_necklace", "item.smoke_battlestaff", "item.dragon_chainbody", "item.pet_smoke_devil")),
        Category("skotizo", "Skotizo", "npc.skotizo", listOf("item.dark_claw", "item.uncut_onyx", "item.jar_of_darkness", "item.skotos")),
        Category("demonic_gorilla", "Demonic Gorillas", "npc.demonic_gorilla", listOf("item.zenyte_shard", "item.ballista_limbs", "item.ballista_spring", "item.heavy_frame", "item.monkey_tail")),
        // Moons of Peril — the Lunar Chest's twelve uniques (Moons.Moon.pieces).
        Category(
            "moons_of_peril", "Moons of Peril", "npc.blood_moon",
            listOf(
                "item.blood_moon_helm", "item.blood_moon_chestplate", "item.blood_moon_tassets", "item.dual_macuahuitl",
                "item.blue_moon_helm", "item.blue_moon_chestplate", "item.blue_moon_tassets", "item.blue_moon_spear",
                "item.eclipse_moon_helm", "item.eclipse_moon_chestplate", "item.eclipse_moon_tassets", "item.eclipse_atlatl",
            ),
        ),
        // Fallen Varrock PvM layer — the War-Forging materials the ruins give up.
        Category("fallen_varrock", "Fallen Varrock", "npc.zombies_champion", listOf("item.varrock_relic", "item.burnt_page")),
        // Senntisten Expeditions — the Custodian's log page and the relics beneath the Digsite.
        Category("senntisten", "Senntisten Expeditions", "npc.lesser_demon_champion", listOf("item.expedition_log", "item.varrock_relic")),
    )

    fun byKey(key: String): Category? = categories.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** Resolved (guarded) item ids for [cat], in display order; unknown cache ids dropped. */
    fun itemIds(cat: Category): List<Int> = cat.items.mapNotNull { resolve(it) }

    /** All distinct loggable item ids across every category (the union the log can hold). */
    fun allItemIds(): Set<Int> = categories.flatMap { itemIds(it) }.toSet()

    /** How many of [cat]'s items [player] has obtained. */
    fun obtainedCount(player: Player, cat: Category): Int {
        val have = CollectionLog.ids(player)
        return itemIds(cat).count { it in have }
    }

    /** Total loggable items in [cat] (resolved count). */
    fun total(cat: Category): Int = itemIds(cat).size

    /** Overall obtained / total across all categories (distinct items). */
    fun overallObtained(player: Player): Int {
        val have = CollectionLog.ids(player)
        return allItemIds().count { it in have }
    }

    fun overallTotal(): Int = allItemIds().size

    private fun resolve(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }
}
