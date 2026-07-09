package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.World
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Layer 2 of the War Brain — allocation.** A commander divides its side's *live finite
 * supply* across the city's [fields] each tick by a [doctrine], writing each field's
 * per-side troop target; the [WarFront] engine then spawns toward that target from the
 * shared reserve (goblins) / pool (knights) at a capped rate.
 *
 * Strategy (which field to concentrate on, the posture) is decided one layer up by the
 * [AttackDirector] / [WarBrain] and handed down via [focusIndex] + the `concentrate`
 * flag — so this class is pure allocation math:
 *
 * `target = baseGarrison + reserve × weight / Σweight`, smoothed [MAX_STEP]/tick.
 *
 * Doctrine: knights ([Doctrine.REINFORCE_THREAT]) mass where the goblins are; goblins
 * ([Doctrine.EXPLOIT_GAP]) flood the least-defended field. Both sides mildly avoid
 * player-crowded fields (the AI "reads" where humans are). A disrupted goblin commander
 * (Warlord killed) allocates leaderless on a slashed budget.
 *
 * `// WAR BRAIN ROADMAP`: reinforcement-timing (hold a reserve for a counter-push),
 * feint allocations, and multi-front coordinated maneuvers live here.
 */
class Commander(
    private val frontId: String,
    private val fields: List<WarFront>,
    private val side: Side,
    private val doctrine: Doctrine,
    private val baseGarrison: Int,
) {
    enum class Side { ATTACKER, DEFENDER }
    enum class Doctrine { REINFORCE_THREAT, EXPLOIT_GAP }

    /** The field the strategy layer wants concentrated (thrust / guard / hunt). */
    var focusIndex: Int = 0

    private var disruptedTicksLeft = 0

    /** Cripple this commander (Warlord killed): budget slashed + leaderless for [ticks]. */
    fun disrupt(ticks: Int) { disruptedTicksLeft = ticks }
    fun isDisrupted(): Boolean = disruptedTicksLeft > 0

    fun reset() {
        disruptedTicksLeft = 0
        focusIndex = 0
        fields.forEach { it.eliteTarget = 0 }
    }

    /**
     * Allocate [budget] troops across the fields. [concentrate] applies [concentrateMult]
     * to the [focusIndex] field; [seedElites] seeds shock troops there (attacker only).
     */
    fun allocate(
        world: World,
        a: BattleAssessment,
        budget: Int,
        concentrate: Boolean,
        concentrateMult: Int,
        seedElites: Boolean,
    ) {
        try {
            if (fields.isEmpty()) return
            val disrupted = side == Side.ATTACKER && disruptedTicksLeft > 0
            if (disruptedTicksLeft > 0) disruptedTicksLeft--
            val effBudget = if (disrupted) budget * DISRUPT_BUDGET_PCT / 100 else budget

            val weights = IntArray(fields.size) { i ->
                (max(0, doctrineWeight(a, i)) + 1) * playerFactor(a.fieldViews[i].playerCount)
            }

            val conc = concentrate && !disrupted && focusIndex in fields.indices
            if (conc) weights[focusIndex] = weights[focusIndex] * concentrateMult

            fields.forEachIndexed { i, f ->
                f.eliteTarget = if (conc && seedElites && i == focusIndex) ELITE_COUNT else 0
            }

            val reserve = (effBudget - baseGarrison * fields.size).coerceAtLeast(0)
            val total = weights.sum()
            fields.forEachIndexed { i, f ->
                val share = if (total > 0) reserve * weights[i] / total else reserve / fields.size
                setTarget(f, stepToward(currentTarget(f), baseGarrison + share, MAX_STEP))
            }
        } catch (e: Exception) {
            logger.error(e) { "Commander ($side/$doctrine) allocate failed for '$frontId'" }
        }
    }

    /** Doctrine value (always usable as a weight after coercion). */
    private fun doctrineWeight(a: BattleAssessment, i: Int): Int = when (doctrine) {
        Doctrine.REINFORCE_THREAT -> a.fieldViews[i].goblins // mass where the goblins are
        Doctrine.EXPLOIT_GAP -> {
            val maxDef = a.fieldViews.maxOf { it.knights }
            maxDef - a.fieldViews[i].knights // least-defended field weighs most
        }
    }

    /** Counter-player: a field with more real players is a little less attractive to both
     *  sides (goblins probe the gaps; knights cover where humans aren't). */
    private fun playerFactor(players: Int): Int = (PLAYER_BASE - players).coerceAtLeast(1)

    private fun currentTarget(field: WarFront): Int =
        if (side == Side.ATTACKER) field.attackerTarget else field.defenderTarget

    private fun setTarget(field: WarFront, value: Int) {
        if (side == Side.ATTACKER) field.attackerTarget = value else field.defenderTarget = value
    }

    private fun stepToward(current: Int, desired: Int, step: Int): Int =
        current + (desired - current).coerceIn(-step, step)

    private companion object {
        const val MAX_STEP = 6 // a field's target moves at most this much per tick (anti-oscillation)
        const val ELITE_COUNT = 8 // shock-troop hobgoblins seeded into the focus field
        const val PLAYER_BASE = 3 // counter-player: factor = max(1, BASE - playersInField)
        const val DISRUPT_BUDGET_PCT = 40 // goblin budget while the Warlord is dead
    }
}
