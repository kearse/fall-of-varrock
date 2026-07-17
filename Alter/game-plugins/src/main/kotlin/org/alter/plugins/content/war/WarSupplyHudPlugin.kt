package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **War-supply dial feed** — keeps the realm's war-supply meter ([RealmSupply]) in front of every
 * player. Publishes one packed varp [VARP] that the client's dial row
 * (`net.runelite.client.plugins.lofdials`) reads and draws as the far-right supply gauge; same
 * continuous-state pattern as [WarProgressPlugin] (refresh timer + onLogin, only sent on change).
 *
 * Packed layout (must match the client overlay):
 *   bits 0-11   meter (0-4095; SUPPLY_METER_MAX is 3000 so this never clips)
 *   bits 12-23  max   (0-4095)
 *   bit  24     campaign affordable
 *   bit  25     conquest affordable
 */
class WarSupplyHudPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = REFRESH_TICKS }
        onTimer(timer) {
            world.players.forEach { if (it.entityType.isHumanControlled) it.refreshSupplyDial() }
            world.timers[timer] = REFRESH_TICKS
        }
        onLogin { player.refreshSupplyDial() }
    }

    private fun Player.refreshSupplyDial() {
        val meter = RealmSupply.meter().coerceIn(0, 4095)
        val max = RealmSupply.max().coerceIn(1, 4095)
        val packed = meter or
            (max shl 12) or
            (if (RealmSupply.canAfford(CampaignTier.CAMPAIGN.supplyCost)) 1 shl 24 else 0) or
            (if (RealmSupply.canAfford(CampaignTier.CONQUEST.supplyCost)) 1 shl 25 else 0)

        if (getVarp(VARP) != packed) setVarp(VARP, packed)
    }

    private companion object {
        /** Packed supply varp the client dial reads (4609 — next free after LMS 4608; companions hold 4610-4612). */
        const val VARP = 4609
        const val REFRESH_TICKS = 5 // ~3s; the meter moves slowly
    }
}
