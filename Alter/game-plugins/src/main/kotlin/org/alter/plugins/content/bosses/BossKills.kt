package org.alter.plugins.content.bosses

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **Per-boss kill ledger** — the "KC" every OSRS player expects next to their Collection Log.
 * One persistent string attribute (`key:count,key:count`), written through on every record;
 * content plugins call [record] from their death hook and [register] a display name once at
 * load so `::kc` can print "Barrows chests: 12" instead of a raw key.
 *
 * Deliberately tiny: no per-kill timestamps, no fastest-kill — those are UI features for
 * later; the ledger is the data anchor the loot-mechanics work (drop-rate boosts at KC
 * thresholds, pet threshold rolls) can build on.
 */
object BossKills {

    val BOSS_KILLS_ATTR = AttributeKey<String>("boss_kills")

    private val names = LinkedHashMap<String, String>()

    /** Give [key] a human name for `::kc`; registration order is display order. */
    fun register(key: String, displayName: String) {
        names[key] = displayName
    }

    fun displayName(key: String): String = names[key] ?: key

    /** Every registered key in display order (so `::kc` lists zero-kill bosses too). */
    fun registeredKeys(): List<String> = names.keys.toList()

    fun all(p: Player): Map<String, Int> =
        (p.attr[BOSS_KILLS_ATTR] ?: "")
            .split(",")
            .mapNotNull { entry ->
                val i = entry.lastIndexOf(':')
                if (i <= 0) return@mapNotNull null
                val n = entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, i) to n
            }
            .toMap()

    fun count(p: Player, key: String): Int = all(p)[key] ?: 0

    /** Add [n] kills of [key] for [p]; returns the new total. */
    fun record(p: Player, key: String, n: Int = 1): Int {
        val m = all(p).toMutableMap()
        val total = (m[key] ?: 0) + n
        m[key] = total
        p.attr[BOSS_KILLS_ATTR] = m.entries.joinToString(",") { "${it.key}:${it.value}" }
        return total
    }
}
