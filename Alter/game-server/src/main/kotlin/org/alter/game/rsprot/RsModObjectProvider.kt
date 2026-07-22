package org.alter.game.rsprot

import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvFull
import org.alter.game.model.item.Item

class RsModObjectProvider(val items: Array<Item?>) : UpdateInvFull.ObjectProvider {
    override fun provide(slot: Int): Long {
        val item = items[slot] ?: return InventoryObject.NULL
        // A bank placeholder is stored with the sentinel amount -2 (see Item.PLACEHOLDER_AMOUNT
        // and Bank.withdraw/removePlaceholder). The wire format encodes a placeholder as a count
        // of 0, so translate it rather than dropping the slot — passing -2 through makes rsprot
        // throw "Obj count cannot be below zero", which used to abort the owner's whole cycle
        // and leave them permanently desynced.
        if (item.amount == Item.PLACEHOLDER_AMOUNT) return InventoryObject(slot, item.id, 0)
        // Any other non-positive amount is genuine corruption with no meaning on the wire.
        // Render it as an empty slot instead of letting it throw.
        if (item.amount < 0) return InventoryObject.NULL
        return InventoryObject(slot, item.id, item.amount)
    }
}
