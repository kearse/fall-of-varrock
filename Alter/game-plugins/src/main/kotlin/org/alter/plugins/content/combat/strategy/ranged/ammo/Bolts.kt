package org.alter.plugins.content.combat.strategy.ranged.ammo

import org.alter.rscm.RSCM.getRSCM
/**
 * @author Tom <rspsmods@gmail.com>
 */
object Bolts {
    val BRONZE_BOLTS = arrayOf(getRSCM("item.bronze_bolts"), getRSCM("item.bronze_bolts_p"), getRSCM("item.bronze_bolts_p_6061"), getRSCM("item.bronze_bolts_p_6062"))
    val IRON_BOLTS = arrayOf(getRSCM("item.iron_bolts"), getRSCM("item.iron_bolts_p"), getRSCM("item.iron_bolts_p_9294"), getRSCM("item.iron_bolts_p_9301"))
    val STEEL_BOLTS = arrayOf(getRSCM("item.steel_bolts"), getRSCM("item.steel_bolts_p"), getRSCM("item.steel_bolts_p_9295"), getRSCM("item.steel_bolts_p_9302"))
    val MITHRIL_BOLTS = arrayOf(getRSCM("item.mithril_bolts"), getRSCM("item.mithril_bolts_p"), getRSCM("item.mithril_bolts_p_9296"), getRSCM("item.mithril_bolts_p_9303"))
    val ADAMANT_BOLTS = arrayOf(getRSCM("item.adamant_bolts"), getRSCM("item.adamant_bolts_p"), getRSCM("item.adamant_bolts_p_9297"), getRSCM("item.adamant_bolts_p_9304"))
    val BROAD_BOLTS = arrayOf(getRSCM("item.broad_bolts"), getRSCM("item.amethyst_broad_bolts"))
    val RUNITE_BOLTS = arrayOf(getRSCM("item.runite_bolts"), getRSCM("item.runite_bolts_p"), getRSCM("item.runite_bolts_p_9298"), getRSCM("item.runite_bolts_p_9305"))
    val DRAGON_BOLTS = arrayOf(getRSCM("item.dragon_bolts"), getRSCM("item.dragon_bolts_p"), getRSCM("item.dragon_bolts_p_21926"), getRSCM("item.dragon_bolts_p_21928"))
    val BLURITE_BOLTS = arrayOf(getRSCM("item.blurite_bolts"), getRSCM("item.blurite_bolts_p"), getRSCM("item.blurite_bolts_p_9293"), getRSCM("item.blurite_bolts_p_9300"))
    val GEM_TIPPED_BOLTS =
        arrayOf(
            getRSCM("item.opal_bolts"), getRSCM("item.opal_bolts_e"),
            getRSCM("item.jade_bolts"), getRSCM("item.jade_bolts_e"),
            getRSCM("item.pearl_bolts"), getRSCM("item.pearl_bolts_e"),
            getRSCM("item.topaz_bolts"), getRSCM("item.topaz_bolts_e"),
            getRSCM("item.sapphire_bolts"), getRSCM("item.sapphire_bolts_e"),
            getRSCM("item.emerald_bolts"), getRSCM("item.emerald_bolts_e"),
            getRSCM("item.ruby_bolts"), getRSCM("item.ruby_bolts_e"),
            getRSCM("item.diamond_bolts"), getRSCM("item.diamond_bolts_e"), getRSCM("item.diamond_bolts_e_23649"),
            getRSCM("item.dragonstone_bolts"), getRSCM("item.dragonstone_bolts_e"),
            getRSCM("item.onyx_bolts"), getRSCM("item.onyx_bolts_e"),
        )

    /** Enchanted DRAGON bolts (e). Their effects are implemented in BoltEnchantments but
     *  they were rejected by every crossbow's ammo gate. Usable in dragon-tier crossbows. */
    val DRAGON_GEM_TIPPED_BOLTS =
        arrayOf(
            getRSCM("item.opal_dragon_bolts_e"), getRSCM("item.jade_dragon_bolts_e"),
            getRSCM("item.pearl_dragon_bolts_e"), getRSCM("item.topaz_dragon_bolts_e"),
            getRSCM("item.sapphire_dragon_bolts_e"), getRSCM("item.emerald_dragon_bolts_e"),
            getRSCM("item.ruby_dragon_bolts_e"), getRSCM("item.diamond_dragon_bolts_e"),
            getRSCM("item.dragonstone_dragon_bolts_e"), getRSCM("item.onyx_dragon_bolts_e"),
        )
    val BONE_BOLTS = arrayOf(getRSCM("item.bone_bolts"))
    val KEBBIT_BOLTS = arrayOf(getRSCM("item.kebbit_bolts"), getRSCM("item.long_kebbit_bolts"))
    val BOLT_RACKS = arrayOf(getRSCM("item.bolt_rack"))
}
