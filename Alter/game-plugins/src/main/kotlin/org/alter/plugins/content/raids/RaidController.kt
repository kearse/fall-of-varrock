package org.alter.plugins.content.raids

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

/**
 * Runs one live raid in its [instance] (R0) — the generalized wave/room state machine (the
 * Fight-Cave loop, lifted out and made instance-aware + party-aware). Driven once per tick by
 * [RaidsPlugin]. Party is solo-first (a list of one), but the logic is already multi-player.
 */
class RaidController(
    private val world: World,
    val party: MutableList<Player>,
    val instance: RaidInstance,
    val def: RaidDefinition,
    private val onEnd: (RaidController) -> Unit,
) {
    private var stageIdx = -1
    private val alive = mutableListOf<Npc>()
    private var active = true

    /** Teleport the party in and start the first stage. */
    fun begin() {
        party.forEach { it.moveTo(instance.translate(def.entryTile)) }
        party.forEach { it.message("<col=007f00>${def.name} begins!</col>") }
        nextStage()
    }

    fun tick() {
        if (!active) return
        party.removeAll { it.index < 0 }
        if (party.none { instance.contains(it.tile) }) { end(reward = false); return } // everyone left/died
        alive.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
        if (alive.isEmpty()) nextStage()
    }

    private fun nextStage() {
        stageIdx++
        if (stageIdx >= def.stages.size) { complete(); return }
        val stage = def.stages[stageIdx]
        if (stage.announce.isNotBlank()) party.forEach { it.message(stage.announce) }
        for (sp in stage.spawns) {
            val id = runCatching { getRSCM(sp.npcKey) }.getOrNull() ?: continue
            repeat(sp.count) {
                val npc = Npc(id, instance.translate(sp.tile), world)
                npc.respawns = false
                world.spawn(npc)
                npc.setActive(true)
                alive += npc
            }
        }
    }

    private fun complete() {
        party.forEach { p ->
            p.awardTickets(PointKind.BOSS, def.points)
            def.reward.roll(world).forEach { drop ->
                val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
                world.spawn(GroundItem(id, drop.amount, p.tile, p))
                if (drop.log) CollectionLog.record(p, id)
            }
            p.message("<col=ffae00>You have completed ${def.name}!</col> (+${def.points} Boss Tickets)")
        }
        end(reward = false) // reward already paid
    }

    fun end(reward: Boolean) {
        if (!active) return
        active = false
        alive.forEach { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
        alive.clear()
        party.forEach { if (it.index >= 0 && instance.contains(it.tile)) it.moveTo(def.exitTile) }
        onEnd(this)
    }
}
