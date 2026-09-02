package org.alter.plugins.content.mechanics

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **Player flags** — persistent, boolean, per-player facts with a stable string key: milestones
 * ("veteran_of_varrock"), route unlocks ("route.<key>"), quest completions ("quest.<key>.done"),
 * one-time story beats. The single seam achievements, rank eligibility, transport gating and the
 * Block-2 quest framework share, so nobody invents a new `AttributeKey<Boolean>` per fact.
 *
 * Stored as a sorted comma-separated set on [PLAYER_FLAGS_ATTR] (a plugin-local persistent key —
 * the save layer matches on the key string, like `last_teleports`). Keys are lower-cased.
 */
object Flags {

    val PLAYER_FLAGS_ATTR = AttributeKey<String>("player_flags")

    /** Well-known flag keys. Reserve here; award elsewhere. */
    object Known {
        /**
         * Meaningful participation in a major Varrock assault (design authority §5) — feeds
         * Minister/King eligibility. **RESERVED: nothing awards it in Block 1**; the first major
         * assault story event (Block 2) sets it via [set].
         */
        const val VETERAN_OF_VARROCK = "veteran_of_varrock"

        /** Prefix for transport-route unlocks: `route.<routeKey>`. */
        const val ROUTE_PREFIX = "route."

        /** Prefix for quest completions: `quest.<questKey>.done`. */
        const val QUEST_DONE_PREFIX = "quest."
    }

    fun has(p: Player, flag: String): Boolean = norm(flag) in all(p)

    /** Set [flag]; returns true if it was newly set. */
    fun set(p: Player, flag: String): Boolean {
        val s = all(p).toMutableSet()
        if (!s.add(norm(flag))) return false
        write(p, s)
        return true
    }

    /** Clear [flag]; returns true if it was set. */
    fun clear(p: Player, flag: String): Boolean {
        val s = all(p).toMutableSet()
        if (!s.remove(norm(flag))) return false
        write(p, s)
        return true
    }

    fun all(p: Player): Set<String> =
        p.attr[PLAYER_FLAGS_ATTR]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun norm(flag: String): String = flag.trim().lowercase()

    private fun write(p: Player, flags: Set<String>) {
        p.attr[PLAYER_FLAGS_ATTR] = flags.sorted().joinToString(",")
    }
}
