package org.alter.game.saving.impl

import dev.openrune.cache.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Client
import org.alter.game.model.item.Item
import org.alter.game.saving.DocumentHandler
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * Verdict for a single persisted container slot. Kept as a pure function of its inputs so the
 * sanitation policy is unit-testable without a [Client] or a mounted cache.
 */
internal sealed class SlotVerdict {
    object Keep : SlotVerdict()

    data class Drop(val reason: String) : SlotVerdict()
}

/**
 * Decide whether a persisted container entry may be loaded.
 *
 * A bank placeholder is stored numerically as [Item.PLACEHOLDER_AMOUNT] and is legitimate — it
 * must survive sanitation (dropping it at a write chokepoint was the mistake that wiped players'
 * bank layouts in PR #139). Everything else with a non-positive amount is corruption: `remove()`
 * nulls a slot the instant it reaches 0, so no code path stores a 0 or negative stack on purpose.
 * An id absent from the mounted cache can never be rendered, deposited or examined — every def
 * lookup on it throws — so it is dead data that silently breaks any loop that touches it.
 */
internal fun validateSlot(
    slot: Int?,
    capacity: Int,
    item: Item?,
    itemExists: (Int) -> Boolean,
): SlotVerdict =
    when {
        slot == null || slot < 0 || slot >= capacity -> SlotVerdict.Drop("slot out of bounds (capacity=$capacity)")
        item == null -> SlotVerdict.Drop("unparseable item document")
        !itemExists(item.id) -> SlotVerdict.Drop("item id not in cache")
        item.amount == Item.PLACEHOLDER_AMOUNT -> SlotVerdict.Keep
        item.amount <= 0 -> SlotVerdict.Drop("non-positive amount")
        else -> SlotVerdict.Keep
    }

/**
 * If more entries than this drop from a single container, something systemic is wrong (most
 * likely the server booted against a wrong or outdated cache) — flag it loudly instead of
 * letting the sanitiser quietly eat a whole bank.
 */
private const val DROP_ALARM_THRESHOLD = 10

class ContainersSerialisation(override val name: String = "containers") : DocumentHandler {

    override fun fromDocument(client: Client, doc: Document) {
        doc.forEach { (containerKey, itemsList) ->
            val key = client.world.plugins.containerKeys.firstOrNull { it.name == containerKey }

            if (key == null) {
                logger.error { "Container found in serialized data, but is not registered to our World. [key=$containerKey]" }
                return@forEach
            }

            val container = client.containers.getOrPut(key) { ItemContainer(key) }

            if (itemsList is Document) {
                var dropped = 0
                itemsList.forEach { (slotString, itemDoc) ->
                    val slot = slotString.toIntOrNull()
                    val item = runCatching { Item.fromDocument(itemDoc as Document) }.getOrNull()
                    when (val verdict = validateSlot(slot, container.capacity, item, CacheManager::itemExists)) {
                        is SlotVerdict.Keep -> container[slot!!] = item
                        is SlotVerdict.Drop -> {
                            dropped++
                            logger.error {
                                "DROPPED corrupt container entry on load: player=${client.loginUsername} " +
                                    "container=$containerKey slot=$slotString reason=${verdict.reason} raw=$itemDoc"
                            }
                        }
                    }
                }
                if (dropped > DROP_ALARM_THRESHOLD) {
                    logger.error {
                        "SANITATION ALARM: dropped $dropped entries from a single container — check that the " +
                            "correct cache is mounted before letting players save! player=${client.loginUsername} " +
                            "container=$containerKey"
                    }
                }
            }
        }
    }

    override fun asDocument(client: Client): Document {
        return Document().apply {
            client.getPersistentContainers().forEach { container ->
                val itemsDoc = Document()
                container.items.forEach { (slot, item) ->
                    itemsDoc.append(slot.toString(), item.asDocument())
                }
                put(container.name, itemsDoc)
            }
        }
    }

    private fun Client.getPersistentContainers(): List<PersistentContainer> {
        val persistent = mutableListOf<PersistentContainer>()

        containers.forEach { (key, container) ->
            if (!container.isEmpty) {
                persistent.add(PersistentContainer(key.name, container.toMap()))
            }
        }

        return persistent
    }

    data class PersistentContainer(val name: String, val items: Map<Int, Item>)

}
