package org.alter.game.saving.impl

import org.alter.game.model.item.Item
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Policy tests for the load-time container sanitation. Everything here runs cache-free:
 * item existence is injected, and constructing an [Item] never touches the cache.
 */
class ContainerSanitationTests {
    private val allKnown: (Int) -> Boolean = { true }
    private val noneKnown: (Int) -> Boolean = { false }

    private fun keep(verdict: SlotVerdict) = assertTrue(verdict is SlotVerdict.Keep, "expected Keep, got $verdict")

    private fun drop(verdict: SlotVerdict) = assertTrue(verdict is SlotVerdict.Drop, "expected Drop, got $verdict")

    @Test
    fun keepsOrdinaryStacks() {
        keep(validateSlot(0, 800, Item(4151, 1), allKnown))
        keep(validateSlot(799, 800, Item(995, Int.MAX_VALUE), allKnown))
    }

    @Test
    fun keepsBankPlaceholders() {
        // -2 is the bank placeholder sentinel — dropping it was PR #139's bank-wiping mistake.
        keep(validateSlot(12, 800, Item(19272, Item.PLACEHOLDER_AMOUNT), allKnown))
    }

    @Test
    fun dropsPlaceholderWithUnknownId() {
        drop(validateSlot(12, 800, Item(19272, Item.PLACEHOLDER_AMOUNT), noneKnown))
    }

    @Test
    fun dropsNonPositiveAmounts() {
        drop(validateSlot(0, 800, Item(4151, 0), allKnown))
        drop(validateSlot(0, 800, Item(4151, -1), allKnown))
        drop(validateSlot(0, 800, Item(4151, -3), allKnown))
        drop(validateSlot(0, 800, Item(4151, Int.MIN_VALUE), allKnown))
    }

    @Test
    fun dropsUnknownItemIds() {
        drop(validateSlot(0, 800, Item(999999, 1), noneKnown))
    }

    @Test
    fun dropsOutOfBoundsSlots() {
        drop(validateSlot(null, 800, Item(4151, 1), allKnown))
        drop(validateSlot(-1, 800, Item(4151, 1), allKnown))
        drop(validateSlot(800, 800, Item(4151, 1), allKnown))
        drop(validateSlot(1200, 800, Item(4151, 1), allKnown))
    }

    @Test
    fun dropsUnparseableItems() {
        drop(validateSlot(0, 800, null, allKnown))
    }
}
