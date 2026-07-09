package org.alter.plugins.content.bosses

import org.alter.game.model.attr.COLLECTION_LOG_ATTR
import org.alter.game.model.entity.Player

/**
 * The **Collection Log** (content retention anchor — see the growth roadmap, Phase 4):
 * a persistent per-player set of notable drop ids the player has obtained at least once.
 * Backed by [COLLECTION_LOG_ATTR] (a comma-separated id string, since attrs persist only
 * Int/String). Drops opt in via [DropEntry.log]; the boss plugin calls [record] on each kill.
 */
object CollectionLog {

    /** All collected item ids for [p]. */
    fun ids(p: Player): Set<Int> =
        (p.attr[COLLECTION_LOG_ATTR] ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    /** Whether [p] has already collected [itemId]. */
    fun has(p: Player, itemId: Int): Boolean = itemId in ids(p)

    /**
     * Record [itemId] in [p]'s log. Returns true if it was newly added (a brand-new slot),
     * false if already present.
     */
    fun record(p: Player, itemId: Int): Boolean {
        val current = ids(p)
        if (itemId in current) return false
        p.attr[COLLECTION_LOG_ATTR] = (current + itemId).joinToString(",")
        return true
    }
}
