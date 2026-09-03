package org.alter.plugins.content.combat

import org.alter.api.SkullIcon
import org.alter.api.ext.hasSkullIcon
import org.alter.game.model.attr.PROTECT_ITEM_ATTR
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.economy.pk.LootKeys
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * The OSRS keep-N death rule as a PURE computation (docs/osrs-death-system.md): what a player
 * would keep and lose if they died right now. [PvpDeathDropPlugin] applies a [Plan] on death;
 * the PK kill-legitimacy guard ([org.alter.plugins.content.economy.pk.PkKillGuard]) reads
 * [riskedValue] to refuse Blood Money for victims who risked nothing. One source of truth for
 * the keep-N maths, so the guard and the drop can never disagree about what was at stake.
 *
 *  - unskulled: keep 3 (4 with Protect Item); skulled: keep 0 (1 with Protect Item)
 *  - untradeables are always kept and never use a keep slot
 *  - keep slots protect ITEMS (units), not stacks — a stack of blood runes keeps [Plan.keep]
 *    units and loses the rest
 *  - unclaimed loot keys are always lost (no keep slot, no Protect Item): their sealed contents
 *    are [Plan.keyLoot]; the key HANDLES themselves are ignored (never part of the risk)
 *
 * Nothing here mutates the player. Values come from the shared [ItemMarketValueService]
 * (falling back to the cache cost) — do not add a second price source.
 */
object DeathRisk {

    /** One held item and how the keep-N rule splits it. */
    class Slot(val container: ItemContainer, val slot: Int, val item: Item, val kept: Int, val lost: Int)

    class Plan(val keep: Int, val slots: List<Slot>, val keyLoot: List<Item>) {
        /** The lost portion of every held item (units), in value order. */
        val lostLoot: List<Item> get() = slots.filter { it.lost > 0 }.map { Item(it.item, it.lost) }
    }

    /** How many items the victim keeps under their current skull / Protect Item state. */
    fun keepCount(victim: Player): Int {
        val protectItem = victim.attr[PROTECT_ITEM_ATTR] == true
        val skulled = victim.hasSkullIcon(SkullIcon.WHITE) || victim.hasSkullIcon(SkullIcon.RED)
        return when {
            skulled && protectItem -> 1
            skulled -> 0
            protectItem -> 4
            else -> 3
        }
    }

    /** Per-item market value, falling back to the cache cost when the price service has none. */
    fun unitValue(price: ItemMarketValueService?, item: Item): Int {
        val market = price?.get(item.id) ?: 0
        return if (market > 0) market else (item.getDef().cost ?: 0)
    }

    /**
     * The keep/lose split for everything worn + carried (loot-key handles excluded) plus the
     * contents of any unclaimed loot keys. Read-only: apply it yourself (see PvpDeathDropPlugin).
     */
    fun plan(victim: Player, price: ItemMarketValueService?): Plan {
        val keep = keepCount(victim)
        val held = ArrayList<Triple<ItemContainer, Int, Item>>()
        for (i in 0 until victim.equipment.capacity) {
            victim.equipment[i]?.let { held += Triple(victim.equipment, i, it) }
        }
        for (i in 0 until victim.inventory.capacity) {
            victim.inventory[i]?.let { if (!LootKeys.isKeyItem(it.id)) held += Triple(victim.inventory, i, it) }
        }
        var keepLeft = keep
        val slots = held.filter { it.third.getDef().isTradeable }
            .sortedByDescending { unitValue(price, it.third) }
            .map { (container, slot, item) ->
                val protected = minOf(keepLeft, item.amount)
                keepLeft -= protected
                Slot(container, slot, item, kept = protected, lost = item.amount - protected)
            }
        val keyLoot = LootKeys.load(victim).flatMap { it.items }
        return Plan(keep, slots, keyLoot)
    }

    /** Total market value (gp) the victim would lose on death right now: lost units + sealed key loot. */
    fun riskedValue(victim: Player, price: ItemMarketValueService?): Long {
        val p = plan(victim, price)
        var total = 0L
        for (s in p.slots) if (s.lost > 0) total += s.lost.toLong() * unitValue(price, s.item)
        for (item in p.keyLoot) total += item.amount.toLong() * unitValue(price, item)
        return total
    }
}
