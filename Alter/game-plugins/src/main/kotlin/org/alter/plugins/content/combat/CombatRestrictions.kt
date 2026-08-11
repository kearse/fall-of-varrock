package org.alter.plugins.content.combat

import org.alter.game.model.entity.Player
import org.alter.plugins.content.minigames.duel.DuelArena
import org.alter.plugins.content.minigames.pktraining.CompanionSparring

/**
 * The merged combat-restriction view over whatever rule-bound bout a player is in — an active
 * **duel** ([DuelArena]) or a companion **sparring bout** ([CompanionSparring]). The two rule
 * types stay deliberate parallels (stakes vs. none), but every enforcement site they share
 * (eating, potions, prayers, style bans, special attacks) asks ONE question here instead of
 * consulting both registries — see docs/duel-arena-implementation-plan.md, "Companion sparring —
 * parity & reuse".
 *
 * Restrictions that exist for only one side of the parallel (duel: forfeit, weapon lock, fun
 * weapons, gear slots; sparring: max stats) are enforced at their own sites and are not part of
 * this view.
 */
class CombatRestrictions private constructor(
    val noMelee: Boolean,
    val noRanged: Boolean,
    val noMagic: Boolean,
    val noPrayer: Boolean,
    val noFood: Boolean,
    val noDrinks: Boolean,
    val noSpec: Boolean,
    /** "duel" or "sparring bout" — the noun refusal messages name. */
    val context: String,
) {
    companion object {
        /** The restrictions binding [p] right now, or null when no duel/spar is active. */
        fun of(p: Player): CombatRestrictions? {
            DuelArena.rulesOf(p)?.let { r ->
                return CombatRestrictions(
                    noMelee = r.noMelee, noRanged = r.noRanged, noMagic = r.noMagic,
                    noPrayer = r.noPrayer, noFood = r.noFood, noDrinks = r.noDrinks,
                    noSpec = r.noSpec, context = "duel",
                )
            }
            CompanionSparring.rulesOf(p)?.let { r ->
                return CombatRestrictions(
                    noMelee = r.noMelee, noRanged = r.noRanged, noMagic = r.noMagic,
                    noPrayer = r.noPrayer, noFood = r.noFood, noDrinks = r.noDrinks,
                    noSpec = r.noSpec, context = "sparring bout",
                )
            }
            return null
        }
    }
}
