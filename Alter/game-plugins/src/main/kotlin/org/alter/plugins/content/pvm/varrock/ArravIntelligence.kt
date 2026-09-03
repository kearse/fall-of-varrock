package org.alter.plugins.content.pvm.varrock

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.VarrockDistrict
import org.alter.plugins.content.war.forge.WarForge
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * **Arrav Intelligence** — repeatable high-level Fallen Varrock assignments (story doc 02 §12:
 * "The Cursed Hero → Arrav Intelligence: repeatable high-level Fallen Varrock assignments, War
 * Effort, salvage, FoV materials, valuables"). Modelled on [org.alter.plugins.content.skills.slayer.ResourceContracts]:
 * one assignment at a time, auto-completing as the work is done, paid in War Effort +
 * Commendations + salvage/relics through the war team's existing APIs.
 *
 * **Gate**: the quest flag once Team 3 ships *The Cursed Hero*; until [ENFORCE_QUEST] is flipped,
 * Knight rank stands in (it is endgame work either way).
 */
object ArravIntelligence {

    const val QUEST_FLAG = "quest.the_cursed_hero.done"
    const val ENFORCE_QUEST = false
    val MIN_TITLE = Title.KNIGHT

    enum class Kind(val display: String) { PURGE("Purge"), SALVAGE("Salvage"), BOUNTY("Bounty"), WARDEN("Warden") }

    /** `kind:district:left` — absent = no assignment. */
    val TASK_ATTR = AttributeKey<String>("arrav_task")
    val LAST_ATTR = AttributeKey<String>("arrav_last")
    val DONE_ATTR = AttributeKey<Int>(persistenceKey = "arrav_done")

    data class Task(val kind: Kind, val district: VarrockDistrict?, var left: Int) {
        fun encode() = "${kind.name}:${district?.key ?: "-"}:$left"
        fun describe(): String = when (kind) {
            Kind.PURGE -> "purge $left elite undead in ${district?.display ?: "Fallen Varrock"}"
            Kind.SALVAGE -> "recover $left Varrock salvage from the ruins"
            Kind.BOUNTY -> "hunt down Malachai the Hollow"
            Kind.WARDEN -> "fell the Palace Warden"
        }

        companion object {
            fun decode(s: String?): Task? {
                val parts = s?.split(":") ?: return null
                if (parts.size != 3) return null
                val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
                val left = parts[2].toIntOrNull() ?: return null
                return Task(kind, VarrockDistrict.byKey(parts[1]), left)
            }
        }
    }

    fun canUse(p: Player): Boolean =
        Flags.has(p, QUEST_FLAG) || (!ENFORCE_QUEST && p.title.ordinal >= MIN_TITLE.ordinal)

    fun current(p: Player): Task? = Task.decode(p.attr[TASK_ATTR])?.takeIf { it.left > 0 }

    fun completed(p: Player): Int = p.attr[DONE_ATTR] ?: 0

    /** Hand out a fresh assignment (or report the active one). */
    fun assign(p: Player): Task {
        current(p)?.let { return it }
        val world = p.world
        val last = p.attr[LAST_ATTR]
        var pool = Kind.values().toList()
        if (last != null && pool.size > 1) pool = pool.filter { it.name != last }
        // Boss hunts are rarer work than street purges.
        val weighted = pool.flatMap { k -> List(when (k) { Kind.PURGE -> 4; Kind.SALVAGE -> 3; Kind.BOUNTY -> 1; Kind.WARDEN -> 2 }) { k } }
        val kind = weighted[world.random(weighted.size - 1)]
        val task = when (kind) {
            Kind.PURGE -> Task(kind, VarrockDistrict.all[world.random(VarrockDistrict.all.size - 1)], 8 + world.random(6))
            Kind.SALVAGE -> Task(kind, null, 10 + world.random(8))
            Kind.BOUNTY -> Task(kind, null, 1)
            Kind.WARDEN -> Task(kind, null, 1)
        }
        p.attr[TASK_ATTR] = task.encode()
        return task
    }

    fun onEliteKill(p: Player, district: VarrockDistrict?) {
        val t = current(p) ?: return
        if (t.kind != Kind.PURGE) return
        if (t.district != null && district != t.district) return
        progress(p, t, 1)
    }

    fun onSalvage(p: Player, amount: Int) {
        val t = current(p) ?: return
        if (t.kind != Kind.SALVAGE) return
        progress(p, t, amount)
    }

    fun onHollowKill(p: Player) { current(p)?.takeIf { it.kind == Kind.BOUNTY }?.let { progress(p, it, 1) } }
    fun onWardenKill(p: Player) { current(p)?.takeIf { it.kind == Kind.WARDEN }?.let { progress(p, it, 1) } }

    private fun progress(p: Player, t: Task, n: Int) {
        t.left = (t.left - n).coerceAtLeast(0)
        if (t.left > 0) {
            p.attr[TASK_ATTR] = t.encode()
            p.message("<col=801700>Arrav Intelligence:</col> ${t.left} to go — ${t.describe()}.")
            return
        }
        p.attr.remove(TASK_ATTR)
        p.attr[LAST_ATTR] = t.kind.name
        p.attr[DONE_ATTR] = completed(p) + 1
        pay(p, t.kind)
    }

    private fun pay(p: Player, kind: Kind) {
        val world = p.world
        val (we, comm) = when (kind) {
            Kind.PURGE -> 15 to 2
            Kind.SALVAGE -> 12 to 2
            Kind.BOUNTY -> 25 to 4
            Kind.WARDEN -> 40 to 6
        }
        p.addPoints(PointKind.WAR_EFFORT, we)
        WarForge.awardCommendations(p, comm)
        var extra = ""
        when (kind) {
            Kind.PURGE -> { give(p, VarrockPvm.SALVAGE_KEY, 5); extra = ", 5 salvage" }
            Kind.SALVAGE -> if (world.chance(1, 6)) { give(p, VarrockPvm.RELIC_KEY, 1); extra = ", a relic" }
            Kind.BOUNTY -> { give(p, VarrockPvm.RELIC_KEY, 1); extra = ", a relic" }
            Kind.WARDEN -> { give(p, VarrockPvm.RELIC_KEY, 2); extra = ", 2 relics" }
        }
        p.message("<col=801700>Assignment complete!</col> +$we War Effort, +$comm Commendations$extra. Report to Captain Rovin for more.")
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) p.world.spawn(GroundItem(id, leftover, p.tile, p))
    }
}
