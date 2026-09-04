package org.alter.plugins.content.combat

import org.alter.game.model.entity.Player
import org.alter.plugins.content.minigames.duel.DuelArena

/**
 * The combat-restriction view over the rule-bound bout a player is in — today an active **duel**
 * ([DuelArena]). Every enforcement site (eating, potions, prayers, style bans, special attacks)
 * asks ONE question here instead of consulting the duel registry directly, so a future rule-bound
 * activity (a sparring bout, a tournament format) plugs in by adding a branch to [of] — see
 * docs/duel-arena-implementation-plan.md, "Companion sparring — parity & reuse".
 *
 * Restrictions that exist only for duels (forfeit, weapon lock, fun weapons, gear slots) are
 * enforced at their own sites and are not part of this view.
 */
class CombatRestrictions private constructor(
    val noMelee: Boolean,
    val noRanged: Boolean,
    val noMagic: Boolean,
    val noPrayer: Boolean,
    val noFood: Boolean,
    val noDrinks: Boolean,
    val noSpec: Boolean,
    /** The noun refusal messages name — "duel". */
    val context: String,
) {
    companion object {
        /** The restrictions binding [p] right now, or null when no rule-bound bout is active. */
        fun of(p: Player): CombatRestrictions? {
            DuelArena.rulesOf(p)?.let { r ->
                return CombatRestrictions(
                    noMelee = r.noMelee, noRanged = r.noRanged, noMagic = r.noMagic,
                    noPrayer = r.noPrayer, noFood = r.noFood, noDrinks = r.noDrinks,
                    noSpec = r.noSpec, context = "duel",
                )
            }
            return null
        }
    }
}
