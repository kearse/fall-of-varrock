package org.alter.plugins.content.kits

import org.alter.api.EquipmentType
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item

/**
 * Server driver for the **kit editor** client overlay (`net.runelite.client.plugins.lofkit`) — the
 * LMS-style loadout screen: equipment paper-doll + full 28-slot inventory + item palette + presets
 * + three per-account save slots + spellbook (and, in training mode, sparring-bot difficulty).
 *
 * Same transport contract as the duel rules screen — no custom packets:
 *  - State flows DOWN as varps: [CONTROL_VARP] (open/mode/book/diff/saved-slot flags) and one varp
 *    per kit slot ([SLOT_VARP_BASE] + i): 11 worn slots in [SLOT_IDS] order, then the 28 inventory
 *    slots. Each slot varp packs `itemId | (qty << 16)` (qty capped at [KitSetup.MAX_QTY]).
 *  - Clicks flow UP as `::kit <action>` public-chat commands (→ the `kitclick` command).
 *
 * Two modes:
 *  - [Mode.TRAINING] — opened by the PK trainer. Adds are validated against [KitArmoury.pool]
 *    (loaner gear comes from a curated armoury, not thin air of ANY item). "Start bout" hands the
 *    kit to the [onStart] callback (the trainer loans it out and starts the fight).
 *  - [Mode.BANK] — opened at a bank. Any item id is allowed IN THE KIT (the palette is the player's
 *    own bank, client-side); loading only ever withdraws items actually present in their bank, so
 *    an arbitrary saved id is harmless. "Load kit" is handled by the plugin's bank loader.
 *  - [Mode.LMS] — Lisa's "Build my kit". LMS keeps its category balance (ONE pick per category),
 *    so the editor becomes a visual PICKER: the palette tabs are the five LmsKits categories, each
 *    tile is a choice's representative item, a click selects that whole bundle (persisted at once,
 *    like the old wizard), and the doll + inventory render a live preview of the resulting spawn
 *    loadout. Slot clicks / presets / save slots / book / difficulty are all inert in this mode.
 */
object KitEditor {
    const val CONTROL_VARP = 4640
    const val SLOT_VARP_BASE = 4641 // 11 equipment varps, then 28 inventory varps (39 total)
    const val EQUIP_SLOTS = 11

    /** Paper-doll order (doll index → EquipmentType id) — MUST match the client overlay. */
    val SLOT_IDS = intArrayOf(
        EquipmentType.HEAD.id, EquipmentType.CAPE.id, EquipmentType.AMULET.id, EquipmentType.WEAPON.id,
        EquipmentType.CHEST.id, EquipmentType.SHIELD.id, EquipmentType.LEGS.id, EquipmentType.GLOVES.id,
        EquipmentType.BOOTS.id, EquipmentType.RING.id, EquipmentType.AMMO.id,
    )

    enum class Mode { TRAINING, BANK, LMS }

    class Session(
        val player: Player,
        val mode: Mode,
        /** Training mode: fires with the finished kit + difficulty (0/1/2) on "Start bout". */
        val onStart: ((KitSetup, Int) -> Unit)?,
        /** Bank mode: fires with the kit to withdraw on "Load kit". */
        val onLoad: ((KitSetup) -> Unit)?,
    ) {
        val kit = KitSetup()
        var diff = 1 // 0 easy, 1 medium, 2 hard (training mode only)
        var savedKits: Array<KitSetup?> = arrayOfNulls(KitStorage.SLOT_COUNT)
    }

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)
    fun sessionOf(p: Player): Session? = sessions[p.index]

    fun open(
        p: Player,
        mode: Mode,
        onStart: ((KitSetup, Int) -> Unit)? = null,
        onLoad: ((KitSetup) -> Unit)? = null,
    ) {
        val s = Session(p, mode, onStart, onLoad)
        if (mode == Mode.LMS) {
            // LMS state lives in LmsKits (persisted selection) — the session just mirrors it.
            sessions[p.index] = s
            publish(s)
            return
        }
        s.savedKits = KitStorage.load(p)
        // Open on the last-used saved kit if there is one, else the Dharok preset — never a blank
        // screen: there is always something concrete on the doll to react to.
        val initial = s.savedKits.firstOrNull { it != null && !it.isEmpty() } ?: KitArmoury.DHAROK
        loadInto(s, initial)
        sessions[p.index] = s
        publish(s)
    }

    fun close(p: Player) {
        sessions.remove(p.index) ?: return
        if (p.index >= 0) clearVarps(p)
    }

    /** Wipe all editor varps (login hygiene — transient UI state must never persist). */
    fun clearVarps(p: Player) {
        p.setVarp(CONTROL_VARP, 0)
        for (i in 0 until EQUIP_SLOTS + KitSetup.INV_SIZE) p.setVarp(SLOT_VARP_BASE + i, 0)
    }

    // ── actions (routed from ::kit via KitsPlugin) ──

    /** Add [itemId] — gear equips into its slot, anything else takes the first free inventory slot.
     *  In LMS mode a palette click is a CATEGORY PICK: the id is a choice's representative item. */
    fun addItem(p: Player, itemId: Int) {
        val s = sessions[p.index] ?: return
        if (itemId <= 0) return
        if (s.mode == Mode.LMS) {
            val (cat, choice) = org.alter.plugins.content.minigames.lms.LmsKits.choiceByRepId(itemId) ?: return
            org.alter.plugins.content.minigames.lms.LmsKits.save(p, cat.key, choice.key)
            p.message("${cat.title}: <col=007f00>${choice.label}</col>.")
            publish(s)
            return
        }
        if (s.mode == Mode.TRAINING && !KitArmoury.contains(itemId)) {
            p.message("The armoury doesn't stock that."); return
        }
        val def = runCatching { Item(itemId).getDef() }.getOrNull() ?: return
        val qty = if (s.mode == Mode.TRAINING) KitArmoury.defaultQty(itemId) else 1
        if (def.equipSlot >= 0) {
            s.kit.gear[def.equipSlot] = Item(itemId, if (def.stackable) qty.coerceAtMost(KitSetup.MAX_QTY) else 1)
        } else if (def.stackable && s.kit.inv.values.any { it.id == itemId }) {
            // Stackables merge into their existing slot instead of eating a new one.
            val (slot, cur) = s.kit.inv.entries.first { it.value.id == itemId }.let { it.key to it.value }
            s.kit.inv[slot] = Item(itemId, (cur.amount + qty).coerceAtMost(KitSetup.MAX_QTY))
        } else {
            val free = (0 until KitSetup.INV_SIZE).firstOrNull { it !in s.kit.inv }
                ?: run { p.message("The kit's inventory is full."); return }
            s.kit.inv[free] = Item(itemId, qty.coerceAtMost(KitSetup.MAX_QTY))
        }
        publish(s)
    }

    /** Clear worn-slot [dollIndex] (index into [SLOT_IDS]). */
    fun clearEquip(p: Player, dollIndex: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return // the LMS doll is a read-only preview of the category picks
        if (dollIndex !in SLOT_IDS.indices) return
        s.kit.gear.remove(SLOT_IDS[dollIndex])
        publish(s)
    }

    /** Clear inventory slot [slot] (0..27). */
    fun clearInv(p: Player, slot: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return
        if (slot !in 0 until KitSetup.INV_SIZE) return
        s.kit.inv.remove(slot)
        publish(s)
    }

    /** Load a built-in preset: 0 = Dharok's, 1 = NH tribrid. */
    fun loadPreset(p: Player, index: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return
        loadInto(s, if (index == 0) KitArmoury.DHAROK else KitArmoury.NH)
        publish(s)
    }

    fun loadSaved(p: Player, slot: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return
        val kit = s.savedKits.getOrNull(slot) ?: run { p.message("That kit slot is empty — press Save to fill it."); return }
        loadInto(s, kit)
        publish(s)
    }

    fun saveKit(p: Player, slot: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return
        if (slot !in 0 until KitStorage.SLOT_COUNT) return
        if (s.kit.isEmpty()) { p.message("There's nothing to save yet."); return }
        KitStorage.save(p, slot, s.kit)
        s.savedKits[slot] = s.kit.copy()
        p.message("<col=007f00>Saved to kit slot ${slot + 1}.</col>")
        publish(s)
    }

    fun setBook(p: Player, book: Int) {
        val s = sessions[p.index] ?: return
        if (s.mode == Mode.LMS) return // the magic-pack category owns the spellbook in LMS
        if (book !in 0..2) return
        s.kit.book = book
        publish(s)
    }

    fun setDiff(p: Player, diff: Int) {
        val s = sessions[p.index] ?: return
        if (diff !in 0..2) return
        s.diff = diff
        publish(s)
    }

    /** Training mode "Start bout" — hand the kit to the trainer and close. */
    fun start(p: Player) {
        val s = sessions[p.index] ?: return
        val onStart = s.onStart ?: return
        if (s.kit.isEmpty()) { p.message("Put a kit together first."); return }
        val kit = s.kit.copy()
        val diff = s.diff
        close(p)
        onStart(kit, diff)
    }

    /** LMS mode "Done" — the picks were persisted as they were clicked; just confirm and close. */
    fun done(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.mode != Mode.LMS) return
        close(p)
        p.message("<col=007f00>Kit saved.</col> You'll spawn with it in your next Last Man Standing game.")
    }

    /** Bank mode "Load kit" — hand the kit to the bank loader and close. */
    fun load(p: Player) {
        val s = sessions[p.index] ?: return
        val onLoad = s.onLoad ?: return
        if (s.kit.isEmpty()) { p.message("Put a kit together first."); return }
        val kit = s.kit.copy()
        close(p)
        onLoad(kit)
    }

    // ── state sync ──

    private fun loadInto(s: Session, kit: KitSetup) {
        s.kit.gear.clear(); s.kit.gear.putAll(kit.copy().gear)
        s.kit.inv.clear(); s.kit.inv.putAll(kit.copy().inv)
        s.kit.book = kit.book
    }

    /**
     * Control varp layout (must match the client overlay):
     *   bit 0      open
     *   bits 1-2   mode (1 = training, 2 = bank, 3 = LMS)
     *   bits 3-4   spellbook (0 std / 1 ancients / 2 lunar)
     *   bits 5-6   difficulty (training only)
     *   bits 7-9   which of the three save slots hold a kit
     *   bits 10-19 LMS: selected choice index per category (2 bits each, LmsKits.CATEGORIES order)
     */
    private fun publish(s: Session) {
        val p = s.player
        if (p.index < 0) return
        // LMS mode renders a preview of the persisted category picks; the other modes render s.kit.
        val kit = if (s.mode == Mode.LMS) org.alter.plugins.content.minigames.lms.LmsKits.buildKit(p) else s.kit
        var c = 1
        c = c or (when (s.mode) { Mode.TRAINING -> 1; Mode.BANK -> 2; Mode.LMS -> 3 } shl 1)
        c = c or (kit.book.coerceIn(0, 2) shl 3)
        c = c or (s.diff.coerceIn(0, 2) shl 5)
        for (i in 0 until KitStorage.SLOT_COUNT) {
            if (s.savedKits[i] != null) c = c or (1 shl (7 + i))
        }
        if (s.mode == Mode.LMS) {
            org.alter.plugins.content.minigames.lms.LmsKits.selectedIndices(p).forEachIndexed { i, sel ->
                c = c or ((sel and 0x3) shl (10 + 2 * i))
            }
        }
        p.setVarp(CONTROL_VARP, c)
        SLOT_IDS.forEachIndexed { i, slotId ->
            p.setVarp(SLOT_VARP_BASE + i, packItem(kit.gear[slotId]))
        }
        for (i in 0 until KitSetup.INV_SIZE) {
            p.setVarp(SLOT_VARP_BASE + EQUIP_SLOTS + i, packItem(kit.inv[i]))
        }
    }

    private fun packItem(item: Item?): Int {
        if (item == null) return 0
        return (item.id and 0xFFFF) or (item.amount.coerceIn(1, KitSetup.MAX_QTY) shl 16)
    }
}
