package org.alter.plugins.content.teleport

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.magic.canTeleport
import org.alter.plugins.content.magic.teleport

/**
 * Persistent CSV of a player's most-recent teleport keys (most-recent first, max
 * [TeleportService.RECENT_LIMIT]). Drives the interface's "Previous Teleports"
 * panel and the portal's right-click "Previous Teleport".
 */
val LAST_TELEPORTS_ATTR = AttributeKey<String>("last_teleports")

/**
 * Executes portal teleports and feeds the two dynamic features (Previous Teleports,
 * Popular Now). Pure server-side logic — the temporary `::portal` command and the
 * later cache interface both go through [teleport].
 *
 * Teleporting reuses the existing magic machinery ([Player.teleport] / [canTeleport])
 * so tele-block, the wilderness-level restriction, lock state and the animation/gfx
 * all behave exactly like a spellbook teleport.
 */
object TeleportService {

    const val RECENT_LIMIT = 3
    const val POPULAR_LIMIT = 5

    /** Server-wide lifetime use count per destination key (drives Popular Now). */
    private val usage = HashMap<String, Int>()

    /**
     * Attempt to send [player] to [dest]. Returns true if the teleport started.
     * Coming-soon destinations are rejected with a message; combat/tele-block/wild
     * restrictions are enforced by [canTeleport].
     */
    fun teleport(player: Player, dest: TeleportDestination): Boolean {
        if (!dest.isBuilt) {
            player.message("<col=801700>${dest.displayName}</col> is coming soon!")
            return false
        }
        if (!player.canTeleport(dest.teleType)) return false

        player.teleport(dest.dest, dest.teleType)
        recordUse(player, dest)
        return true
    }

    /** Convenience: teleport by destination key (no-op + false if unknown). */
    fun teleport(player: Player, key: String): Boolean {
        val dest = TeleportRegistry.byKey(key) ?: return false
        return teleport(player, dest)
    }

    // ── Dynamic feature backing ────────────────────────────────────────────────

    private fun recordUse(player: Player, dest: TeleportDestination) {
        usage[dest.key] = (usage[dest.key] ?: 0) + 1

        val recent = recentKeys(player).toMutableList()
        recent.remove(dest.key)          // dedup — bump to front
        recent.add(0, dest.key)
        while (recent.size > RECENT_LIMIT) recent.removeAt(recent.lastIndex)
        player.attr[LAST_TELEPORTS_ATTR] = recent.joinToString(",")
    }

    fun recentKeys(player: Player): List<String> =
        player.attr[LAST_TELEPORTS_ATTR]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /** The player's last [RECENT_LIMIT] destinations, most-recent first. */
    fun recent(player: Player): List<TeleportDestination> =
        recentKeys(player).mapNotNull { TeleportRegistry.byKey(it) }

    /** The single most-recent destination (for the portal's right-click), or null. */
    fun lastUsed(player: Player): TeleportDestination? = recent(player).firstOrNull()

    /** Top [POPULAR_LIMIT] destinations server-wide by use count, busiest first. */
    fun popular(): List<TeleportDestination> =
        usage.entries.sortedByDescending { it.value }
            .mapNotNull { TeleportRegistry.byKey(it.key) }
            .take(POPULAR_LIMIT)
}
