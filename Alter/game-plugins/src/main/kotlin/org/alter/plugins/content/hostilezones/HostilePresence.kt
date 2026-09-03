package org.alter.plugins.content.hostilezones

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * Which hostile zone a player is standing in, and what they walked in carrying — session-only
 * state shared by the banner ([HostileZoneBannerPlugin]) and the extraction channel
 * ([HostileExtractionPlugin]).
 */
object HostilePresence {
    /** Key of the zone the player is currently inside (absent = none). Session-only. */
    val ZONE_ATTR = AttributeKey<String>()
    /** Market value (gp) of the tradeables the player carried when they ENTERED the zone —
     *  the baseline an extraction's "haul" is measured against. Session-only. */
    val ENTRY_VALUE_ATTR = AttributeKey<Long>()

    fun current(p: Player): HostileZoneConfig? = p.attr[ZONE_ATTR]?.let { HostileZones.byKey(it) }

    /** Market value of every TRADEABLE item worn + carried (cache cost fallback). */
    fun carriedValue(p: Player, prices: ItemMarketValueService?): Long {
        var total = 0L
        fun add(id: Int, amount: Int, tradeable: Boolean, cost: Int) {
            if (!tradeable) return
            val market = prices?.get(id) ?: 0
            val unit = if (market > 0) market else cost
            total += amount.toLong() * unit
        }
        for (i in 0 until p.inventory.capacity) {
            p.inventory[i]?.let { add(it.id, it.amount, it.getDef().isTradeable, it.getDef().cost ?: 0) }
        }
        for (i in 0 until p.equipment.capacity) {
            p.equipment[i]?.let { add(it.id, it.amount, it.getDef().isTradeable, it.getDef().cost ?: 0) }
        }
        return total
    }
}
