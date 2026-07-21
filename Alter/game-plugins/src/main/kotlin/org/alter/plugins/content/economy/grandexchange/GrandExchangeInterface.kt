package org.alter.plugins.content.economy.grandexchange

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.InterfaceDestination
import org.alter.api.ext.message
import org.alter.api.ext.openInterface
import org.alter.api.ext.searchItemInput
import org.alter.api.ext.setInterfaceUnderlay
import org.alter.api.ext.setVarbit
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.debugButtons

/**
 * **PROTOTYPE** driver for the REAL OSRS **Grand Exchange** interface — stock cache group **465**
 * (`GRAND_EXCHANGE`, the 8-slot offer board + collection box).
 *
 * This is **Phase 0** of the GE build. Its only jobs are to answer two questions before we invest in
 * the full offer engine:
 *
 *  1. **Does interface 465 render server-driven on our custom client?** We already drive stock cache
 *     interfaces this way (the Wilderness Loot Chest = group 742, see [org.alter.plugins.content.economy.pk.LootChestInterface]),
 *     so this should "just work" — but the GE is bigger and worth confirming empirically. [open] opens
 *     it; if the player sees the GE board, native rendering is proven.
 *  2. **Does the existing `::item` search feed it?** [searchForOffer] reuses the exact chatbox search
 *     the `::item` command uses (`searchItemInput` → the `GE_SEARCH_ITEMS` client script), so the GE
 *     item lookup is the OSRS GE search with zero new UI.
 *
 * No economy/persistence logic lives here yet — that lands once the component map below is verified.
 *
 * **Component map (TO VERIFY in-game).** The definitive rev-228 GE component/script/varp ids must be
 * confirmed against the running cache, exactly as the Loot Chest's ids were discovered. The plugin
 * registers [debugClick] across the board's top-level components: open the GE with `::debug` on, click
 * each slot/button, and the server logs `comp/op/slot`. That mapping becomes the constants the full
 * offer engine drives (slot buttons, quantity/price steppers, confirm, collect, abort).
 */
object GrandExchangeInterface {
    /** Cache interface group for the Grand Exchange board. The core thing this prototype confirms. */
    const val IFACE = 465

    /**
     * Real OSRS varbit `GE_OFFER_CREATION_TYPE` (buy = 1, sell = 2) — the offer-setup mode flag the
     * native GE reads when a slot opens. Set best-effort here; wrapped since the full setup-script
     * sequence is still unverified (see the component map note above).
     */
    private const val VARBIT_OFFER_TYPE = 4397

    private val opened = HashSet<Int>()

    fun isOpen(p: Player): Boolean = p.index in opened

    /** Open the native GE board on the main screen. If it renders, native 465 is viable. */
    fun open(p: Player) {
        opened.add(p.index)
        p.setInterfaceUnderlay(color = -1, transparency = -1)
        p.openInterface(IFACE, InterfaceDestination.MAIN_SCREEN)
        p.message("<col=801700>Grand Exchange prototype: opened interface $IFACE.</col>")
        p.message("<col=801700>If the GE board is visible, native rendering works. Use ::debug then click slots to map components.</col>")
    }

    /**
     * Reuse the `::item` search box to pick an item for an offer, proving the search → offer flow.
     * Reports the item + its guide price (cache value) so we can see the search returns correctly.
     */
    fun searchForOffer(p: Player, buy: Boolean) {
        p.queue {
            val itemId = searchItemInput(p, "Grand Exchange: search for an item")
            if (itemId <= 0) return@queue
            val name = runCatching { getItem(itemId).name }.getOrDefault("item $itemId")
            val guide = runCatching { maxOf(1, getItem(itemId).cost) }.getOrDefault(1)
            p.message("<col=801700>GE search returned:</col> $name (id $itemId) — guide $guide gp — mode ${if (buy) "BUY" else "SELL"}.")
            // Best-effort: flag the native offer-setup mode. The remaining setup-script wiring is
            // deferred until the component map is verified.
            runCatching { p.setVarbit(VARBIT_OFFER_TYPE, if (buy) 1 else 2) }
        }
    }

    fun onClosed(p: Player) {
        opened.remove(p.index)
    }

    /**
     * Discovery aid — reports GE component clicks in chat, but ONLY to a player who opted into
     * chatbox diagnostics (`::debug`). Silent for everyone else. This is how we map the rev-228 GE
     * component ids to real constants (same approach as the Loot Chest).
     */
    fun debugClick(p: Player, comp: Int, option: Int, slot: Int) {
        if (!p.debugButtons) return
        p.message("<col=808080>[grand exchange] comp=$comp opt=$option slot=$slot</col>")
    }
}
