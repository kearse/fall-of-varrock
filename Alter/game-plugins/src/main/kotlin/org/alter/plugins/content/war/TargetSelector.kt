package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import kotlin.math.abs
import kotlin.math.max

/**
 * Tracks per-pawn velocity (tile delta/tick) so the AI can **lead/intercept** moving targets
 * instead of chasing their stale position — cut off a fleeing player's retreat, not trail it.
 * Updated once per tick by the [AttackDirector].
 */
object MovementTracker {
    private val last = HashMap<Pawn, Tile>()
    private val vel = HashMap<Pawn, IntArray>()

    fun tick(pawns: List<Pawn>) {
        val seen = HashSet<Pawn>()
        for (p in pawns) {
            seen += p
            last[p]?.let { vel[p] = intArrayOf(p.tile.x - it.x, p.tile.z - it.z) }
            last[p] = p.tile
        }
        last.keys.retainAll(seen); vel.keys.retainAll(seen)
    }

    /** Predicted tile [lead] ticks ahead along the pawn's recent heading. */
    fun predict(p: Pawn, lead: Int = 2): Tile {
        val v = vel[p] ?: return p.tile
        return Tile(p.tile.x + v[0] * lead, p.tile.z + v[1] * lead, p.tile.height)
    }
}

/**
 * **Layer 3 of the War Brain — squad target selection.** Replaces "hit the nearest
 * enemy" with a *scored* choice, so units deliberately focus the dangerous targets and
 * **coordinate kills** instead of smearing damage across the whole line (RS combat is
 * slow — spread damage kills nothing).
 *
 * ```
 * score = wProx·proximity + wThreat·threat + wSecure·lowHp
 *       + wFocus·alreadyFocused + wObj·isObjective
 * ```
 * The [Weights] come from the active **posture** (Layer 1), so the same selector yields
 * very different behaviour: a high `wThreat` makes goblins gang the highest-level player
 * first; a high `wSecure` makes a winning side finish the wounded for tempo.
 *
 * `// WAR BRAIN ROADMAP`: hand-off / assist calls, threat decay over time, kite/LoS
 * scoring for ranged & mage roles, and baiting all hang off this scoring function.
 */
object TargetSelector {

    /** Per-posture scoring weights. All integer to keep the hot loop allocation-free. */
    data class Weights(
        val wProx: Int = 3,
        val wThreat: Int = 2,
        val wSecure: Int = 1,
        val wFocus: Int = 4,
        val wObj: Int = 50,
    ) {
        companion object {
            /** Gang the strongest enemy and pile on — "target high-levels first". */
            val ELIMINATE = Weights(wProx = 2, wThreat = 6, wSecure = 1, wFocus = 5, wObj = 60)
            /** Finish the wounded fast — used when a side wants tempo / to thin numbers. */
            val SECURE = Weights(wProx = 3, wThreat = 1, wSecure = 6, wFocus = 5, wObj = 60)
            /** Balanced line-holding. */
            val BALANCED = Weights()
        }
    }

    /** No more than this many allies are rewarded for stacking one target (avoid overkill). */
    private const val FOCUS_CAP = 4
    private const val PROX_RANGE = 32

    /**
     * Pick the best target for [unit] among [candidates] (already filtered to those in
     * engage range and alive). [focusCount] maps a target to how many allies are already
     * locked onto it (for focus-fire coordination); [objective] is the side's VIP
     * (e.g. the Warlord) and gets the [Weights.wObj] spike. Returns null if no candidate.
     */
    fun choose(
        unit: Npc,
        candidates: List<Pawn>,
        focusCount: Map<Pawn, Int>,
        objective: Pawn?,
        w: Weights,
    ): Pawn? {
        var best: Pawn? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            // Aim at where the target is HEADING (intercept), not where it was.
            val pred = MovementTracker.predict(c)
            val d = max(abs(unit.tile.x - pred.x), abs(unit.tile.z - pred.z))
            val prox = max(0, PROX_RANGE - d)
            val threat = threatOf(c)
            val lowHp = lowHpBonus(c)
            val focus = (focusCount[c] ?: 0).coerceAtMost(FOCUS_CAP)
            val obj = if (objective != null && c === objective) 1 else 0
            val score = w.wProx * prox + w.wThreat * threat + w.wSecure * lowHp +
                w.wFocus * focus + w.wObj * obj
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /**
     * A unit's danger score. Players are rated by **combat level** (so the horde hunts
     * the strongest first); NPC combatants by a fraction of their max HP (so elites and
     * the Warlord outweigh rabble).
     *
     * `// WAR BRAIN ROADMAP`: fold in equipment bonuses, prayers, and recent damage output.
     */
    fun threatOf(pawn: Pawn): Int = when (pawn) {
        is Player -> pawn.combatLevel
        is Npc -> max(1, pawn.getMaxHp() / 4)
        else -> 1
    }

    /** 0..100 — how wounded the target is (100 = nearly dead), to secure kills. */
    private fun lowHpBonus(pawn: Pawn): Int {
        val max = pawn.getMaxHp()
        if (max <= 0) return 0
        return (100 - pawn.getCurrentHp() * 100 / max).coerceIn(0, 100)
    }

    private fun dist(a: Pawn, b: Pawn): Int = max(abs(a.tile.x - b.tile.x), abs(a.tile.z - b.tile.z))
}
