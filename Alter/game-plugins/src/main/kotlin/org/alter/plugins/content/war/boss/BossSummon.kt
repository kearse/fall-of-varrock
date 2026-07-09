package org.alter.plugins.content.war.boss

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.Cities
import org.alter.plugins.content.war.CommandTier
import org.alter.plugins.content.war.WarTroops
import org.alter.plugins.content.war.canCommand
import org.alter.rscm.RSCM.getRSCM

/**
 * The paid, on-demand boss summon (Lord+). Spawns a boss in a city and sends a backing raid
 * party as one action. Ordering is deliberate: the **title gate** and **city-occupied check
 * run before any coin is taken**, so a rejected summon never costs the player.
 */
object BossSummon {
    private val coinId by lazy { getRSCM("item.coins_995") }

    /** Returns true if the boss was summoned (and the sponsor charged). */
    fun trySummon(world: World, sponsor: Player, def: BossDef, cityId: Int): Boolean {
        // 1. Title gate.
        if (!sponsor.canCommand(CommandTier.RAID)) {
            sponsor.message("<col=801700>Only a Lord or higher may summon a boss and send a raid.</col>")
            return false
        }
        // 1a. The boss must rotate into this city.
        if (cityId !in def.cities || def.tileIn(cityId) == null) {
            sponsor.message("<col=801700>The ${def.displayName} cannot be summoned there.</col>")
            return false
        }
        // 1b. One boss per city — checked BEFORE charging so a rejected summon costs nothing.
        if (BossScheduler.cityHasActiveBoss(cityId)) {
            val city = Cities.byId(cityId)?.displayName ?: "that city"
            sponsor.message("<col=801700>A boss already stalks $city. Wait until it falls.</col>")
            return false
        }
        // 2. Pay.
        val have = sponsor.inventory.getItemCount(coinId)
        if (have < def.summonCost) {
            sponsor.message("<col=801700>Summoning a ${def.displayName} costs ${fmt(def.summonCost)} coins; you carry only ${fmt(have)}.</col>")
            return false
        }
        sponsor.inventory.remove(coinId, def.summonCost)
        // 3-5. Spawn + register + announce (the scheduler owns this).
        val npc = BossScheduler.spawnBoss(world, def, cityId, sponsor, isSummon = true)
        if (npc == null) {
            // Should not happen (tile validated above) — refund defensively rather than eat coin.
            sponsor.inventory.add(coinId, def.summonCost)
            sponsor.message("<col=801700>The summoning failed; your coins are returned.</col>")
            return false
        }
        // 6. Send the backing raid party (campaign engine; no-op until that lands).
        WarTroops.dispatch(world, sponsor, cityId)
        return true
    }

    private fun fmt(n: Int): String = "%,d".format(n)
}
