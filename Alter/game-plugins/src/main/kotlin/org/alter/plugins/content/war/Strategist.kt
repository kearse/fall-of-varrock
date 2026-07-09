package org.alter.plugins.content.war

import org.alter.game.model.Area

/**
 * The **strategy seam** (War Brain Layer 1, made pluggable). A [Strategist] turns the live
 * [BattleAssessment] (+ persisted [WarMemory] + the [TacticalMap]) into a high-level
 * [StrategicIntent] — postures and which field each side concentrates on. The
 * [AttackDirector] executes the intent; it doesn't decide strategy itself.
 *
 * This is the deliberate hook for an **LLM commander**: swap [HeuristicStrategist] for an
 * implementation that, on a slow async cadence, asks a model for orders (see
 * [ClaudeStrategist]). The interface is intent-only, so the model never touches the per-tick
 * hot loop — it just sets the plan the deterministic layers carry out.
 */
interface Strategist {
    fun decide(a: BattleAssessment, ctx: StrategistContext): StrategicIntent
}

/** Everything a strategist needs beyond the assessment. */
class StrategistContext(
    val frontId: String,
    val fieldNames: List<String>,
    val fieldZones: List<Area>,
    val warlordFieldIndex: Int,
    val tactical: TacticalMap?,
    /** Live supply scalars (for the LLM brief). */
    val knightPool: Int = 0,
    val goblinReserve: Int = 0,
    val tierName: String = "",
)

/** The plan for this tick: stance + the field each side concentrates on. */
data class StrategicIntent(
    val goblinPosture: GoblinPosture,
    val knightPosture: KnightPosture,
    val goblinFocus: Int,
    val knightFocus: Int,
)

/**
 * The shipped strategist: fast, deterministic, and readable. Postures from [WarBrain];
 * goblins concentrate on the field that is **least defended now AND historically neglected**
 * ([WarMemory] — the learned feint); knights guard Zo / hunt the Warlord / cover the mass,
 * preferring the highest **player-threat** field from the [TacticalMap] when holding.
 */
class HeuristicStrategist : Strategist {
    override fun decide(a: BattleAssessment, ctx: StrategistContext): StrategicIntent {
        val gPosture = WarBrain.goblinPosture(a)
        val kPosture = WarBrain.knightPosture(a)

        val gFocus = when (gPosture) {
            GoblinPosture.RALLY -> ctx.warlordFieldIndex
            else -> softestField(a, ctx)
        }

        val kFocus = when (kPosture) {
            KnightPosture.GUARD_ZO -> a.spearheadField?.index ?: 0
            KnightPosture.HUNT -> ctx.warlordFieldIndex
            KnightPosture.HOLD -> hottestField(a, ctx)
        }

        return StrategicIntent(gPosture, kPosture, gFocus.coerceIn(0, a.fieldViews.lastIndex), kFocus.coerceIn(0, a.fieldViews.lastIndex))
    }

    /** Least-defended field, breaking ties toward where players historically don't show. */
    private fun softestField(a: BattleAssessment, ctx: StrategistContext): Int {
        return a.fieldViews.indices.minByOrNull { i ->
            val knights = a.fieldViews[i].knights
            val learned = WarMemory.presenceScore(ctx.frontId, ctx.fieldNames.getOrElse(i) { "" }) / 100
            knights * 3 + learned // weight live defense over learned habit
        } ?: 0
    }

    /** Field with the most player threat (knights cover where the danger/humans are thin? —
     *  no: HOLD reinforces where goblins mass; we add threat as a secondary pull). */
    private fun hottestField(a: BattleAssessment, ctx: StrategistContext): Int {
        return a.fieldViews.indices.maxByOrNull { i ->
            val goblins = a.fieldViews[i].goblins
            val threat = ctx.tactical?.let { tm ->
                ctx.fieldZones.getOrNull(i)?.let { z -> tm.averageThreat(z.bottomLeftX, z.bottomLeftY, z.topRightX, z.topRightY) }
            } ?: 0
            goblins * 4 + threat
        } ?: 0
    }
}

// The live LLM commander lives in LlmStrategist.kt ([LlmStrategist] + [WarLlm]).
