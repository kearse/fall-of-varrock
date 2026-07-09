package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player

/**
 * **Layer 0 of the War Brain — perception.** A read-only snapshot of the whole
 * battlefield, rebuilt each AI tick and handed to every layer above (the single
 * "live data" feed). Keeping all the world reads in one place means the strategy,
 * allocation and targeting layers all reason over the *same* consistent picture.
 *
 * `// WAR BRAIN ROADMAP`: this is where richer perception plugs in later — per-player
 * equipment/prayer threat (not just combat level), damage-dealt history, supply lines,
 * and line-of-sight maps for ranged/mage units.
 */
class BattleAssessment(
    world: World,
    fields: List<WarFront>,
    private val goalTile: Tile,
    breachDist: Int,
    warlordNpc: Npc?,
) {
    /** One field's live picture. */
    class FieldView(
        val index: Int,
        val name: String,
        val goblins: Int,
        val knights: Int,
        val players: List<Player>,
        /** Σ of present players' combat levels — the field's human threat. */
        val playerPower: Int,
        /** Chebyshev distance of the closest goblin to the castle (how far they've pushed). */
        val frontDist: Int,
    ) {
        val playerCount: Int get() = players.size
    }

    val fieldViews: List<FieldView> = fields.mapIndexed { i, f ->
        val players = f.playersIn(world)
        FieldView(
            index = i,
            name = f.name,
            goblins = f.aliveAttackerCount(world),
            knights = f.aliveDefenderCount(world),
            players = players,
            playerPower = players.sumOf { it.combatLevel },
            frontDist = f.closestAttackerDist(world, goalTile),
        )
    }

    val totalGoblinsAlive: Int = fieldViews.sumOf { it.goblins }
    val totalKnightsAlive: Int = fieldViews.sumOf { it.knights }
    val totalPlayers: Int = fieldViews.sumOf { it.playerCount }

    /** The field whose goblins are closest to the castle (the spearhead). */
    val spearheadField: FieldView? = fieldViews.minByOrNull { it.frontDist }

    /** True when goblins have reached the castle / General Zo — a breach. */
    val breaching: Boolean = (spearheadField?.frontDist ?: Int.MAX_VALUE) <= breachDist

    val warlordAlive: Boolean = warlordNpc != null && !warlordNpc.isDead() && warlordNpc.index >= 0
    val warlordHpPct: Int =
        if (warlordAlive && warlordNpc != null && warlordNpc.getMaxHp() > 0)
            warlordNpc.getCurrentHp() * 100 / warlordNpc.getMaxHp()
        else 0

    /** Who is winning, crudely: positive = defenders ahead, negative = goblins ahead. */
    val defenderEdge: Int = totalKnightsAlive - totalGoblinsAlive
}
