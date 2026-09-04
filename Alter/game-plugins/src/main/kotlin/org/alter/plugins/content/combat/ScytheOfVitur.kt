package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.isPlayerAttackable
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs

/**
 * **Scythe of vitur** multi-hit (OSRS Wiki): every swing sweeps a 1×3 arc in front of the wielder.
 *  - Against a LARGE target (2×2+) the same target takes up to three hits — the second at half the
 *    max hit, the third at a quarter — each with its own accuracy roll; 3+ tiles get all three,
 *    2×2 gets two.
 *  - In multi-combat the arc also strikes up to two OTHER attackable npcs standing on the tiles
 *    beside the primary target (100% / 50% / 25% in order).
 * The melee strategy dealt exactly one hit for every weapon before 2026-09-03 ("scythe does not
 * hit like OSRS 1x3"). Every extra hit runs the normal on-hit layer (poison, recoil, blood fury).
 */
object ScytheOfVitur {

    private val SCYTHES: Set<Int> = listOf(
        "item.scythe_of_vitur", "item.scythe_of_vitur_22664",
        "item.holy_scythe_of_vitur", "item.sanguine_scythe_of_vitur",
    ).mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    /** Damage fraction of hits 2 and 3, in percent. */
    private val FOLLOW_UP_PCT = intArrayOf(50, 25)

    fun isWielding(player: Player): Boolean = player.getEquipment(EquipmentType.WEAPON)?.id in SCYTHES

    /**
     * Called by the melee strategy right after the primary hit with the max hit it rolled against.
     * [maxHit] is the primary hit's max (all multipliers applied); follow-ups derive from it.
     */
    fun extraHits(pawn: Pawn, target: Pawn, maxHit: Int, world: World) {
        if (pawn !is Player || !isWielding(pawn)) return
        val formula = MeleeCombatFormula

        // Same target, large monsters: 2×2 → one follow-up, 3×3+ → two.
        val size = target.getSize()
        val followUps = when {
            size >= 3 -> 2
            size == 2 -> 1
            else -> 0
        }
        repeat(followUps) { i ->
            swing(pawn, target, maxHit * FOLLOW_UP_PCT[i] / 100, formula, world)
        }

        // The 1×3 arc onto bystanders — multi-combat only, npcs only, never a player.
        if (followUps == 0 && PvpZones.isMultiCombat(pawn.tile, world)) {
            val arc = arcTiles(pawn, target)
            var slot = 0
            world.npcs.forEach { npc ->
                if (slot >= FOLLOW_UP_PCT.size || npc == null || npc === target || npc.index < 0) return@forEach
                if (npc.tile.height != pawn.tile.height) return@forEach
                if (abs(npc.tile.x - pawn.tile.x) > 2 || abs(npc.tile.z - pawn.tile.z) > 2) return@forEach
                if (npc.isDead() || !npc.isPlayerAttackable() || npc.combatDef.hitpoints == -1) return@forEach
                if (arc.none { t -> Combat.areOverlapping(t.x, t.z, 1, 1, npc.tile.x, npc.tile.z, npc.getSize(), npc.getSize()) }) return@forEach
                if (!Combat.canEngage(pawn, npc, quiet = true)) return@forEach
                swing(pawn, npc, maxHit * FOLLOW_UP_PCT[slot] / 100, formula, world)
                slot++
            }
        }
    }

    private fun swing(pawn: Player, victim: Pawn, max: Int, formula: MeleeCombatFormula, world: World) {
        if (max <= 0) return
        val landed = formula.getAccuracy(pawn, victim) >= world.randomDouble()
        pawn.dealHit(target = victim, maxHit = max, landHit = landed, delay = 0) {
            WeaponEffects.applyOnHit(pawn, victim, it, combatClass = CombatClass.MELEE)
        }
    }

    /** The three tiles of the arc: the target's tile plus the two beside it, perpendicular to the swing. */
    private fun arcTiles(pawn: Player, target: Pawn): List<Tile> {
        val centre = target.tile
        val dx = centre.x - pawn.tile.x
        val dz = centre.z - pawn.tile.z
        // Swinging north/south → the arc runs east-west; swinging east/west → north-south.
        return if (abs(dz) >= abs(dx)) {
            listOf(centre, centre.transform(-1, 0), centre.transform(1, 0))
        } else {
            listOf(centre, centre.transform(0, -1), centre.transform(0, 1))
        }
    }
}
