package org.alter.plugins.content.mechanics.prayer

import org.alter.rscm.RSCM.getRSCM

/**
 * The one bone table: OSRS Prayer xp per bone, consumed by the altar/bury plugin AND the
 * companions' bone-feeding. It used to be two separate 7-entry lists (bones, bat, big, baby
 * dragon, wolf, jogre, dragon) — every other bone in the game had no Bury option and could not be
 * offered ("Hydra bones cannot be used on the altar", 2026-09-03). Long/curved bones give no
 * Prayer xp in OSRS and are deliberately absent.
 */
object Bones {

    data class Bone(val key: String, val xp: Double)

    val ALL: List<Bone> = listOf(
        Bone("item.bones", 4.5),
        Bone("item.burnt_bones", 4.5),
        Bone("item.wolf_bones", 4.5),
        Bone("item.monkey_bones", 5.0),
        Bone("item.bat_bones", 5.3),
        Bone("item.wyrmling_bones", 11.0),
        Bone("item.big_bones", 15.0),
        Bone("item.jogre_bones", 15.0),
        Bone("item.zogre_bones", 22.5),
        Bone("item.shaikahan_bones", 25.0),
        Bone("item.sunkissed_bones", 25.0),
        Bone("item.babydragon_bones", 30.0),
        Bone("item.wyrm_bones", 50.0),
        Bone("item.dragon_bones", 72.0),
        Bone("item.wyvern_bones", 72.0),
        Bone("item.drake_bones", 80.0),
        Bone("item.fayrg_bones", 84.0),
        Bone("item.lava_dragon_bones", 85.0),
        Bone("item.raurg_bones", 96.0),
        Bone("item.hydra_bones", 110.0),
        Bone("item.dagannoth_bones", 125.0),
        Bone("item.ourg_bones", 140.0),
        Bone("item.superior_dragon_bones", 150.0),
    )

    /** id → xp for every bone whose RSCM key resolves in this cache. */
    val byId: Map<Int, Double> by lazy {
        ALL.mapNotNull { b -> runCatching { getRSCM(b.key) }.getOrNull()?.let { it to b.xp } }.toMap()
    }
}
