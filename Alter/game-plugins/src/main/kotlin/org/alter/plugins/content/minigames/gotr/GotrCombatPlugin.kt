package org.alter.plugins.content.minigames.gotr

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Npc
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.removeCombatTarget

/**
 * Abyssal creature behaviour. Every creature spawns at the rift, picks a barrier column and
 * walks south. **Leeches** slip past a hole in the line and drain the Great Guardian's power
 * once they reach him (1% per bite, five bites, then they burn out). **Walkers** batter the
 * barrier in their column (10 per hit) and only push on when it is down. **Guardians** use the
 * engine's melee against players (aggro radius 8) and otherwise trudge toward the Guardian
 * like a walker (6 per hit on barriers). All three die to normal combat.
 *
 * Barriers are owned by [GotrPlugin]; the callbacks below are how a creature asks it.
 */
class GotrCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Set by [GotrPlugin] at world init so creatures can talk to the live game. */
    interface Arena {
        fun barrierAt(column: Int): Boolean
        fun hitBarrier(column: Int, damage: Int)
        fun drainPower(percent: Int)
        fun active(): Boolean
    }

    companion object {
        @Volatile var arena: Arena? = null
        val COLUMN = AttributeKey<Int>()
        val ROLE = AttributeKey<String>()

        /** Called by [GotrPlugin] right after spawning a creature. */
        fun drive(world: World, npc: Npc, role: String, column: Int) {
            npc.attr[ROLE] = role
            npc.attr[COLUMN] = column
            npc.queue { march(this, world, npc) }
        }

        private suspend fun march(task: QueueTask, world: World, npc: Npc) {
            val role = npc.attr[ROLE] ?: return
            val column = npc.attr[COLUMN] ?: 0
            val gate = Tile(Gotr.CELL_TILES[column].x, Gotr.CELL_TILES[column].z + 1, 0)
            var bites = 0
            while (npc.index >= 0 && !npc.isDead()) {
                val a = arena
                if (a == null || !a.active()) { world.remove(npc); return }
                // Guardians fighting a player stay with the engine's combat loop.
                if (role == "guardian" && npc.getCombatTarget() != null) { task.wait(2); continue }
                if (!npc.tile.isWithinRadius(gate, 1) && npc.tile.z > gate.z) {
                    npc.walkTo(gate)
                    task.wait(2)
                    continue
                }
                if (npc.tile.z > Gotr.CELL_TILES[column].z - 1 && a.barrierAt(column)) {
                    // A barrier stands in this column.
                    when (role) {
                        "leech" -> { npc.animate(npc.combatDef.attackAnimation); a.hitBarrier(column, 3) }
                        "walker" -> { npc.animate(npc.combatDef.attackAnimation); a.hitBarrier(column, 10) }
                        else -> { npc.animate(npc.combatDef.attackAnimation); a.hitBarrier(column, 6) }
                    }
                    task.wait(4)
                    continue
                }
                // Through the line: head for the Great Guardian.
                if (!npc.tile.isWithinRadius(Gotr.GUARDIAN_TILE, 3)) {
                    npc.walkTo(Tile(Gotr.GUARDIAN_TILE.x + world.random(2) - 1, Gotr.GUARDIAN_TILE.z + 3, 0))
                    task.wait(2)
                    continue
                }
                if (role == "leech") {
                    npc.animate(npc.combatDef.attackAnimation)
                    a.drainPower(Gotr.LEECH_DRAIN_PERCENT)
                    if (++bites >= 5) { world.remove(npc); return }
                    task.wait(4)
                } else {
                    // Walkers and guardians at the Guardian just gnaw for a while, then burn out.
                    npc.animate(npc.combatDef.attackAnimation)
                    a.drainPower(1)
                    if (++bites >= 8) { world.remove(npc); return }
                    task.wait(6)
                }
            }
        }
    }

    init {
        // Guardians are the only creature the engine fights for us; leeches and walkers ignore players.
        onNpcCombat(Gotr.LEECH_KEY) { npc.resetFacePawn(); npc.removeCombatTarget() }
        onNpcCombat(Gotr.WALKER_KEY) { npc.resetFacePawn(); npc.removeCombatTarget() }
    }
}
