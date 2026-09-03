package org.alter.plugins.content.war

/**
 * The five public war tiers of the design authority (03 Ranks, War & Core Systems §2), as one
 * enum other teams can name: **who may start it, what it costs, what it consumes**. Each maps onto
 * the engine's [CampaignTier] (troop count, quota, reward pool) — a [LORD_OPERATION] and a [MARCH]
 * share the MARCH-tier column and differ only in having a sponsor.
 *
 * | type | starts it | coins | Realm Supplies |
 * |---|---|---|---|
 * | [MARCH] / [GRAND_MARCH] | the realm (scheduled) or a story event — never a player | free | none |
 * | [LORD_OPERATION] | Lord+ (`::operation`) | [coinCost], not refunded | none |
 * | [CAMPAIGN] | Minister+ (`::campaign`) | stake, back on a win | [supplyCost] |
 * | [CONQUEST] | King (`::conquest`) | stake, back on a win | [supplyCost] |
 *
 * Starting is rank-gated ([command]); **joining never is** (`::march`, `WarEvents.join`). A story
 * event may launch any of MARCH / GRAND_MARCH / CAMPAIGN / CONQUEST as a *public* op with no
 * sponsor through `WarEvents.startPublicOperation` — free and supply-free, since no commander paid.
 */
enum class WarType(
    val display: String,
    val tier: CampaignTier,
    /** The command tier that may START it, or null when only the realm / a story event does. */
    val command: CommandTier?,
    /** Coins the commander pays on launch (0 = free). */
    val coinCost: Int,
) {
    MARCH("march", CampaignTier.MARCH, null, 0),
    GRAND_MARCH("grand march", CampaignTier.GRAND_MARCH, null, 0),
    /** A Lord's sponsored public offensive on a march target. The fee is the Lord's contribution — it
     *  is NOT refunded (they take the commander's tithe on a win instead). TUNE. */
    LORD_OPERATION("operation", CampaignTier.MARCH, CommandTier.RAID, 500_000),
    CAMPAIGN("campaign", CampaignTier.CAMPAIGN, CommandTier.CAMPAIGN, CampaignTier.CAMPAIGN.cost),
    CONQUEST("conquest", CampaignTier.CONQUEST, CommandTier.CONQUEST, CampaignTier.CONQUEST.cost),
    ;

    /** Realm Supplies the launch consumes (0 = none). Only the commanders' big operations spend. */
    val supplyCost: Int get() = tier.supplyCost
    val consumesSupplies: Boolean get() = supplyCost > 0

    /** True for the player-commanded types (a sponsor exists when one is launched by a player). */
    val sponsored: Boolean get() = command != null

    /** March-scale types strike the [MarchTargets] pool; the big ones strike [Campaigns.HOSTILE] cities. */
    val strikesMarchTargets: Boolean get() = tier == CampaignTier.MARCH || tier == CampaignTier.GRAND_MARCH

    companion object {
        /** The public type an engine op of [tier] was — null for a RAID party (boss support, not a war). */
        fun of(tier: CampaignTier, sponsored: Boolean): WarType? = when (tier) {
            CampaignTier.MARCH -> if (sponsored) LORD_OPERATION else MARCH
            CampaignTier.GRAND_MARCH -> GRAND_MARCH
            CampaignTier.CAMPAIGN -> CAMPAIGN
            CampaignTier.CONQUEST -> CONQUEST
            CampaignTier.RAID -> null
        }

        fun byName(name: String): WarType? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
