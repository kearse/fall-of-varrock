package org.alter.plugins.content.war

import org.alter.game.model.World
import org.alter.game.model.entity.Player

/**
 * Bridge from a paid boss summon to the allied-troop **campaign engine**: a Lord's summon
 * sends a small raid party into the city to clear the path and fight alongside the players.
 *
 * This is the seam the campaign engine fills in (see CampaignDirector / CampaignRegistry).
 * Until then it is a deliberate no-op so the boss-summon path compiles and works on its own
 * — a summoned boss is fully functional without backing troops.
 */
object WarTroops {
    /**
     * Send [sponsor]'s backing raid party to [cityId] — a small allied band that clears the
     * frontier path and fights alongside the players at the boss. No-op if the city has no
     * campaign config, or already has an operation underway (the boss is still summoned;
     * it just goes unescorted).
     */
    fun dispatch(world: World, sponsor: Player, cityId: Int) {
        if (CampaignRegistry.hasSquad(sponsor)) return // the summoner already has men out
        // Boss-help: the vanguard musters from home and marches to the boss (cityId is the home city).
        CampaignRegistry.start(world, Campaigns.home(), CampaignTier.RAID, sponsor)
    }
}
