package org.alter.plugins.content.raids

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Drives the raid framework (R0): one world timer ticks every live [RaidController], and each
 * [Raids] definition gets an `::<key>` entry command that allocates a private [RaidInstance]
 * (solo-first), teleports the player in, and starts the encounter. Instances tear themselves
 * down (empty/logout/death); the controller removes itself from [active] when it ends.
 *
 * Real raids slot in by adding [RaidDefinition]s to [Raids]; this driver is encounter-agnostic.
 */
class RaidsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val active = mutableListOf<RaidController>()
    private val timer = TimerKey()

    init {
        onWorldInit { world.timers[timer] = 1 }
        onTimer(timer) {
            active.toList().forEach { runCatching { it.tick() }.onFailure { e -> logger.error(e) { "raid tick failed" } } }
            world.timers[timer] = 1
        }

        Raids.all.forEach { def ->
            onCommand(def.key, description = "Enter ${def.name}") { start(player, def) }
        }

        onLogout {
            active.firstOrNull { it.party.contains(player) }?.let { ctrl ->
                ctrl.party.remove(player)
            }
        }
    }

    private fun start(player: Player, def: RaidDefinition) {
        if (active.any { it.party.contains(player) }) {
            player.message("You're already in a raid.")
            return
        }
        val instance = RaidInstance.allocate(world, def.sourceArea, def.exitTile, player.uid)
        if (instance == null) {
            player.message("The instance space is full right now — try again shortly.")
            return
        }
        val controller = RaidController(world, mutableListOf(player), instance, def) { active.remove(it) }
        active += controller
        controller.begin()
    }
}
