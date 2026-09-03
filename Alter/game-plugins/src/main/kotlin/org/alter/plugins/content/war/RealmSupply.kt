package org.alter.plugins.content.war

import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.war.events.ServiceRecords

/**
 * **Realm Supplies** — the kingdom's shared, consumable war stockpile (design authority §8): the
 * bridge between skilling and the war, and the heart of the Mire (the swamp skilling hub).
 * Handing finished goods to a Quartermaster **contributes** to it; a commander launching a
 * Campaign or Conquest **consumes** it. Scheduled Marches, Grand Marches, Lord operations and
 * event-started public ops never touch it — the war never stalls for an empty stockpile; only
 * the commanders' biggest operations do.
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

    /**
     * Raise the stockpile by [amount]; announce when a campaign becomes affordable. With a
     * [contributor] the hand-in is also filed in their service ledger ([ServiceRecords.recordSupplies])
     * — the one call a depot / drive / quest reward needs.
     */
    fun contribute(world: World, amount: Int, contributor: Player? = null) {
        if (amount <= 0) return
        val before = meter()
        WarState.addSupplyMeter(amount)
        if (contributor != null) ServiceRecords.recordSupplies(contributor, amount)
        val campaign = CampaignTier.CAMPAIGN.supplyCost
        if (before < campaign && meter() >= campaign) {
            Announce.broadcast(world, "<col=4f9b4f>The $NAME stand at ${meter()}/${max()} — a Minister may now march a campaign!</col>")
        }
    }

    /** Drain [amount] from the stockpile because [who] did [what] (e.g. "marched a campaign on Varrock"). */
    fun consume(world: World, amount: Int, who: String, what: String) {
        if (amount <= 0) return
        WarState.addSupplyMeter(-amount)
        Announce.broadcast(world, "<col=801700>$who has $what — the $NAME fall to ${meter()}/${max()}. Supply the Mire to refill them!</col>")
    }

    /** A commander launched [tier] on [target]; drain its supply cost and announce the march. */
    fun consume(world: World, tier: CampaignTier, who: String, target: String) =
        consume(world, tier.supplyCost, who, "marched a ${tier.display} on $target")

    /** Status line for ::supply. */
    fun status(): String =
        "$NAME: <col=4f9b4f>${meter()}/${max()}</col>. A campaign needs <col=ffae00>${CampaignTier.CAMPAIGN.supplyCost}</col>, a conquest <col=ffae00>${CampaignTier.CONQUEST.supplyCost}</col>; marches and Lord operations are free. Hand finished goods to a Quartermaster (the Mire) to fill the stockpile."
}
