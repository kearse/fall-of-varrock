package org.alter.plugins.content.quests.framework

import dev.openrune.cache.CacheManager.getNpc
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.points
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * What a [QuestStep] asks of the player. [QuestEngine] evaluates these: kills on the additive
 * npc-death list, areas/items/predicates on the poll, [TalkTo]/[Manual] only when code calls
 * [QuestEngine.satisfy] (an [NpcTalk] branch, an event hook, a war result …).
 */
sealed class Objective(val text: String) {

    /**
     * Kill [count] npcs matching any of [npcKeys] (rscm keys) OR whose cache name contains
     * [nameContains]; optionally only inside [where] and/or passing [filter] (e.g. "tagged by my
     * instance"). With neither keys nor name given, every npc kill passing the other filters counts.
     */
    class KillNpcs(
        text: String,
        val count: Int,
        val npcKeys: Set<String> = emptySet(),
        val nameContains: String? = null,
        val where: Area? = null,
        val filter: ((Player, Npc) -> Boolean)? = null,
    ) : Objective(text) {
        private val ids: Set<Int> by lazy { npcKeys.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet() }

        fun matches(p: Player, npc: Npc): Boolean {
            val needle = nameContains
            if (ids.isNotEmpty() || needle != null) {
                val idOk = ids.isNotEmpty() && npc.id in ids
                val nameOk = needle != null &&
                    runCatching { getNpc(npc.id).name.contains(needle, ignoreCase = true) }.getOrDefault(false)
                if (!idOk && !nameOk) return false
            }
            if (where != null && !where.contains(npc.tile)) return false
            if (filter != null && !filter.invoke(p, npc)) return false
            return true
        }
    }

    /** Stand anywhere inside [area]. */
    class ReachArea(text: String, val area: Area) : Objective(text)

    /** Speak to [npcKey] — satisfied by the quest's [NpcTalk] branch calling [QuestEngine.satisfy]. */
    class TalkTo(text: String, val npcKey: String) : Objective(text)

    /** Carry [items] (rscm key → amount); [consume] takes them the moment the step clears. */
    class HaveItems(text: String, val items: List<Pair<String, Int>>, val consume: Boolean = false) : Objective(text) {
        fun has(p: Player): Boolean = items.all { (key, n) ->
            val id = runCatching { getRSCM(key) }.getOrNull() ?: return false
            p.inventory.getItemCount(id) >= n
        }

        fun take(p: Player) {
            items.forEach { (key, n) -> runCatching { p.inventory.remove(getRSCM(key), n) } }
        }
    }

    /** Any player-state test, polled every few ticks. */
    class Predicate(text: String, val test: (Player) -> Boolean) : Objective(text)

    /** Advanced only by code ([QuestEngine.satisfy] / [QuestEngine.advance]). */
    class Manual(text: String) : Objective(text)
}

/** What must be true before a quest may [QuestEngine.begin]. */
sealed class Prerequisite {
    abstract val description: String
    abstract fun met(p: Player): Boolean

    /** Another quest — legacy chain or framework — is complete (see [QuestRegistry.isComplete]). */
    class QuestComplete(val questKey: String) : Prerequisite() {
        override val description: String get() = "finish ${QuestRegistry.byKey(questKey)?.displayName ?: questKey}"
        override fun met(p: Player): Boolean = QuestRegistry.isComplete(p, questKey)
    }

    class RankAtLeast(val title: Title) : Prerequisite() {
        override val description: String get() = "hold the rank of ${title.display}"
        override fun met(p: Player): Boolean = p.title.ordinal >= title.ordinal
    }

    class WarEffortAtLeast(val amount: Int) : Prerequisite() {
        override val description: String get() = "$amount lifetime War Effort"
        override fun met(p: Player): Boolean = p.points(PointKind.WAR_EFFORT) >= amount
    }

    class FlagSet(val flag: String, label: String? = null) : Prerequisite() {
        override val description: String = label ?: flag
        override fun met(p: Player): Boolean = Flags.has(p, flag)
    }

    class Custom(override val description: String, val test: (Player) -> Boolean) : Prerequisite() {
        override fun met(p: Player): Boolean = test(p)
    }
}

/** What a step or a completion grants. Every [grant] is defensive on missing item keys. */
sealed class Reward {
    abstract val description: String
    abstract fun grant(p: Player)

    class Items(val items: List<Pair<String, Int>>) : Reward() {
        override val description: String get() = items.joinToString(", ") { (k, n) -> "$n x ${k.removePrefix("item.")}" }
        override fun grant(p: Player) = items.forEach { (k, n) -> giveItem(p, k, n) }
    }

    class Coins(val amount: Int) : Reward() {
        override val description: String get() = "${"%,d".format(amount)} coins"
        override fun grant(p: Player) = giveItem(p, "item.coins_995", amount)
    }

    class WarEffort(val amount: Int) : Reward() {
        override val description: String get() = "$amount War Effort"
        override fun grant(p: Player) { p.addPoints(PointKind.WAR_EFFORT, amount) }
    }

    class Prestige(val amount: Int) : Reward() {
        override val description: String get() = "$amount Prestige"
        override fun grant(p: Player) { p.addPoints(PointKind.PRESTIGE, amount) }
    }

    class Xp(val skill: Int, val xp: Double) : Reward() {
        override val description: String get() = "${xp.toInt()} xp"
        override fun grant(p: Player) { p.addXp(skill, xp) }
    }

    /** Set a [Flags] flag (a milestone other systems read — rank eligibility, transport …). */
    class Flag(val flag: String) : Reward() {
        override val description: String get() = flag
        override fun grant(p: Player) { Flags.set(p, flag) }
    }

    /** Unlock a transport route: sets the `route.<key>` flag `TransportRoutes` reads. */
    class UnlockRoute(val routeKey: String) : Reward() {
        override val description: String get() = "route: $routeKey"
        override fun grant(p: Player) { Flags.set(p, Flags.Known.ROUTE_PREFIX + routeKey) }
    }

    class Custom(override val description: String, val apply: (Player) -> Unit) : Reward() {
        override fun grant(p: Player) = apply(p)
    }

    companion object {
        /** Add [amount] of [key] to the bag; whatever doesn't fit overflows to the bank. */
        fun giveItem(p: Player, key: String, amount: Int) {
            runCatching {
                val id = getRSCM(key)
                val tx = p.inventory.add(id, amount, assureFullInsertion = false)
                val left = amount - tx.completed
                if (left > 0) p.bank.add(id, left)
            }
        }
    }
}

/**
 * One step of a quest. [id] is the persisted key (stable, human-readable); [anchor]/[anchorNpc]
 * feed the guidance arrow ([QuestArrows]); [onEnter]/[onLeave] are the side-effect hooks (open an
 * instance, spawn an npc, start a war); [rewards] pay when the step clears; [nudge] is an extra
 * hint line printed on step entry and with the login reminder.
 */
class QuestStep(
    val id: String,
    val objective: Objective,
    val anchor: Tile? = null,
    val anchorNpc: String? = null,
    val onEnter: ((Player) -> Unit)? = null,
    val onLeave: ((Player) -> Unit)? = null,
    val rewards: List<Reward> = emptyList(),
    val nudge: String? = null,
)
