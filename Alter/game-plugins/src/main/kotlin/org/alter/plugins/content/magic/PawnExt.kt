package org.alter.plugins.content.magic

import dev.openrune.cache.CacheManager.getAnim
import org.alter.api.ext.getWildernessLevel
import org.alter.api.ext.message
import org.alter.game.model.LockState
import org.alter.game.model.Tile
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.TaskPriority

fun Player.canTeleport(type: TeleportType): Boolean {
    val currWildLvl = tile.getWildernessLevel()
    val wildLvlRestriction = type.wildLvlRestriction

    if (!lock.canTeleport()) {
        return false
    }

    // Tele Block prevents all teleport methods until it wears off.
    if (TeleBlock.isBlocked(this)) {
        message("A magical force has stopped you from teleporting.")
        return false
    }

    if (currWildLvl > wildLvlRestriction) {
        message("A mysterious force blocks your teleport spell!")
        message("You can't use this teleport after level $wildLvlRestriction wilderness.")
        return false
    }

    return true
}

fun Pawn.prepareForTeleport() {
    resetInteractions()
    clearHits()
}

fun Pawn.teleport(
    endTile: Tile,
    type: TeleportType,
) {
    lock = LockState.FULL_WITH_DAMAGE_IMMUNITY

    queue(TaskPriority.STRONG) {
        /*
         * The lock above is only ever released by this task, so it must be released on EVERY
         * way out, or the pawn is stranded in a FULL lock forever — frozen in place, unable to
         * act or log out, with every re-login rejected as "already logged in" (the "teleported
         * while under attack and my account got nulled" report).
         *
         * Two escapes need covering beyond normal completion:
         *  - terminate(): anything queueing a STRONG task (death's interruptQueues, a forced
         *    logout) drops this coroutine mid-suspension — `finally` does NOT run then, only
         *    [QueueTask.terminateAction] does. Restore the lock there, but only if it is still
         *    OUR lock: death re-locks the pawn right after interrupting, and that lock must win.
         *  - a throw mid-task (e.g. a cache anim with no cycle length): `finally` covers it.
         */
        terminateAction = {
            if (lock == LockState.FULL_WITH_DAMAGE_IMMUNITY) {
                unlock()
            }
        }
        try {
            prepareForTeleport()

            animate(type.animation)
            type.graphic?.let {
                graphic(it)
            }

            wait(type.teleportDelay)

            // Snap off any blocked/wall tile so a teleport can never drop the pawn inside geometry.
            // This is the single funnel every teleport (portal, spellbook, item, minigame) passes
            // through — hardening it here covers them all. No-op when the destination is already clear.
            moveTo(world.snapToWalkable(endTile))

            type.endAnimation?.let {
                animate(it)
            }

            type.endGraphic?.let {
                graphic(it)
            }

            type.endAnimation?.let {
                val def = getAnim(it)
                // wait() rejects non-positive cycles with a throw — a zero-length anim def must
                // not abort the task (the finally would unlock, but the end-animation would hang).
                if (def.cycleLength > 0) {
                    wait(def.cycleLength)
                }
            }

            animate(-1)
        } finally {
            if (lock == LockState.FULL_WITH_DAMAGE_IMMUNITY) {
                unlock()
            }
        }
    }
}
