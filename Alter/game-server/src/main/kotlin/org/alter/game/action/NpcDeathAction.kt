package org.alter.game.action

import dev.openrune.cache.CacheManager.getAnim
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.action.NpcDeathAction.reset
import org.alter.game.info.NpcInfo
import org.alter.game.model.LockState
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.combat.NpcAnims
import org.alter.game.model.entity.AreaSound
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.stopMovement
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.model.weightedTableBuilder.roll
import org.alter.game.plugin.Plugin
import org.alter.game.service.log.LoggerService
import java.lang.ref.WeakReference

/**
 * This class is responsible for handling npc death events.
 *
 * @author Tom <rspsmods@gmail.com>
 */
object NpcDeathAction {
    private val logger = KotlinLogging.logger {}

    var deathPlugin: Plugin.() -> Unit = {
        val npc = ctx as Npc
        if (!npc.world.plugins.executeNpcFullDeath(npc)) {
            npc.interruptQueues()
            npc.stopMovement()
            npc.lock()
            npc.queue(TaskPriority.STRONG) {
                death(npc)
            }
        }
    }

    suspend fun QueueTask.death(npc: Npc) {
        val world = npc.world
        // Coerce to animations this npc's skeleton can actually play — the DEFAULT def's 836 is a
        // human death, and a monster given it collapses into a deformed heap. See [NpcAnims].
        val deathAnimation = npc.combatDef.deathAnimation
            .map { NpcAnims.coerceDeath(npc.id, it) }
            .filter { it != -1 }
        val deathSound = npc.combatDef.defaultDeathSound
        val respawnDelay = npc.combatDef.respawnDelay
        var killer: Pawn? = null
        npc.damageMap.getMostDamage()?.let {
            if (it is Player) {
                killer = it
                world.getService(LoggerService::class.java, searchSubclasses = true)?.logNpcKill(it, npc)
            }
            npc.attr[KILLER_ATTR] = WeakReference(it)
        }
        NpcInfo(npc).setAllOpsInvisible()
        world.plugins.executeNpcPreDeath(npc)
        npc.resetFacePawn()
        if (npc.combatDef.defaultDeathSoundArea) {
            world.spawn(AreaSound(npc.tile, deathSound, npc.combatDef.defaultDeathSoundRadius, npc.combatDef.defaultDeathSoundVolume))
        } else {
            (killer as? Player)?.playSound(deathSound, npc.combatDef.defaultDeathSoundVolume)
        }

        /**
         * @TODO add interruption for this block if we would want to execute a plugin during it's death animation
         */
        deathAnimation.forEach { anim ->
            // A bad/missing anim id must not abort the death sequence: everything downstream —
            // the kill counters on [anyNpcDeath], loot, respawn — hangs off finishing this loop.
            val def = try {
                getAnim(anim)
            } catch (e: Exception) {
                logger.error(e) { "Death animation $anim unresolved for npc ${npc.id} '${npc.name}' — skipping it." }
                return@forEach
            }
            npc.animate(def.id, def.cycleLength)
            wait(def.cycleLength)
        }
        world.plugins.executeNpcDeath(npc)
        // The additive death hooks (Slayer/rogue-hunt/quest kill counters, generic drops, Discord
        // boss feed, …) are independent cross-cutting listeners. A throw in one used to abort the
        // whole forEach, silently starving EVERY later listener of the kill — the classic "the
        // server randomly stops counting a task/quest" bug, and order-dependent to boot. Isolate
        // each handler so one bad listener can't disable the others (mirrors [Pawn.hitsCycle]).
        world.plugins.anyNpcDeath.forEach {
            try {
                npc.executePlugin(it)
            } catch (e: Exception) {
                logger.error(e) { "An onAnyNpcDeath handler threw for npc ${npc.id} '${npc.name}' — other death handlers still ran." }
            }
        }
        if (npc.respawns) {
            NpcInfo(npc).setInaccessible(true)
            npc.reset()
            wait(respawnDelay)
            NpcInfo(npc).setAllOpsVisible()
            NpcInfo(npc).setInaccessible(false)
            world.plugins.executeNpcSpawn(npc)
        } else {
            world.remove(npc)
        }
    }
    private fun Npc.reset() {
        lock = LockState.NONE
        moveTo(spawnTile)
        attr.clear()
        timers.clear()
        world.setNpcDefaults(this)
    }
}
