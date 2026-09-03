package org.alter.plugins.content.bosses

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * **Per-boss kill ledger** — the "KC" every OSRS player expects next to their Collection Log.
 * One persistent string attribute (`key:count,key:count`), written through on every record;
 * content plugins call [record] from their death hook and [register] a display name once at
 * load so `::kc` can print "Barrows chests: 12" instead of a raw key.
 *
 * Deliberately tiny: no per-kill timestamps, no fastest-kill — those are UI features for
 * later; the ledger is the data anchor the loot-mechanics work (drop-rate boosts at KC
 * thresholds, pet threshold rolls) can build on.
 *
 * **Milestones.** When the Boss Ticket shop was retired (economy #336) two statless cosmetics
 * lost their only shelf; they are now kill-count milestones across the whole ledger: the
 * **Champion's cape** at 100 boss kills and the **Divine halo** at 500. Awarded once, to
 * inventory or bank, with a world announcement.
 */
object BossKills {

    val BOSS_KILLS_ATTR = AttributeKey<String>("boss_kills")

    data class Milestone(val kills: Int, val item: String, val title: String)

    val MILESTONES = listOf(
        Milestone(100, "item.champions_cape", "Champion's cape"),
        Milestone(500, "item.divine_halo", "Divine halo"),
    )

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

    /** Every kill of every registered boss, minigame chest and story boss. */
    fun grandTotal(p: Player): Int = all(p).values.sum()

    /** Add [n] kills of [key] for [p]; returns the new total for that key. Fires milestones on the grand total. */
    fun record(p: Player, key: String, n: Int = 1): Int {
        val m = all(p).toMutableMap()
        val before = m.values.sum()
        val total = (m[key] ?: 0) + n
        m[key] = total
        p.attr[BOSS_KILLS_ATTR] = m.entries.joinToString(",") { "${it.key}:${it.value}" }
        val after = before + n
        MILESTONES.forEach { ms -> if (before < ms.kills && after >= ms.kills) award(p, ms) }
        return total
    }

    private fun award(p: Player, ms: Milestone) {
        val id = runCatching { getRSCM(ms.item) }.getOrNull() ?: return
        val add = p.inventory.add(item = id, amount = 1, assureFullInsertion = false)
        if (add.completed == 0) p.bank.add(id, 1)
        val name = getItem(id).name ?: ms.title
        p.message("<col=ffae00>${ms.kills} boss kills! The ${ms.title} is yours${if (add.completed == 0) " (sent to your bank)" else ""}.</col>")
        p.world.players.forEach { other ->
            if (other !== p) other.message("<col=ff0000>News: ${p.username} has reached ${ms.kills} boss kills and earned the <col=ffae00>$name</col>!</col>")
        }
    }
}
