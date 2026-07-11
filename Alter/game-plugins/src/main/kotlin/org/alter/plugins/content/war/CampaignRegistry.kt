package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player

/**
 * Tracks every running [CampaignDirector] and ticks them from a single timer. Multiple Lords can
 * each have a squad in the field at once — so a boss fight can draw several Lords' men — but a Lord
 * may only field **one squad at a time**. Runtime-only: a half-finished paid operation does not, by
 * design, survive a restart.
 */
object CampaignRegistry {
    private val active = ArrayList<CampaignDirector>()

    /** True if [sponsor] already has a squad in the field (one per Lord at a time). */
    fun hasSquad(sponsor: Player): Boolean = active.any { it.ownedBy(sponsor) }

    /** [sponsor]'s active squad, if any (for setting its orders via `::troops`). */
    fun squadOf(sponsor: Player): CampaignDirector? = active.firstOrNull { it.ownedBy(sponsor) }

    /** The non-RAID campaign whose battlefield covers [tile] (drives the client progress HUD), if any. */
    fun campaignCovering(tile: Tile): CampaignDirector? =
        active.firstOrNull { it.tier != CampaignTier.RAID && it.coversBattle(tile) }

    /** Every active squad operating in [cityId] (used to pay out each Lord's damage share). */
    fun squadsIn(cityId: Int): List<CampaignDirector> = active.filter { it.cityId == cityId }

    /** True if a CAMPAIGN/CONQUEST (not a boss-backing RAID) is currently besieging [cityKey] — the
     *  signal a campaign-gated frontier ([CityFrontierPlugin]) uses to keep that city's garrison
     *  mustered. When this flips false (the campaign ended) the frontier despawns the garrison. */
    fun isAttacking(cityKey: String): Boolean =
        active.any { it.tier != CampaignTier.RAID && it.targetCityKey.equals(cityKey, ignoreCase = true) }

    /** The realm's scheduled warband currently in the field ([CampaignTier.MARCH]), if any. */
    fun activeMarch(): CampaignDirector? = active.firstOrNull { it.tier == CampaignTier.MARCH }

    /**
     * Begin a [tier] operation for [sponsor] (null = the realm's own scheduled MARCH). Returns
     * false (and does nothing) if that Lord already has a squad out — callers must charge only
     * after this succeeds, or refund on false. [onResult] fires once with the outcome
     * (true = victory) — e.g. the march's district pressure credit.
     */
    fun start(world: World, op: CampaignOp, tier: CampaignTier, sponsor: Player?, onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (sponsor != null && hasSquad(sponsor)) return false
        val director = CampaignDirector(op, tier, sponsor, onResult) { active.remove(it) }
        active += director
        director.init(world)
        return true
    }

    /**
     * Route a slain frontier enemy's loot [value] into the campaign being fought over its tile, if any.
     * Returns true if a campaign claimed it (the caller then SKIPS the normal floor drop — the value is
     * pooled for the end-of-raid payout instead). False = no campaign here, drop loot as usual.
     */
    fun creditCityKill(tile: Tile, value: Int): Boolean {
        // Only a CAMPAIGN/CONQUEST pools loot; a boss-backing RAID must leave frontier drops on the
        // floor (claiming-but-not-pooling would make the loot vanish).
        val director = active.firstOrNull { it.tier != CampaignTier.RAID && it.coversBattle(tile) } ?: return false
        director.creditLoot(value)
        return true
    }

    /** Total damage all active squads in [cityId] dealt to [target], grouped by their Lord.
     *  Realm-sponsored marches have no Lord to credit, so they're skipped. */
    fun troopDamageByOwner(cityId: Int, target: Npc): Map<Player, Int> =
        squadsIn(cityId)
            .mapNotNull { d -> d.owner?.let { it to d.troopDamageTo(target) } }
            .toMap()
            .filterValues { it > 0 }

    /** TEST (`::wintest`): force [sponsor]'s active squad to a victory now, seeding [seedValue] gp into
     *  its war-chest. Returns false if they have no squad in the field. */
    fun forceWin(world: World, sponsor: Player, seedValue: Int): Boolean {
        val director = squadOf(sponsor) ?: return false
        director.forceVictory(world, seedValue, sponsor)
        return true
    }

    /** Tick all active squads (snapshot first — a director may finish and remove itself). */
    fun tick(world: World) {
        active.toList().forEach { it.tick(world) }
    }
}
