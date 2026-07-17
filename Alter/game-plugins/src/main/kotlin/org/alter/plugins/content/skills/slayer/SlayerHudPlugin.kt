package org.alter.plugins.content.skills.slayer

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.SLAYER_TASK_LEFT_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.attr.SLAYER_TASK_TOTAL_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Slayer dial feed** — keeps each player's active Slayer contract in front of them so they can see
 * how close they are to completing it. Publishes one packed varp [VARP] that the client's dial row
 * (`net.runelite.client.plugins.lofdials`) reads and draws as a circular gauge; same continuous-state
 * pattern as [org.alter.plugins.content.war.WarSupplyHudPlugin] (refresh timer + onLogin, sent only on
 * change, no custom packets).
 *
 * Packed layout (must match the client overlay):
 *   bits 0-11   killed (task total - remaining; 0-4095)
 *   bits 12-23  total  (count originally assigned; 0-4095)
 * Packed 0 (no task, or a task just completed) hides the dial.
 */
class SlayerHudPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = REFRESH_TICKS }
        onTimer(timer) {
            world.players.forEach { if (it.entityType.isHumanControlled) it.refreshSlayerDial() }
            world.timers[timer] = REFRESH_TICKS
        }
        onLogin { player.refreshSlayerDial() }
    }

    private fun Player.refreshSlayerDial() {
        val npc = attr[SLAYER_TASK_NPC_ATTR]
        val left = attr[SLAYER_TASK_LEFT_ATTR] ?: 0
        val total = (attr[SLAYER_TASK_TOTAL_ATTR] ?: 0).coerceIn(0, 4095)

        val packed = if (npc == null || total <= 0 || left <= 0) {
            0 // no active task — dial hidden
        } else {
            val killed = (total - left).coerceIn(0, total)
            killed or (total shl 12)
        }

        if (getVarp(VARP) != packed) setVarp(VARP, packed)
    }

    private companion object {
        /** Packed slayer varp the client dial reads (4616 — next free after companion status 4613-4615). */
        const val VARP = 4616
        const val REFRESH_TICKS = 3 // ~1.8s; matches the war-progress cadence
    }
}
