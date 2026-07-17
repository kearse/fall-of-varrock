package org.alter.plugins.content.economy.pk

import dev.openrune.cache.CacheManager.getItem
import net.rsprot.protocol.game.outgoing.inv.UpdateInvFull
import org.alter.api.ClientScript
import org.alter.api.InterfaceDestination
import org.alter.api.ext.message
import org.alter.api.ext.openInterface
import org.alter.api.ext.runClientScript
import org.alter.api.ext.setComponentHidden
import org.alter.api.ext.setComponentText
import org.alter.api.ext.setInterfaceEvents
import org.alter.api.ext.setInterfaceUnderlay
import org.alter.api.ext.setVarbit
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.debugButtons
import org.alter.game.model.item.Item
import org.alter.game.rsprot.RsModObjectProvider

/**
 * Driver for the REAL OSRS **Wilderness Loot Chest** popup — stock cache interface **742**
 * (`WILDY_LOOT_CHEST`, titled "Wilderness Loot Key"), the one at Ferox Enclave.
 *
 * **The rev-228 UI is a ONE-KEY-AT-A-TIME viewer and is entirely SERVER-driven.** The per-key tab
 * strip people know from current OSRS was only added in a later revision (rev 237's
 * `wildy_loot_build_tabs` — not in our cache; verified against the decompiled rev-228 dump in
 * Joshua-F/osrs-dumps and runelite/cs2-scripts). At 228 the interface's own scripts do almost
 * nothing: `wildy_loot_init` (5914) just swaps the ironman destroy-text, and comp 24 re-renders
 * the Item/Note selection from varp 1299. Everything else is the server's job:
 *
 *  - **Item grid** = comp 4 (a bare 270x152 layer, hidden by default — 7x4 cells of 36x32 exactly).
 *    The server unhides it and runs the stock generic grid builder **script 149
 *    `interface_inv_init`**`(component, inv, columns, rows, dragMode, dragTarget, op1..op5)`,
 *    pointing it at the displayed key's Deadman loot container (gameval `DEADMAN_LOOT_INV0..4` =
 *    invs **558..562**, key slot i ↔ inv 558+i). 149 re-arms itself on inv transmit, so later
 *    `UpdateInvFull`s redraw the grid for free. Grid cell clicks arrive as IfButton on comp 4
 *    (slot = cell index = inv slot) once IfSetEvents allows the ops.
 *  - comp 5 = the static "This chest is now empty." text inside the grid layer — server-hidden
 *    while a key is displayed.
 *  - comp 6 = top-centre label (we show "victim's key (n/5)"); comps 29/31 = the used/capacity
 *    fraction in the bottom bar.
 *  - Static buttons (cache dump, ids match the rev-228 if3 source): 9=Destroy,
 *    20=Withdraw-all-to-inventory, 34=Withdraw-all-to-bank, 25=Item mode, 26=Note mode.
 *  - Varp 1299 (`DEADMAN_LOOT`) is UI STATE: varbit 4843 `DEADMAN_LOOT_WITHDRAWNOTES` (bit 31) is
 *    the note-mode flag comp 24 renders (varbit 4842 is the tab index of the LATER revisions'
 *    UI). Never write raw values into this varp — the original bug here was sending the total
 *    sealed gp value, which corrupted the state varbits.
 */
object LootChestInterface {
    const val IFACE = 742

    private const val INV_BASE = 558 // DEADMAN_LOOT_INV0 — key slot i ↔ inv 558+i
    private const val KEY_SLOTS = 5

    const val COMP_DESTROY = 9
    const val COMP_WITHDRAW_INV = 20
    const val COMP_MODE_ITEM = 25
    const val COMP_MODE_NOTE = 26
    const val COMP_WITHDRAW_BANK = 34

    /** The dynamic item grid layer + its static children (rev-228 if3 dump). */
    const val COMP_GRID = 4
    private const val COMP_EMPTY_TEXT = 5
    private const val COMP_KEY_LABEL = 6
    private const val COMP_COUNT_USED = 29
    private const val COMP_COUNT_CAP = 31

    private const val VARBIT_WITHDRAW_NOTES = 4843

    /** Stock generic inv-grid builder (rev-228 `interface_inv_init`). */
    private val INTERFACE_INV_INIT = ClientScript(id = 149)

    private const val GRID_COLS = 7
    private const val GRID_ROWS = 4

    /** IfSetEvents mask: op1 (Withdraw), op2 (Bank), op10 (Examine). */
    private const val GRID_OPS = (1 shl 1) or (1 shl 2) or (1 shl 10)

    private class Session(var noted: Boolean = false)

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    fun open(p: Player) {
        sessions[p.index] = Session()
        sendContents(p)
        p.setInterfaceUnderlay(color = -1, transparency = -1)
        p.openInterface(IFACE, InterfaceDestination.MAIN_SCREEN)
        drawCurrent(p)
    }

    /** The key the chest currently displays — always the oldest bundle. */
    fun currentBundle(p: Player): LootKeys.Bundle? = LootKeys.load(p).firstOrNull()

    /** Push all five key containers + the note-mode state varbit. */
    fun sendContents(p: Player) {
        val bundles = LootKeys.load(p)
        for (slot in 0 until KEY_SLOTS) {
            val bundle = bundles.firstOrNull { LootKeys.keySlot(it.keyId) == slot }
            val items: Array<Item?> = bundle?.items?.toTypedArray() ?: emptyArray()
            p.write(UpdateInvFull(inventoryId = INV_BASE + slot, capacity = items.size, provider = RsModObjectProvider(items)))
        }
        p.setVarbit(VARBIT_WITHDRAW_NOTES, if (sessions[p.index]?.noted == true) 1 else 0)
    }

    /**
     * (Re)draw the displayed key: run the grid builder against its inv, enable its click ops and
     * fill the labels — or show the "chest is now empty" state when no keys remain. Call after
     * every mutation; switching keys (the displayed one emptied) is just a redraw against the
     * next bundle's inv.
     */
    fun drawCurrent(p: Player) {
        val bundles = LootKeys.load(p)
        val bundle = bundles.firstOrNull()
        val slot = bundle?.let { LootKeys.keySlot(it.keyId).coerceAtLeast(0) } ?: 0
        p.setComponentHidden(IFACE, COMP_GRID, hidden = false)
        p.setComponentHidden(IFACE, COMP_EMPTY_TEXT, hidden = bundle != null)
        p.runClientScript(
            INTERFACE_INV_INIT,
            (IFACE shl 16) or COMP_GRID,
            INV_BASE + slot,
            GRID_COLS,
            GRID_ROWS,
            0, // drag mode: none
            -1, // drag target: none
            "Withdraw", "Bank", "", "", "",
        )
        p.setInterfaceEvents(IFACE, COMP_GRID, 0..(GRID_COLS * GRID_ROWS), GRID_OPS)
        if (bundle == null) {
            p.setComponentText(IFACE, COMP_KEY_LABEL, "")
            p.setComponentText(IFACE, COMP_COUNT_USED, "0")
        } else {
            p.setComponentText(IFACE, COMP_KEY_LABEL, "${bundle.victim} (${bundles.size}/$KEY_SLOTS)")
            p.setComponentText(IFACE, COMP_COUNT_USED, "${bundle.items.size}")
        }
        p.setComponentText(IFACE, COMP_COUNT_CAP, "${LootKeys.MAX_STACKS}")
    }

    fun setNoted(p: Player, noted: Boolean) {
        sessions.getOrPut(p.index) { Session() }.noted = noted
        p.setVarbit(VARBIT_WITHDRAW_NOTES, if (noted) 1 else 0)
        p.message(if (noted) "Withdrawing as notes." else "Withdrawing as items.")
    }

    /** Grid cell click: take (or bank) ONE item stack from the displayed key. */
    fun onGridClick(p: Player, slot: Int, option: Int) {
        val bundle = currentBundle(p) ?: return
        val noted = sessions[p.index]?.noted == true
        when (option) {
            1 -> LootKeys.takeOne(p, bundle.keyId, slot, toBank = false, noted = noted)
            2 -> LootKeys.takeOne(p, bundle.keyId, slot, toBank = true)
            10 -> {
                bundle.items.getOrNull(slot)?.let {
                    p.message(runCatching { getItem(it.id).name }.getOrDefault("item ${it.id}"))
                }
                return
            }
            else -> {
                debugClick(p, COMP_GRID, option, slot)
                return
            }
        }
        sendContents(p)
        drawCurrent(p)
    }

    /** "Withdraw all to X" empties the DISPLAYED key (the chest shows one key at a time). */
    fun withdrawAll(p: Player, toBank: Boolean) {
        val noted = sessions[p.index]?.noted == true
        val bundle = currentBundle(p) ?: run {
            p.message("The chest holds no loot for you.")
            return
        }
        LootKeys.takeAll(p, bundle.keyId, toBank = toBank, noted = noted)
        sendContents(p)
        drawCurrent(p)
    }

    fun onClosed(p: Player) {
        sessions.remove(p.index)
    }

    /**
     * Discovery aid for unwired components — reported in chat only to a player who has opted into
     * chatbox debug diagnostics (`::debug`). Silent for everyone else so ordinary players never see it.
     */
    fun debugClick(p: Player, comp: Int, option: Int, slot: Int) {
        if (!p.debugButtons) return
        p.message("<col=808080>[loot chest] comp=$comp opt=$option slot=$slot</col>")
    }
}
