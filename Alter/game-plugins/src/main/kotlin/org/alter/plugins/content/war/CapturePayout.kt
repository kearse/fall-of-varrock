package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.isDonor
import org.alter.rscm.RSCM.getRSCM

/**
 * Splits the **war-chest pool** of a won campaign/conquest — the aggregate gp-value of everything the
 * enemy city dropped, pooled by [CampaignDirector] instead of hitting the floor — among the players
 * who fought, by contribution (time-in-combat in the battle area). The payout is **auto-banked**, so
 * a raid ends with coin in your bank rather than a scramble on the ground.
 *
 * The slice model (master design brief §3C, see memory `rsps-campaign-loot-model`):
 *  - **Commander tithe** — the sponsor who funded + led the op takes [TITHE] off the top of the pool.
 *  - **Participants** — split the rest by contribution share; auto-banked as gp.
 *  - **Donor bonus** — every donor PARTICIPANT also gets [DONOR_CUT] of the pool's value, paid in
 *    **donor points** (NOT gp). Minted alongside, never out of the pool: cash-side flair can't skim
 *    the players' earned loot, and donor points don't inflate the gp economy. No cap — even a few
 *    hundred donors cost the money supply nothing.
 *
 * (A single boss raid splits by precise damage instead — see boss/BossLoot. The King/Minister
 * passive nobility cut from the locked model is a full-realm-politics follow-up, not in the slice.)
 */
object CapturePayout {
    private const val TITHE = 0.10           // commander's cut, out of the pool
    private const val DONOR_CUT = 0.01       // a donor participant's bonus, minted in donor points
    private const val DONOR_POINT_RATE = 100 // gp-value per 1 donor point (so a 1m pool → ~100 points)
    private val coinId by lazy { getRSCM("item.coins_995") }

    fun award(
        world: World,
        op: CampaignOp,
        tier: CampaignTier,
        participation: Map<Player, Int>,
        /** The commanding Lord — or null for a realm-sponsored MARCH (no stake, no tithe). */
        sponsor: Player?,
        lootPool: Long,
    ) {
        // The commander recoups their war-stake on a WIN — they only forfeit it if the campaign fails.
        // Banked on top of their share, so a successful campaign returns the cost plus profit.
        if (sponsor != null && sponsor.index >= 0 && tier.cost > 0) {
            bankCoins(world, sponsor, tier.cost.toLong())
            sponsor.message("<col=ffae00>${op.displayName} is taken — your ${fmt(tier.cost.toLong())} coin war-stake is returned.</col>")
        }

        val contrib = participation.filterKeys { it.index >= 0 && !it.isDead() }
        if (contrib.isEmpty()) {
            if (sponsor != null && sponsor.index >= 0) sponsor.message("<col=801700>${op.displayName} fell, but no soldier of yours stood to claim the spoils.</col>")
            return
        }

        // Commendations — the war-forging service token: paid on ANY won op (even with bare
        // coffers), contribution-scaled up to the tier's cap. Untradeable by design.
        if (tier.commendMax > 0) {
            val totalScore = contrib.values.sum().coerceAtLeast(1)
            contrib.forEach { (player, score) ->
                val n = (1 + (tier.commendMax - 1) * score / totalScore).coerceIn(1, tier.commendMax)
                org.alter.plugins.content.war.forge.WarForge.awardCommendations(player, n)
            }
        }

        val pool = lootPool.coerceAtLeast(0)
        if (pool <= 0) {
            contrib.keys.forEach { it.message("<col=801700>${op.displayName} is taken — but its coffers were bare.</col>") }
            return
        }

        val total = contrib.values.sum().coerceAtLeast(1)
        val tithe = if (sponsor != null && sponsor.index >= 0) (pool * TITHE).toLong().coerceAtLeast(0) else 0
        if (sponsor != null && tithe > 0) {
            bankCoins(world, sponsor, tithe)
            sponsor.message("<col=ffae00>Your commander's tithe from ${op.displayName}: ${fmt(tithe)} coins, banked.</col>")
        }
        val split = pool - tithe

        contrib.forEach { (player, score) ->
            val coins = (split * score / total).coerceAtLeast(1)
            bankCoins(world, player, coins)
            player.addPoints(PointKind.WAR_EFFORT, tier.prestige) // war effort for the rank-and-file
            val pct = (score * 100 / total).coerceAtLeast(1)
            player.message("<col=4f9b4f>${op.displayName} is seized! Your $pct% share of the spoils: ${fmt(coins)} coins, banked.</col>")

            // Donor perk: +1% of the pool's VALUE, paid in donor points (minted, never from the pool).
            if (player.isDonor) {
                val points = ((pool * DONOR_CUT) / DONOR_POINT_RATE).toInt().coerceAtLeast(1)
                player.addPoints(PointKind.DONOR, points)
                player.message("<col=ff66ff>Donor bonus: +${fmt(points.toLong())} donor points from the war-chest.</col>")
            }
        }
    }

    /** Bank [amount] coins; if the bank can't take them (no slot + no existing stack), drop at the feet. */
    private fun bankCoins(world: World, player: Player, amount: Long) {
        if (amount <= 0) return
        val n = amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val ok = runCatching { player.bank.add(coinId, n).completed > 0 }.getOrDefault(false)
        if (!ok) world.spawn(GroundItem(coinId, n, player.tile, player))
    }

    private fun fmt(n: Long): String = "%,d".format(n)
}
