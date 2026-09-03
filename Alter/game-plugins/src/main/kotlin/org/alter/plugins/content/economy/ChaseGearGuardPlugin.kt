package org.alter.plugins.content.economy

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * **Chase gear is player-market-only.** The high-end PvM catalogue that the Warlord's Armoury used to
 * sell for Boss Tickets (retired 2026-09, design doc 04 §13) is boss-drop and war-reward only now,
 * and it keeps the NPC-conversion guard it had as shop stock: none of it can be alched or sold to the
 * Trading Post / General Store (design doc 04 §1/§15 — boss equipment is a market between players,
 * never an NPC cash-out; the cache alch values of these items would otherwise be a PvM gp faucet
 * the drop tables never priced for, e.g. a Justiciar chestguard alching for 3.6M).
 *
 * Keys resolve defensively; an unknown key is skipped. Extend the list when a new chase item ships.
 */
object ChaseGear {
    val KEYS: List<String> = listOf(
        // Megarares + high PvM weapons
        "item.holy_scythe_of_vitur", "item.sanguine_scythe_of_vitur", "item.scythe_of_vitur",
        "item.twisted_bow", "item.tumekens_shadow", "item.holy_sanguinesti_staff", "item.sanguinesti_staff",
        "item.soulreaper_axe", "item.zaryte_crossbow", "item.venator_bow", "item.harmonised_nightmare_staff",
        "item.ghrazi_rapier", "item.tonalztics_of_ralos", "item.dragon_hunter_crossbow",
        "item.dragon_hunter_lance", "item.osmumtens_fang", "item.keris_partisan",
        // GWD bases (the war-forge feedstock) + mid PvM armour
        "item.bandos_chestplate", "item.bandos_tassets", "item.armadyl_chestplate", "item.armadyl_chainskirt",
        "item.justiciar_faceguard", "item.justiciar_chestguard", "item.justiciar_legguards",
        "item.inquisitors_great_helm", "item.inquisitors_hauberk", "item.inquisitors_plateskirt",
        "item.elite_void_top", "item.elite_void_robe", "item.void_knight_top", "item.void_knight_gloves",
        // Crystal
        "item.crystal_bow", "item.crystal_halberd", "item.crystal_shield", "item.crystal_helm",
        "item.crystal_body", "item.crystal_legs", "item.blade_of_saeldor", "item.bow_of_faerdhinen",
        // BIS accessories
        "item.occult_necklace", "item.amulet_of_torture", "item.necklace_of_anguish", "item.tormented_bracelet",
        "item.amulet_of_blood_fury", "item.amulet_of_fury", "item.primordial_boots", "item.pegasian_boots",
        "item.eternal_boots", "item.ultor_ring", "item.magus_ring", "item.bellator_ring", "item.venator_ring",
        "item.lightbearer", "item.ring_of_suffering", "item.brimstone_ring", "item.berserker_ring_i",
        "item.dinhs_bulwark", "item.ancient_wyvern_shield", "item.dragonfire_shield", "item.avernic_defender",
        "item.zaryte_vambraces", "item.ferocious_gloves", "item.barrows_gloves",
        // Charged / degradable
        "item.trident_of_the_seas", "item.trident_of_the_swamp", "item.toxic_staff_of_the_dead",
        "item.toxic_blowpipe", "item.abyssal_tentacle", "item.dragon_hunter_wand", "item.scorching_bow",
        "item.serpentine_helm", "item.magma_helm", "item.tanzanite_helm",
        // 3rd age relics (the war's prestige line)
        "item._3rd_age_full_helmet", "item._3rd_age_platebody", "item._3rd_age_platelegs", "item._3rd_age_kiteshield",
        "item._3rd_age_range_coif", "item._3rd_age_range_top", "item._3rd_age_range_legs", "item._3rd_age_vambraces",
        "item._3rd_age_mage_hat", "item._3rd_age_robe_top", "item._3rd_age_robe", "item._3rd_age_amulet",
        "item._3rd_age_longsword", "item._3rd_age_bow", "item._3rd_age_wand", "item._3rd_age_cloak",
        "item._3rd_age_druidic_robe_top", "item._3rd_age_druidic_robe_bottoms", "item._3rd_age_druidic_staff",
        "item._3rd_age_druidic_cloak",
    )

    fun ids(): List<Int> = KEYS.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }
}

class ChaseGearGuardPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        SpecialShopGuard.register(ChaseGear.ids())
    }
}
