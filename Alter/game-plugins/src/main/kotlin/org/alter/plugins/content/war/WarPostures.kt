package org.alter.plugins.content.war

/**
 * **Layer 1 of the War Brain — strategy / posture.** Each side reads the live
 * [BattleAssessment] and picks an overall stance for the tick. The posture then drives
 * BOTH the allocation weighting (Layer 2, via the focus field the [AttackDirector] sets)
 * AND the per-unit target weights (Layer 3, [TargetSelector.Weights]).
 *
 * Kept deliberately simple (cheap rules) for the first pass.
 * `// WAR BRAIN ROADMAP`: replace these rules with a richer policy — multi-step plans,
 * deception/baiting, supply-timing, and morale-driven retreat/regroup.
 */
enum class GoblinPosture {
    /** Ahead or even — concentrate on the weakest field and drive for the castle. */
    THRUST,
    /** Behind — spread out, bleed the knights and conserve the roster. */
    PROBE,
    /** The Warlord is hurt — mass around it (losing the Warlord routs the horde). */
    RALLY,
}

enum class KnightPosture {
    /** Meet the line where the goblins are. */
    HOLD,
    /** Peel a squad to kill the Warlord (its death routs the horde). */
    HUNT,
    /** Goblins are near General Zo — collapse back to defend the castle. */
    GUARD_ZO,
}

object WarBrain {
    /** Within this distance of the castle the knights drop everything to guard Zo. */
    private const val GUARD_DIST = 9

    fun goblinPosture(a: BattleAssessment): GoblinPosture = when {
        a.warlordAlive && a.warlordHpPct in 1..45 -> GoblinPosture.RALLY
        a.defenderEdge <= 0 -> GoblinPosture.THRUST // goblins ahead/even on bodies -> push
        else -> GoblinPosture.PROBE
    }

    fun knightPosture(a: BattleAssessment): KnightPosture = when {
        a.breaching || (a.spearheadField?.frontDist ?: Int.MAX_VALUE) <= GUARD_DIST -> KnightPosture.GUARD_ZO
        a.warlordAlive && a.totalKnightsAlive >= a.totalGoblinsAlive -> KnightPosture.HUNT
        else -> KnightPosture.HOLD
    }

    fun goblinWeights(p: GoblinPosture): TargetSelector.Weights = when (p) {
        GoblinPosture.THRUST -> TargetSelector.Weights.ELIMINATE
        GoblinPosture.PROBE -> TargetSelector.Weights.SECURE
        GoblinPosture.RALLY -> TargetSelector.Weights.BALANCED
    }

    fun knightWeights(p: KnightPosture): TargetSelector.Weights = when (p) {
        KnightPosture.HUNT -> TargetSelector.Weights.ELIMINATE
        KnightPosture.GUARD_ZO -> TargetSelector.Weights.SECURE
        KnightPosture.HOLD -> TargetSelector.Weights.BALANCED
    }
}
