package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Tracks how much each player contributed to the current raid on a front, so a winning
 * defense can be rewarded fairly. Decoupled global tracker: [WarEffortPlugin] records
 * kills here as they happen; the [AttackDirector] [drain]s it when the raid resolves.
 */
object WarParticipation {
    private val byFront = HashMap<String, HashMap<Player, Int>>()

    /** Credit [player] with [points] of contribution on [frontId]. */
    fun record(frontId: String, player: Player, points: Int) {
        if (points <= 0) return
        byFront.getOrPut(frontId) { HashMap() }.merge(player, points, Int::plus)
    }

    /** Take and clear the contribution map for [frontId] (called when the raid ends). */
    fun drain(frontId: String): Map<Player, Int> {
        val map = byFront.remove(frontId) ?: return emptyMap()
        return map
    }

    fun clear(frontId: String) { byFront.remove(frontId) }
}

/**
 * Rare-item rewards for **winning the defense** (routing the Warlord / wiping the
 * roster). Every contributor gets a shot at the rare table; the **MVP** (top
 * contributor — typically the Warlord-killer, who is credited heavily) gets a
 * guaranteed rare. Loot drops to the ground owned by the player (RS-style).
 *
 * The table is intentionally simple to TUNE — unknown item ids are skipped gracefully
 * so this never crashes a raid resolution if an id isn't in the cache yet.
 */
object RaidRewards {
    /** Contribution that makes a player eligible for a reward roll. */
    private const val ELIGIBLE_THRESHOLD = 3
    /** ~1 in N eligible contributors gets a rare (besides the guaranteed MVP). */
    private const val RARE_ONE_IN = 4

    private val coinId by lazy { getRSCM("item.coins_995") }

    /** Curated "very nice" rare table (TUNE). Resolved lazily + guarded for missing ids. */
    private val RARE_IDS: List<Int> by lazy {
        listOf(
            "item.dragon_scimitar",
            "item.dragon_dagger",
            "item.dragon_med_helm",
            "item.rune_platebody",
            "item.rune_kiteshield",
            "item.abyssal_whip",
        ).mapNotNull { name -> runCatching { getRSCM(name) }.getOrNull() }
    }

    /**
     * Hand out rewards for a won defense. [participation] is the drained contribution map.
     */
    fun award(world: World, participation: Map<Player, Int>, displayName: String) {
        if (participation.isEmpty()) return
        val mvp = participation.maxByOrNull { it.value }?.key

        participation.forEach { (player, score) ->
            if (score < ELIGIBLE_THRESHOLD) return@forEach
            // Everyone who helped gets a coin bounty.
            val coins = (200 + score * 15).coerceAtMost(50_000)
            world.spawn(GroundItem(coinId, coins, player.tile, player))
            player.message("<col=4f9b4f>$displayName is saved! Your war effort earns ${"%,d".format(coins)} coins.</col>")

            val guaranteed = player === mvp
            if (guaranteed || world.random(RARE_ONE_IN - 1) == 0) {
                grantRare(world, player, guaranteed)
            }
        }
    }

    private fun grantRare(world: World, player: Player, guaranteed: Boolean) {
        val id = RARE_IDS.randomOrNull() ?: return
        world.spawn(GroundItem(id, 1, player.tile, player))
        if (guaranteed) {
            player.message("<col=ffcc00>For routing the horde you are awarded a RARE prize of war!</col>")
        } else {
            player.message("<col=ffcc00>RARE WAR SPOILS! A prize falls at your feet.</col>")
        }
    }
}
