package org.alter.plugins.content.war

import org.alter.game.model.World
import org.alter.plugins.content.announce.Announce

/**
 * The realm's **war-supply meter** — the bridge between skilling and the war, and the heart of the
 * Mire (the swamp skilling hub). Handing finished goods to a Quartermaster **contributes** to the
 * meter; launching a campaign **consumes** it. A Lord/King can only march when the realm is stocked,
 * so the whole server is pulled into supplying the war to unlock the next (big-paying) campaign:
 *
 *   skill the Mire -> fill the meter -> a commander marches -> shared payout -> meter drains -> repeat.
 *
 * The integer itself lives in [WarState] (persistent). This owns the contribute/consume policy and
 * the server-wide broadcasts.
 */
object RealmSupply {

    fun meter(): Int = WarState.getSupplyMeter()
    fun max(): Int = WarState.supplyMeterMax()
    fun canAfford(cost: Int): Boolean = meter() >= cost

    /** A player handed in supplies (War Effort value [amount]); raise the meter and announce a full store. */
    fun contribute(world: World, amount: Int) {
        if (amount <= 0) return
        val before = meter()
        WarState.addSupplyMeter(amount)
        val campaign = CampaignTier.CAMPAIGN.supplyCost
        if (before < campaign && meter() >= campaign) {
            Announce.broadcast(world, "<col=4f9b4f>The realm's war-stores are full (${meter()}/${max()}) — a Minister may now march a campaign!</col>")
        }
    }

    /** A commander launched [tier]; drain the meter and announce the march. */
    fun consume(world: World, tier: CampaignTier, who: String, target: String) {
        WarState.addSupplyMeter(-tier.supplyCost)
        Announce.broadcast(world, "<col=801700>$who has marched a ${tier.display} on $target — the realm's war-stores fall to ${meter()}/${max()}. Supply the Mire to refill them!</col>")
    }

    /** Status line for ::supply. */
    fun status(): String =
        "Realm war-stores: <col=4f9b4f>${meter()}/${max()}</col> supplies. A campaign needs <col=ffae00>${CampaignTier.CAMPAIGN.supplyCost}</col>, a conquest <col=ffae00>${CampaignTier.CONQUEST.supplyCost}</col>. Hand finished goods to a Quartermaster (the Mire) to fill it."
}
