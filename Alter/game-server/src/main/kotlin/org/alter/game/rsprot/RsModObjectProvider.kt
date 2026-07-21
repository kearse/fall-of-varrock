package org.alter.game.rsprot

import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject
import net.rsprot.protocol.game.outgoing.inv.UpdateInvFull
import org.alter.game.model.item.Item

class RsModObjectProvider(val items: Array<Item?>) : UpdateInvFull.ObjectProvider {
    override fun provide(slot: Int): Long {
        val item = items[slot] ?: return InventoryObject.NULL
        // A non-positive stack is corrupt data (e.g. an amount underflowed below zero). rsprot's
        // buildInventory throws "Obj count cannot be below zero" on it, and since this provider
        // feeds every inventory/equipment/bank UpdateInvFull, that single bad stack aborts the
        // player's whole cycle every tick — which stops their varps and stats from ever being
        // sent (see Player.flushClientState). Render it as an empty slot instead of throwing.
        if (item.amount <= 0) return InventoryObject.NULL
        return InventoryObject(slot, item.id, item.amount)
    }
}
