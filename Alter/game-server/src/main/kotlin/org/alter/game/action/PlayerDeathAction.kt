package org.alter.game.action

import dev.openrune.cache.CacheManager.getAnim
import io.github.oshai.kotlinlogging.KotlinLogging
import net.rsprot.protocol.game.outgoing.sound.MidiJingle
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.instance.InstancedMap
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.stopMovement
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.Plugin
import org.alter.game.service.log.LoggerService
import java.lang.ref.WeakReference

/**
 * @author Tom <rspsmods@gmail.com>
 */
object PlayerDeathAction {
    private val logger = KotlinLogging.logger {}

    private const val DEATH_ANIMATION = 836

    /**
     * Remaps who gets credit for a player/bot kill before [KILLER_ATTR] is written — the twin of
     * [NpcDeathAction.killCreditResolver], for the player-death path. Content installs it (the
     * companion system points a companion's kills at its owner). Without it, when a hunter's
     * companion deals the most damage to a [PkBot] boss/rogue, KILLER_ATTR is the companion and the
     * bot-death consumers (Rogue Knight rank, rogue-hunt credit, loot keys) that reject `killer is
     * PkBot` silently drop the credit. Identity by default; only changes companion-dealt kills, so
     * real player-vs-player credit is untouched.
     */
    var killCreditResolver: (Pawn) -> Pawn = { it }

    val deathPlugin: Plugin.() -> Unit = {
        val player = ctx as Player

        player.interruptQueues()
        player.stopMovement()
        player.lock()

        player.queue(TaskPriority.STRONG) {
            death(player)
        }
    }

    private suspend fun QueueTask.death(player: Player) {
        val world = player.world
        val deathAnim = getAnim(DEATH_ANIMATION)
        // Captured up-front (before any teleport): if the player died inside an instance they leave
        // through its exit tile, otherwise they respawn at their home/respawn tile.
        val instancedMap = world.instanceAllocator.getMap(player.tile)

        /*
         * The pre-death phase runs arbitrary plugin hooks (item drops, PK stats, minigame cleanup)
         * and plays the death animation. If ANY of it throws, the coroutine's completion handler
         * merely logs it (QueueTask.resumeWith) and the task ends — so without this guard the
         * respawn-and-unlock below would be skipped and the player would be left FULL-locked.
         *
         * A locked player never leaves the death tile AND can never log out (Player.cycleBody gates
         * logout on lock.canLogout()), so they are stranded online forever and cannot log back in
         * ("already logged in") — exactly the "died in the Wizard Tower and can't log in" report.
         * The `finally` below guarantees they ALWAYS respawn and unlock, whatever a hook does.
         */
        try {
            player.write(MidiJingle(90))
            player.damageMap.getMostDamage()?.let { raw ->
                val killer = killCreditResolver(raw) // remap a companion's damage to its owner
                if (killer is Player) {
                    world.getService(LoggerService::class.java, searchSubclasses = true)?.logPlayerKill(killer, player)
                }
                player.attr[KILLER_ATTR] = WeakReference(killer)
            }

            world.plugins.executePlayerPreDeath(player)
            player.resetFacePawn()
            wait(2)
            player.animate(deathAnim.id)
            wait(deathAnim.cycleLength + 1)
        } catch (e: Exception) {
            logger.error(e) { "Death sequence failed for '${player.username}' — forcing respawn." }
        } finally {
            respawn(player, world, instancedMap)
        }

        // Post-death hooks run once the player is safely respawned and unlocked; isolate them so a
        // throw here can't undo the (already completed) respawn.
        runCatching { world.plugins.executePlayerDeath(player) }
            .onFailure { logger.error(it) { "executePlayerDeath failed for '${player.username}'." } }
    }

    /**
     * Restore stats, teleport to the respawn/exit tile, and unlock. Always runs — even if the death
     * sequence threw — so a player can never be stranded in a FULL lock (which would stop them
     * respawning and permanently block logout/re-login). [unlock] is the one step that must never be
     * skipped, so it runs last and unconditionally, outside the guarded teleport.
     */
    private fun respawn(player: Player, world: World, instancedMap: InstancedMap?) {
        runCatching {
            player.getSkills().restoreAll()
            player.animate(-1)
            if (instancedMap == null) {
                // Respawn at the player's custom respawn tile (e.g. their home city),
                // falling back to the global home tile when none is set.
                val respawn = player.attr[RESPAWN_TILE_ATTR]?.let { Tile(it) } ?: world.gameContext.home
                player.moveTo(respawn)
            } else {
                player.moveTo(instancedMap.exitTile)
                world.instanceAllocator.death(player)
            }
            player.writeMessage("Oh dear, you are dead!")
            player.attr.removeIf { it.resetOnDeath }
            player.timers.removeIf { it.resetOnDeath }
        }.onFailure { logger.error(it) { "Respawn teleport failed for '${player.username}'." } }
        player.unlock()
    }
}
