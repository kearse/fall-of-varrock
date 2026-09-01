package org.alter.api.ext

import org.alter.api.BonusSlot
import org.alter.api.HitType
import org.alter.api.HitbarType
import org.alter.api.PrayerIcon
import org.alter.game.model.Hit
import org.alter.game.model.attr.*
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.stopMovement
import org.alter.game.model.timer.FREEZE_IMMUNITY_TIMER
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.model.timer.STUN_TIMER

/** OSRS: after a freeze expires, the target cannot be re-frozen for 5 ticks (3s). */
const val FREEZE_IMMUNITY_TICKS = 5

fun Pawn.getCommandArgs(): Array<String> = attr[COMMAND_ARGS_ATTR]!!

fun Pawn.getInteractingSlot(): Int = attr[INTERACTING_SLOT_ATTR]!!

fun Pawn.getInteractingItem(): Item = attr[INTERACTING_ITEM]!!.get()!!

fun Pawn.getInteractingItemId(): Int = attr[INTERACTING_ITEM_ID]!!

fun Pawn.getInteractingItemSlot(): Int = attr[INTERACTING_ITEM_SLOT]!!

fun Pawn.getInteractingOption(): Int = attr[INTERACTING_OPT_ATTR]!!

fun Pawn.getInteractingGameObj(): GameObject = attr[INTERACTING_OBJ_ATTR]!!.get()!!

fun Pawn.getInteractingNpc(): Npc = attr[INTERACTING_NPC_ATTR]!!.get()!!

fun Pawn.getInteractingPlayer(): Player = attr[INTERACTING_PLAYER_ATTR]!!.get()!!

/** Null-safe variant: the WeakReference can clear if the interacting player logged off/GC'd
 *  between the click and processing, and the `!!` form NPEs the whole game cycle. */
fun Pawn.getInteractingPlayerOrNull(): Player? = attr[INTERACTING_PLAYER_ATTR]?.get()

fun Pawn.hasPrayerIcon(icon: PrayerIcon): Boolean = prayerIcon == icon.id

fun Pawn.getBonus(slot: BonusSlot): Int = equipmentBonuses[slot.id]

fun Pawn.hit(
    damage: Int,
    type: HitType = if (damage == 0) HitType.BLOCK else HitType.HIT,
    delay: Int = 0,
    attackersIndex: Int = -1
): Hit {
    val hit =
        Hit.Builder()
            .setDamageDelay(delay)
            .addHit(damage = damage, type = type.id, attackersIndex)
            .setHitbarMaxPercentage(HitbarType.NORMAL.pixelsWide)
            .setAttackerIndex(attackersIndex)
            .build()
    addHit(hit)
    return hit
}

fun Pawn.freeze(
    cycles: Int,
    onFreeze: () -> Unit,
) {
    // The immunity timer runs for the freeze duration + 5 ticks, so it also covers the
    // "can't be re-frozen while frozen" case and the post-thaw immunity window.
    if (timers.has(FROZEN_TIMER) || timers.has(FREEZE_IMMUNITY_TIMER)) {
        return
    }
    stopMovement()
    timers[FROZEN_TIMER] = cycles
    timers[FREEZE_IMMUNITY_TIMER] = cycles + FREEZE_IMMUNITY_TICKS
    onFreeze()
}

fun Pawn.freeze(cycles: Int) {
    freeze(cycles) {
        if (this is Player) {
            this.message("You have been frozen.")
        }
    }
}

fun Pawn.stun(
    cycles: Int,
    onStun: () -> Unit,
): Boolean {
    if (timers.has(STUN_TIMER)) {
        return false
    }
    stopMovement()
    timers[STUN_TIMER] = cycles
    onStun()
    return true
}

fun Pawn.stun(cycles: Int) {
    stun(cycles) {
        if (this is Player) {
            graphic(245, 124)
            resetInteractions()
            interruptQueues()
            message("You have been stunned!")
        }
    }
}
