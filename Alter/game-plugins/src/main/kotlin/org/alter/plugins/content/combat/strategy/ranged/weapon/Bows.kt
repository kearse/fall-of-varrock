package org.alter.plugins.content.combat.strategy.ranged.weapon

import org.alter.rscm.RSCM.getRSCM

/**
 * @author Tom <rspsmods@gmail.com>
 */
object Bows {
    val CRYSTAL_BOWS =
        arrayOf(
            getRSCM("item.new_crystal_bow"),
            getRSCM("item.new_crystal_bow_i"),
            getRSCM("item.crystal_bow_full"),
            getRSCM("item.crystal_bow_full_i"),
            getRSCM("item.crystal_bow_110"),
            getRSCM("item.crystal_bow_110_i"),
            getRSCM("item.crystal_bow_210"),
            getRSCM("item.crystal_bow_210_i"),
            getRSCM("item.crystal_bow_310"),
            getRSCM("item.crystal_bow_310_i"),
            getRSCM("item.crystal_bow_410"),
            getRSCM("item.crystal_bow_410_i"),
            getRSCM("item.crystal_bow_510"),
            getRSCM("item.crystal_bow_510_i"),
            getRSCM("item.crystal_bow_610"),
            getRSCM("item.crystal_bow_610_i"),
            getRSCM("item.crystal_bow_710"),
            getRSCM("item.crystal_bow_710_i"),
            getRSCM("item.crystal_bow_810"),
            getRSCM("item.crystal_bow_810_i"),
            getRSCM("item.crystal_bow_910"),
            getRSCM("item.crystal_bow_910_i"),
            // Modern (post-Song-of-the-Elves) crystal bow + Bow of Faerdhinen — the shop sells
            // these ids, and they were missing from every crystal-bow rule (range, set bonus).
            getRSCM("item.crystal_bow"),
            getRSCM("item.bow_of_faerdhinen"),
            getRSCM("item.bow_of_faerdhinen_c"),
        )

    /**
     * Crystal armour set effect (OSRS Wiki, Crystal armour): while wielding a crystal bow or
     * the Bow of Faerdhinen, each piece adds ranged damage / accuracy — helm 2.5% / 5%,
     * legs 5% / 10%, body 7.5% / 15% (full set 15% / 30%).
     */
    val CRYSTAL_ARMOUR_BONUS: List<Triple<Int, Double, Double>> =
        listOf(
            Triple("item.crystal_helm", 0.025, 0.05),
            Triple("item.crystal_legs", 0.05, 0.10),
            Triple("item.crystal_body", 0.075, 0.15),
        ).mapNotNull { (key, dmg, acc) -> runCatching { getRSCM(key) }.getOrNull()?.let { Triple(it, dmg, acc) } }

    /**
     * Bows that generate their own ammunition (OSRS): every crystal bow / Bow of Faerdhinen and
     * the two revenant bows. The ranged strategy must neither read nor consume the quiver for
     * these — with arrows equipped they were being eaten every shot AND adding their ranged
     * strength; with an empty quiver no projectile drew (player report 2026-09-03).
     */
    val AMMOLESS_BOWS: Set<Int> = (
        CRYSTAL_BOWS.toList() +
            listOf(
                "item.crystal_bow_24123", "item.crystal_bow_basic", "item.crystal_bow_attuned", "item.crystal_bow_perfected",
                "item.bow_of_faerdhinen_27187",
                "item.craws_bow", "item.craws_bow_u",
                "item.webweaver_bow", "item.webweaver_bow_u",
            ).mapNotNull { runCatching { getRSCM(it) }.getOrNull() }
        ).toSet()

    /**
     * The CHARGED revenant bows: +50% ranged accuracy and damage against NPCs in the Wilderness
     * (OSRS Wiki — never against players). The bows hit "very light" without it.
     */
    val REVENANT_BOWS: Set<Int> = listOf("item.craws_bow", "item.webweaver_bow")
        .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    val LONG_BOWS =
        arrayOf(
            getRSCM("item.longbow"),
            getRSCM("item.oak_longbow"),
            getRSCM("item.maple_longbow"),
            getRSCM("item.willow_longbow"),
            getRSCM("item.yew_longbow"),
            getRSCM("item.magic_longbow"),
        )
}
