package org.alter.game.rsprot

import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial
import org.alter.game.model.item.Item

class RsModIndexedObjectProvider(indices: Iterator<Int>, val items: Array<Item?>) : UpdateInvPartial.IndexedObjectProvider(indices) {
    override fun provide(slot: Int): Long {
        val item = items[slot] ?: return InventoryObject(slot, -1, -1)
        // See RsModObjectProvider: a bank placeholder (amount -2) goes on the wire as count 0,
        // and any other negative amount is corruption rendered as an empty slot — either way we
        // must not hand a negative count to rsprot, which throws and aborts the player's cycle.
        if (item.amount == Item.PLACEHOLDER_AMOUNT) return InventoryObject(slot, item.id, 0)
        if (item.amount < 0) return InventoryObject(slot, -1, -1)
        return InventoryObject(slot, item.id, item.amount)
    }
}
