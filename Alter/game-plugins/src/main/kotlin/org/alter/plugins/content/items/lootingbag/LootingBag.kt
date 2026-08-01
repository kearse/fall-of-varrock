package org.alter.plugins.content.items.lootingbag

import org.alter.api.ext.transfer
import org.alter.game.model.container.ContainerStackType
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.container.key.ContainerKey
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item

/**
 * Shared looting-bag state used by both [LootingBagPlugin] and the bank's deposit handler
 * (clicking the bag while a bank is open empties it into the bank — see BankPlugin).
 */
object LootingBag {
    val CONTAINER_KEY = ContainerKey("looting_bag", capacity = 28, stackType = ContainerStackType.NORMAL)
    const val CONTAINER_ID = 516

    /** The bag's contents container, created on first use. */
    fun containerOf(p: Player): ItemContainer = p.containers.computeIfAbsent(CONTAINER_KEY) { ItemContainer(CONTAINER_KEY) }

    /**
     * Move everything in [container] into [p]'s bank. Iterates by slot and passes `fromSlot` so
     * duplicate-id stacks in different slots each transfer (an id-scan from slot 0 hits a stale
     * item and silently moves nothing). Returns true if ANY item moved; leftovers mean bank full.
     */
    fun bankAll(p: Player, container: ItemContainer): Boolean {
        var any = false
        for (slot in 0 until container.capacity) {
            val item = container[slot] ?: continue
            val moved = container.transfer(p.bank, item = Item(item, item.amount).copyAttr(item), fromSlot = slot, unnote = true)?.completed ?: 0
            if (moved > 0) any = true
        }
        return any
    }
}
