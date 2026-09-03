package org.alter.plugins.content.hostilezones

import org.alter.api.ext.message
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * Entry / exit banners for hostile zones + the entry-value snapshot the extraction haul is measured
 * against. Same cadence as the wilderness overlay ([TICK]); humans only. Kept separate from
 * `WildernessOverlayPlugin` (its zone-label state is private) — a raider sees the single/multi
 * line and this one on entry, which is fine.
 */
class HostileZoneBannerPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val prices = world.getService(ItemMarketValueService::class.java)

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            world.players.forEach { p ->
                if (!p.entityType.isHumanControlled || p.index < 0) return@forEach
                val now = HostileZones.at(p.tile)
                val was = p.attr[HostilePresence.ZONE_ATTR]
                if (now?.key == was) return@forEach
                if (now != null) {
                    p.attr[HostilePresence.ZONE_ATTR] = now.key
                    p.attr[HostilePresence.ENTRY_VALUE_ATTR] = HostilePresence.carriedValue(p, prices)
                    p.message("<col=cc2222>You enter ${now.display} — ${now.kind.entryLine}. Extract or die.</col>")
                } else {
                    val left = was?.let { HostileZones.byKey(it) }
                    p.attr.remove(HostilePresence.ZONE_ATTR)
                    p.attr.remove(HostilePresence.ENTRY_VALUE_ATTR)
                    if (left != null) p.message("<col=cc9933>You leave ${left.display}.</col>")
                }
            }
            world.timers[timer] = TICK
        }
    }

    private companion object {
        const val TICK = 2
    }
}
