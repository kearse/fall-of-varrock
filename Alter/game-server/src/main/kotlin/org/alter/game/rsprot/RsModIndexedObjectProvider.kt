package org.alter.game.rsprot

import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial
import org.alter.game.model.item.Item

class RsModIndexedObjectProvider(indices: Iterator<Int>, val items: Array<Item?>) : UpdateInvPartial.IndexedObjectProvider(indices) {
    override fun provide(slot: Int): Long {
        val item = items[slot] ?: return InventoryObject(slot, -1, -1)
        // See RsModObjectProvider: a non-positive stack is corrupt and makes rsprot throw, which
        // would abort the player's cycle every tick. Render such a slot as empty instead.
        if (item.amount <= 0) return InventoryObject(slot, -1, -1)
        return InventoryObject(slot, item.id, item.amount)
    }
}
