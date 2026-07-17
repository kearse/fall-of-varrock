package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.boss.BossScheduler

/**
 * **War progress HUD feed** — tells each player how far through their current fight they are.
 *
 * Publishes one packed varp [VARP] that the client's dial row (`net.runelite.client.plugins.lofdials`)
 * reads and draws as the campaign/conquest progress dial. The context is resolved per player from where
 * they're standing:
 *   1. inside a non-RAID campaign's battlefield → that campaign's progress (enemies cleared / quota),
 *   2. else near an active city boss → that boss's HP depleted,
 *   3. else nothing (bar hidden).
 *
 * Packed layout (must match the client overlay):
 *   bits 0-1   kind   (0 none / 1 boss / 2 campaign)
 *   bits 2-8   pct    (0-100)
 *   bits 9-10  tier   (campaign only: 0 Campaign / 1 Conquest)
 */
class WarProgressPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = REFRESH_TICKS }
        onTimer(timer) {
            world.players.forEach { if (it.entityType.isHumanControlled) it.refreshProgress() }
            world.timers[timer] = REFRESH_TICKS
        }
        onLogin { player.refreshProgress() }
    }

    private fun Player.refreshProgress() {
        var kind = 0
        var pct = 0
        var tierCode = 0

        val campaign = CampaignRegistry.campaignCovering(tile)
        if (campaign != null) {
            kind = 2
            pct = campaign.progressPct(world)
            tierCode = campaign.campaignTierCode
        } else {
            val bossPct = BossScheduler.bossProgressNear(tile, BOSS_RADIUS)
            if (bossPct != null) {
                kind = 1
                pct = bossPct
            }
        }

        val packed = (kind and 0x3) or
            ((pct.coerceIn(0, 100) and 0x7F) shl 2) or
            ((tierCode and 0x3) shl 9)

        if (getVarp(VARP) != packed) setVarp(VARP, packed)
    }

    private companion object {
        /** Packed progress varp the client overlay reads (4601 — freed when the siege HUD retired). */
        const val VARP = 4601
        const val REFRESH_TICKS = 3 // ~1.8s
        const val BOSS_RADIUS = 25  // show a boss bar when within this many tiles of the boss
    }
}
