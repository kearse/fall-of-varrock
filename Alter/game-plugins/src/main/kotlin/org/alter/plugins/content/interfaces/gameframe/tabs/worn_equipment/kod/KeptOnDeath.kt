package org.alter.plugins.content.interfaces.kod

import org.alter.api.InterfaceDestination
import org.alter.api.SkullIcon
import org.alter.api.ext.*
import org.alter.game.model.World
import org.alter.game.model.attr.PROTECT_ITEM_ATTR
import org.alter.game.model.container.ContainerStackType
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * **Items Kept on Death.** Shows the player's *real* kept/lost items and risk value,
 * mirroring the actual PvP death rules in
 * [org.alter.plugins.content.combat.PvpDeathDropPlugin]:
 *  - unskulled: keep 3 (4 with Protect Item)
 *  - skulled:   keep 0 (1 with Protect Item)
 *
 * Previously this displayed hardcoded dummy items (368/324) and a "TODO" risk string.
 */
object KeptOnDeath {
    const val KOD_INTERFACE_ID = 4
    const val KOD_COMPONENT_ID = 5

    fun open(
        p: Player,
        world: World,
    ) {
        val priceService = world.getService(ItemMarketValueService::class.java)

        // Everything worn + carried.
        val held = ArrayList<Item>()
        for (i in 0 until p.equipment.capacity) p.equipment[i]?.let { held += it }
        for (i in 0 until p.inventory.capacity) p.inventory[i]?.let { held += it }

        val protectItem = p.attr[PROTECT_ITEM_ATTR] == true
        val skulled = p.hasSkullIcon(SkullIcon.WHITE) || p.hasSkullIcon(SkullIcon.RED)
        val keepCount = when {
            skulled && protectItem -> 1
            skulled -> 0
            protectItem -> 4
            else -> 3
        }

        fun value(item: Item): Long {
            val price = priceService?.get(item.id) ?: 0
            return (if (price > 0) price else (item.getDef().cost ?: 0)).toLong()
        }

        val sorted = held.sortedByDescending { value(it) }
        val kept = sorted.take(keepCount)
        val lost = sorted.drop(keepCount)
        val risk = lost.sumOf { value(it) * it.amount }

        val keptContainer = ItemContainer(capacity = 4, stackType = ContainerStackType.NO_STACK)
        kept.forEachIndexed { idx, item -> keptContainer[idx] = item }
        val lostContainer = ItemContainer(capacity = 50, stackType = ContainerStackType.NO_STACK)
        lost.forEachIndexed { idx, item -> if (idx < lostContainer.capacity) lostContainer[idx] = item }

        // 230/584 = items kept; 90/468 & 209/93 = items lost.
        p.sendItemContainer(interfaceId = -1, component = 230, key = 584, container = keptContainer)
        p.sendItemContainer(interfaceId = -1, component = 90, key = 468, container = lostContainer)
        p.sendItemContainer(interfaceId = -1, component = 209, key = 93, container = lostContainer)
        p.setInterfaceUnderlay(color = -1, transparency = -1)
        p.openInterface(interfaceId = KOD_INTERFACE_ID, dest = InterfaceDestination.MAIN_SCREEN)
        // Prominent 3-4 kept-item icons.
        p.runClientScript(
            org.alter.api.ClientScript(id = 972), 0, 0, 0, 0, "", keepCount,
            kept.getOrNull(0)?.id ?: -1,
            kept.getOrNull(1)?.id ?: -1,
            kept.getOrNull(2)?.id ?: -1,
            if (keepCount >= 4) kept.getOrNull(3)?.id ?: -1 else -1,
        )
        p.setInterfaceEvents(interfaceId = KOD_INTERFACE_ID, component = 12, range = 0..3, setting = 1)
        p.setComponentText(KOD_INTERFACE_ID, 18, "Guide risk value:<br><col=ffffff>${formatGp(risk)}</col> gp")
    }

    private fun formatGp(amount: Long): String = "%,d".format(amount)
}
