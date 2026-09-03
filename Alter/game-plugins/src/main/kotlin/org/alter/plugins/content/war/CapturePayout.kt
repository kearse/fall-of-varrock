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
 *  - **Commander cut** — the sponsor who funded + led the op takes their war-stake back plus a [TITHE]
 *    off the top of the pool, and (rank-gated) may earn a 3rd age relic. This does NOT auto-bank: it's
 *    held in the [WarSpoils] claim box and collected with `::claim` (the commander is toasted).
 *  - **Participants** — split the rest by contribution share; auto-banked as gp (the sponsor too, as a fighter).
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
        // A won war's COMMANDER cut no longer auto-banks — it accrues here and is collected with
        // `::claim` (see [WarSpoils]). It starts with the war-stake, returned on a WIN (forfeit only on
        // a loss), and the commander's tithe is added below once the pool is known. The rank-and-file
        // contribution split still auto-banks for everyone (the sponsor included, as a fighter).
        var sponsorSpoils = 0L
        if (sponsor != null && sponsor.index >= 0 && tier.cost > 0) sponsorSpoils += tier.cost.toLong()

        // Leadership relic ([ThirdAge]): a Minister/King who WON has a small flat chance to earn a 3rd
        // age piece, added to their spoils on top of the coin. Rank-gated, independent of the pool, so
        // the roll happens now and rides along whichever payout branch fires below.
        val relic = sponsor != null && sponsor.index >= 0 &&
            sponsor.title.ordinal >= Title.MINISTER.ordinal && ThirdAge.rollLeaderGift()

        val contrib = participation.filterKeys { it.index >= 0 && !it.isDead() }
        if (contrib.isEmpty()) {
            if (sponsor != null && sponsor.index >= 0) {
                WarSpoils.deliverCommanderLoot(world, sponsor, op.displayName, sponsorSpoils, relic)
                sponsor.message("<col=801700>${op.displayName} fell, but no soldier of yours stood to claim the field spoils.</col>")
            }
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
            if (sponsor != null && sponsor.index >= 0) WarSpoils.deliverCommanderLoot(world, sponsor, op.displayName, sponsorSpoils, relic)
            contrib.keys.forEach { it.message("<col=801700>The field at ${op.displayName} is won — but its coffers were bare.</col>") }
            return
        }

        val total = contrib.values.sum().coerceAtLeast(1)
        val tithe = if (sponsor != null && sponsor.index >= 0) (pool * TITHE).toLong().coerceAtLeast(0) else 0
        if (tithe > 0) sponsorSpoils += tithe
        val split = pool - tithe

        // The commander's cut (stake + tithe + any relic) → the ::claim box, not the bank ([WarSpoils]).
        if (sponsor != null && sponsor.index >= 0) WarSpoils.deliverCommanderLoot(world, sponsor, op.displayName, sponsorSpoils, relic)

        contrib.forEach { (player, score) ->
            val coins = (split * score / total).coerceAtLeast(1)
            bankCoins(world, player, coins)
            player.addPoints(PointKind.WAR_EFFORT, tier.prestige) // war effort for the rank-and-file
            val pct = (score * 100 / total).coerceAtLeast(1)
            player.message("<col=4f9b4f>The field at ${op.displayName} is won! Your $pct% share of the spoils: ${fmt(coins)} coins, banked.</col>")

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
