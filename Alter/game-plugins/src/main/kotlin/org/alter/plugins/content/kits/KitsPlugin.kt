package org.alter.plugins.content.kits

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.InterfaceDestination
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.bank.Bank
import org.alter.plugins.content.minigames.pktraining.TrainingArena

private val logger = KotlinLogging.logger {}

/**
 * **Kit loadouts** — the LMS-style kit screen, everywhere.
 *
 * Two ways in:
 *  - The **PK trainer** opens the editor in TRAINING mode (loaner gear — see PkTrainingArenaPlugin).
 *  - `::kits` at a **bank** opens it in BANK mode: build/save kits, then **Load kit** deposits
 *    everything you're carrying and re-arms you from your own bank in one click. Only items
 *    actually in your bank are withdrawn — anything missing is skipped and reported. Nothing is
 *    ever created; requirements still apply (an item you can't wear stays in your inventory).
 *
 * Input routing: the client overlay sends `::kit <action>` (public chat, suppressed and routed to
 * the `kitclick` command by MessagePublicHandler — the lofduel/lofstake pattern).
 *
 * Actions: `a <id>` add item · `re <i>` / `ri <i>` clear worn/inventory slot · `p <0|1>` preset ·
 * `k <0-2>` load saved · `s <0-2>` save · `b <0-2>` spellbook · `d <0-2>` difficulty ·
 * `start` begin training bout · `load` bank-load · `x` close.
 */
class KitsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { KitEditor.clearVarps(player) } // transient UI state never survives a login

        onLogout { KitEditor.close(player) }

        onCommand("kits", description = "Open the kit loadout editor (load kits at a bank)") {
            openBankMode(player)
        }

        // Quick-load: ::kit <1-3> re-arms straight from a saved slot with the bank open —
        // no editor round-trip. Same conservative loader as the editor's Load button.
        onCommand("kit", description = "Quick-load a saved kit at a bank (::kit 1-3)") {
            val slot = player.getCommandArgs().getOrNull(0)?.toIntOrNull()
            if (slot == null || slot !in 1..KitStorage.SLOT_COUNT) {
                player.message("Usage: ::kit <1-${KitStorage.SLOT_COUNT}>")
                return@onCommand
            }
            if (TrainingArena.kitted(player)) {
                player.message("Hand the training kit back first (::unkit).")
                return@onCommand
            }
            val kit = KitStorage.load(player)[slot - 1]
            if (kit == null || kit.isEmpty()) {
                player.message("Kit $slot is empty — build and save it with ::kits first.")
                return@onCommand
            }
            loadFromBank(player, kit)
            player.setSpellbook(spellbookOf(kit.book))
        }

        onCommand("kitclick", description = "Kit editor interaction (client overlay channel)") {
            val args = player.getCommandArgs()
            when (args.getOrNull(0)) {
                "a" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.addItem(player, it) }
                "re" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.clearEquip(player, it) }
                "ri" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.clearInv(player, it) }
                "p" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.loadPreset(player, it) }
                "k" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.loadSaved(player, it) }
                "s" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.saveKit(player, it) }
                "b" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.setBook(player, it) }
                "d" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.setDiff(player, it) }
                "start" -> KitEditor.start(player)
                "load" -> KitEditor.load(player)
                "done" -> KitEditor.done(player)
                "x" -> KitEditor.close(player)
            }
        }
    }

    private fun openBankMode(p: Player) {
        if (KitEditor.isOpen(p)) return
        if (TrainingArena.kitted(p)) { p.message("Hand the training kit back first (::unkit)."); return }
        if (!bankOpen(p)) {
            p.message("Open your bank first — kits load from your own bank. (You can still edit kits at the trainer.)")
            return
        }
        KitEditor.open(p, KitEditor.Mode.BANK, onLoad = { kit -> loadFromBank(p, kit) })
    }

    private fun bankOpen(p: Player): Boolean =
        p.getInterfaceAt(InterfaceDestination.MAIN_SCREEN) == Bank.BANK_INTERFACE_ID

    private fun spellbookOf(book: Int): org.alter.api.Spellbook =
        when (book) {
            KitSetup.BOOK_ANCIENTS -> org.alter.api.Spellbook.ANCIENTS
            KitSetup.BOOK_LUNAR -> org.alter.api.Spellbook.LUNAR
            else -> org.alter.api.Spellbook.NORMAL
        }

    // ───────────────────────────── bank loader ─────────────────────────────

    /**
     * One-click re-arm from the player's own bank: deposit everything carried, then withdraw and
     * equip the kit. Bank-only (checked again here — the editor can outlive walking away), and
     * strictly conservative: any step that can't complete skips and reports, never duplicates.
     */
    private fun loadFromBank(p: Player, kit: KitSetup) {
        if (!bankOpen(p)) { p.message("You need your bank open to load a kit."); return }
        if (p.isLocked()) return

        // 1) Everything carried goes into the bank first (LMS-style clean slate).
        depositInventory(p)
        depositEquipment(p)
        if (!p.inventory.isEmpty || p.equipment.occupiedSlotCount != 0) {
            // Bank couldn't take it all (full) — abort before withdrawing anything.
            p.message("<col=801700>Your bank is too full to stash what you're carrying — kit not loaded.</col>")
            return
        }

        val missing = ArrayList<String>()
        val unworn = ArrayList<String>()

        // 2) Worn gear: withdraw one of each and equip it (requirements apply — a failed equip
        //    stays in the inventory rather than vanishing back to the bank).
        kit.gear.entries.sortedBy { it.key }.forEach { (_, want) ->
            val got = withdraw(p, want.id, if (want.getDef().stackable) want.amount else 1)
            if (got <= 0) { missing += nameOf(want); return@forEach }
            val invSlot = firstSlotOf(p, want.id) ?: return@forEach
            val result = EquipAction.equip(p, Item(want.id, got), invSlot)
            if (result != EquipAction.Result.SUCCESS) unworn += nameOf(want)
        }

        // 3) Inventory: withdraw into the kit's exact slot layout where possible.
        kit.inv.entries.sortedBy { it.key }.forEach { (_, want) ->
            val got = withdraw(p, want.id, want.amount)
            if (got < want.amount) missing += (if (want.amount > 1) "${want.amount - got} x " else "") + nameOf(want)
        }

        p.message("<col=007f00>Kit loaded from your bank.</col>")
        if (missing.isNotEmpty()) {
            p.message("<col=801700>Not in your bank:</col> ${missing.joinToString(", ")}.")
        }
        if (unworn.isNotEmpty()) {
            p.message("<col=801700>Couldn't be worn (kept in your pack):</col> ${unworn.joinToString(", ")}.")
        }
        logger.info { "KIT bank-load by ${p.username}: ${kit.gear.size} gear, ${kit.inv.size} inv, missing=${missing.size}" }
    }

    private fun depositInventory(p: Player) {
        // Distinct ids, then deposit-all per id (Bank.deposit handles tabs + placeholders).
        val ids = LinkedHashSet<Int>()
        for (i in 0 until p.inventory.capacity) p.inventory[i]?.let { ids += it.id }
        ids.forEach { Bank.deposit(p, it, Int.MAX_VALUE) }
    }

    private fun depositEquipment(p: Player) {
        for (slot in 0 until p.equipment.capacity) {
            if (p.equipment[slot] == null) continue
            EquipAction.unequip(p, slot) // lands in the inventory (28 free slots after the deposit)
        }
        depositInventory(p)
    }

    /** Withdraw up to [amt] of [id] from the bank into the inventory. Returns how many arrived. */
    private fun withdraw(p: Player, id: Int, amt: Int): Int {
        var got = 0
        val bank = p.bank
        for (i in 0 until bank.capacity) {
            if (got >= amt) break
            val item = bank[i] ?: continue
            if (item.id != id || item.amount <= 0) continue // amount <= 0 = placeholder, not stock
            val take = minOf(amt - got, item.amount)
            val tx = bank.transfer(p.inventory, item = Item(id, take), fromSlot = i, note = false, unnote = false)
            got += tx?.completed ?: 0
            if (tx == null || tx.completed == 0) break // inventory full — stop pulling
        }
        return got
    }

    private fun firstSlotOf(p: Player, id: Int): Int? {
        for (i in 0 until p.inventory.capacity) if (p.inventory[i]?.id == id) return i
        return null
    }

    private fun nameOf(item: Item): String =
        runCatching { item.getDef().name }.getOrDefault("item ${item.id}")
}
