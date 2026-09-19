package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.strategy.ranged.weapon.Bows
import org.alter.rscm.RSCM.getRSCM

/**
 * The revenant weapons and their Wilderness bonus: **+50% accuracy and damage against NPCs in the
 * Wilderness while charged** (OSRS Wiki).
 *
 * Community question, 2026-09-18: "does pk wildy weapons do more damage & acc while in wildy?" The
 * answer was *only for two of the six*. `RangedCombatFormula` had carried the rule for Craw's bow
 * and the webweaver since 2026-09-03, but the MELEE pair (Viggora's chainmace, Ursine chainmace)
 * and the MAGIC pair (Thammaron's sceptre, Accursed sceptre) had no bonus anywhere — a Viggora's
 * hit exactly as hard in the deep wild as a rune mace, which is the whole reason the weapon exists.
 *
 * One table, one multiplier, consumed by all three formulas so they can no longer disagree. The
 * UNCHARGED forms (`_u`) are deliberately absent: uncharged is the dismantled, no-effect state, the
 * same rule [Bows.REVENANT_BOWS] already follows.
 */
object RevenantWeapons {

    /** +50% damage and accuracy vs NPCs in the Wilderness, charged only (OSRS Wiki). */
    const val WILDERNESS_MULTIPLIER = 1.5

    private fun ids(vararg keys: String): Set<Int> =
        keys.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    /** Viggora's chainmace and its Ursine upgrade. */
    val MELEE: Set<Int> by lazy { ids("item.viggoras_chainmace", "item.ursine_chainmace") }

    /**
     * Thammaron's sceptre and its Accursed upgrade, each in both the plain and the attuned ("a")
     * form — the attuned one is the same weapon swapped to cast Ancients, so it keeps the bonus.
     */
    val MAGIC: Set<Int> by lazy {
        ids(
            "item.thammarons_sceptre", "item.thammarons_sceptre_a",
            "item.accursed_sceptre", "item.accursed_sceptre_a",
        )
    }

    /** Craw's bow and the webweaver — owned by [Bows] since 2026-09-03, re-exported here so all
     *  three combat classes read the revenant family from one place. */
    val RANGED: Set<Int> get() = Bows.REVENANT_BOWS

    /**
     * The multiplier to apply to accuracy and damage for a player wielding one of [weapons]:
     * [WILDERNESS_MULTIPLIER] against an NPC in live Wilderness, else 1.0.
     *
     * NPCs only, exactly as OSRS has it — these weapons do not hit other players harder.
     */
    fun wildernessMultiplier(player: Player, target: Pawn, weapons: Set<Int>): Double =
        if (target is Npc &&
            player.getEquipment(EquipmentType.WEAPON)?.id in weapons &&
            PvpZones.isWilderness(player.tile)
        ) WILDERNESS_MULTIPLIER else 1.0
}
