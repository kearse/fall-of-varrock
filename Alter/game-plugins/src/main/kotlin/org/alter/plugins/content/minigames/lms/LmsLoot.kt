package org.alter.plugins.content.minigames.lms

import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * Last Man Standing loot — the **crate** table the island's chests pay out (the scavenge-for-gear
 * upgrade loop on top of your built kit, see [LmsKits]) and the **scavenge** pile a kill drops to
 * its killer.
 *
 * Pure data. Item RSCM keys are resolved defensively by the engine ([org.alter.rscm.RSCM.getRSCM] in a
 * runCatching), so an unknown name is skipped rather than fatal — the same names the PK-training kits
 * use are known-good against this cache.
 */
object LmsLoot {

    /**
     * A crate roll: a guaranteed supply, one weighted gear/supply pick, and a small shot at a strong
     * weapon. Delivered straight into the opener's inventory (overflow drops at their feet).
     */
    val CRATE = DropTable(
        always = listOf(
            DropEntry("item.shark", 2, 4),
        ),
        main = listOf(
            // Armour
            DropEntry("item.helm_of_neitiznot", 1, 1, weight = 8),
            DropEntry("item.fighter_torso", 1, 1, weight = 8),
            DropEntry("item.black_dhide_body", 1, 1, weight = 8),
            DropEntry("item.black_dhide_chaps", 1, 1, weight = 8),
            DropEntry("item.dragon_boots", 1, 1, weight = 6),
            DropEntry("item.barrows_gloves", 1, 1, weight = 6),
            DropEntry("item.amulet_of_torture", 1, 1, weight = 5),
            DropEntry("item.dragon_defender", 1, 1, weight = 5),
            DropEntry("item.berserker_ring", 1, 1, weight = 4),
            // Weapons
            DropEntry("item.abyssal_whip", 1, 1, weight = 7),
            DropEntry("item.magic_shortbow", 1, 1, weight = 7),
            DropEntry("item.ancient_staff", 1, 1, weight = 6),
            DropEntry("item.occult_necklace", 1, 1, weight = 4),
            // Ammo / runes
            DropEntry("item.rune_arrow", 150, 350, weight = 10),
            DropEntry("item.death_rune", 100, 250, weight = 8),
            DropEntry("item.blood_rune", 80, 200, weight = 8),
            DropEntry("item.water_rune", 200, 500, weight = 8),
            // Sustain
            DropEntry("item.saradomin_brew4", 1, 3, weight = 10),
            DropEntry("item.super_restore4", 1, 2, weight = 8),
            DropEntry("item.cooked_karambwan", 2, 5, weight = 8),
        ),
        rare = listOf(
            DropEntry("item.dragon_claws", 1, 1, oneInN = 10),
            DropEntry("item.armadyl_godsword", 1, 1, oneInN = 14),
        ),
    )

    /** The pile a kill drops to its killer — a modest resupply reward (not the victim's whole kit, so
     *  early kills don't snowball a game beyond recovery). */
    val SCAVENGE = DropTable(
        always = listOf(
            DropEntry("item.shark", 2, 4),
            DropEntry("item.super_restore4", 1, 1),
        ),
        main = listOf(
            DropEntry("item.saradomin_brew4", 1, 2, weight = 10),
            DropEntry("item.cooked_karambwan", 2, 4, weight = 8),
            DropEntry("item.death_rune", 50, 120, weight = 6),
            DropEntry("item.rune_arrow", 80, 160, weight = 6),
        ),
    )
}
