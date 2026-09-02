package org.alter.plugins.content.war

import org.alter.game.model.World
import org.alter.plugins.content.announce.Announce

/**
 * **Realm Supplies** — the kingdom's shared, consumable war stockpile (design authority §8): the
 * bridge between skilling and the war, and the heart of the Mire (the swamp skilling hub).
 * Handing finished goods to a Quartermaster **contributes** to it; a commander launching a
 * Campaign or Conquest **consumes** it. Scheduled Marches, Grand Marches and Lord operations
 * never touch it — the war never stalls for an empty stockpile; only the biggest operations do.
 *
 *   skill the Mire -> fill the stockpile -> a commander marches -> shared payout -> stockpile drains -> repeat.
 *
 * Realm Supplies are NOT the player's War Effort: War Effort is a personal lifetime service
 * record ([org.alter.plugins.content.economy.PointKind.WAR_EFFORT]); the same hand-in raises
 * both, but only the stockpile is ever spent. The integer itself lives in [WarState]
 * (persistent). This owns the contribute/consume policy and the server-wide broadcasts.
 */
object RealmSupply {

    /** The player-facing name of the stockpile. */
    const val NAME = "Realm Supplies"

    fun meter(): Int = WarState.getSupplyMeter()
    fun max(): Int = WarState.supplyMeterMax()
    fun canAfford(cost: Int): Boolean = meter() >= cost

    /** A player handed in supplies (worth [amount]); raise the stockpile and announce a full store. */
    fun contribute(world: World, amount: Int) {
        if (amount <= 0) return
        val before = meter()
        WarState.addSupplyMeter(amount)
        val campaign = CampaignTier.CAMPAIGN.supplyCost
        if (before < campaign && meter() >= campaign) {
            Announce.broadcast(world, "<col=4f9b4f>The $NAME stand at ${meter()}/${max()} — a Minister may now march a campaign!</col>")
        }
    }

    /** A commander launched [tier]; drain the stockpile and announce the march. */
    fun consume(world: World, tier: CampaignTier, who: String, target: String) {
        if (tier.supplyCost <= 0) return
        WarState.addSupplyMeter(-tier.supplyCost)
        Announce.broadcast(world, "<col=801700>$who has marched a ${tier.display} on $target — the $NAME fall to ${meter()}/${max()}. Supply the Mire to refill them!</col>")
    }

    /** Status line for ::supply. */
    fun status(): String =
        "$NAME: <col=4f9b4f>${meter()}/${max()}</col>. A campaign needs <col=ffae00>${CampaignTier.CAMPAIGN.supplyCost}</col>, a conquest <col=ffae00>${CampaignTier.CONQUEST.supplyCost}</col>; marches and Lord operations are free. Hand finished goods to a Quartermaster (the Mire) to fill the stockpile."
}
