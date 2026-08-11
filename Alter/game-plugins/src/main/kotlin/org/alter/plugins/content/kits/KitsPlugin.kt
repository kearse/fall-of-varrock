package org.alter.plugins.content.kits

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.interfaces.bank.Bank
import org.alter.plugins.content.minigames.castlewars.CastleWars
import org.alter.plugins.content.minigames.duel.DuelArena
import org.alter.plugins.content.minigames.lms.LmsGame
import org.alter.plugins.content.minigames.pktraining.TrainingArena

private val logger = KotlinLogging.logger {}

/**
 * **Kit loadouts** — the LMS-style kit screen, everywhere.
 *
 * Two ways in:
 *  - The **PK trainer** opens the editor in TRAINING mode (loaner gear — see PkTrainingArenaPlugin).
 *  - `::kit` (or `::kits`) opens it in BANK mode **anywhere**: browse, build (the palette is the
 *    client's cached view of your bank), copy your current setup ("Wearing"), save kits — and
 *    **Load kit**, which deposits everything you're carrying and re-arms you from your own bank
 *    in one click. Loading works anywhere EXCEPT dangerous places (wilderness, duels, minigames,
 *    instanced arenas — see [loadBlockedReason]). Only items actually in your bank are withdrawn —
 *    anything missing is skipped and reported. Nothing is ever created; requirements still apply
 *    (an item you can't wear stays in your inventory).
 *
 * Input routing: the client overlay sends `::kit <action>` (public chat, suppressed and routed to
 * the `kitclick` command by MessagePublicHandler — the lofduel/lofstake pattern).
 *
 * Actions: `a <id>` add item (training/LMS palette) · `ai <id> [qty]` add into the kit inventory ·
 * `aix <id>` add-X (native amount prompt) · `ae <id> [qty]` add + equip · `eq <i>` equip inventory
 * slot · `re <i>` / `ri <i>` clear worn/inventory slot · `p <0|1>` preset · `k <0-2>` load saved ·
 * `s <0-2>` save · `cur` copy current worn+carried setup · `b <0-2>` spellbook · `d <0-2>`
 * difficulty · `start` begin training bout · `load` bank-load · `x` close · a bare number =
 * `::kit <n>` quick-load (typed commands can arrive down this channel too).
 */
class KitsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { KitEditor.clearVarps(player) } // transient UI state never survives a login

        onLogout { KitEditor.close(player) }

        onCommand("kits", description = "Open the kit loadout editor") {
            openEditor(player)
        }

        // Bare ::kit opens the editor anywhere (browse/build/save kits);
        // ::kit <1-3> re-arms straight from a saved slot with no editor round-trip.
        onCommand("kit", description = "Open the kit editor; ::kit <1-3> quick-loads a saved kit") {
            kitCommand(player, player.getCommandArgs().getOrNull(0))
        }

        onCommand("kitclick", description = "Kit editor interaction (client overlay channel)") {
            val args = player.getCommandArgs()
            when (args.getOrNull(0)) {
                "a" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.addItem(player, it) }
                "ai" -> args.getOrNull(1)?.toIntOrNull()?.let {
                    KitEditor.addToInv(player, it, args.getOrNull(2)?.toIntOrNull() ?: 1)
                }
                // Withdraw-X: the native chatbox amount prompt, then the same add-to-inventory path.
                "aix" -> args.getOrNull(1)?.toIntOrNull()?.let { id ->
                    if (KitEditor.sessionOf(player)?.mode == KitEditor.Mode.BANK) {
                        player.queue {
                            val amt = inputInt(player, "Enter amount:")
                            if (amt > 0) KitEditor.addToInv(player, id, amt)
                        }
                    }
                }
                "ae" -> args.getOrNull(1)?.toIntOrNull()?.let {
                    KitEditor.addAndEquip(player, it, args.getOrNull(2)?.toIntOrNull() ?: 1)
                }
                "eq" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.equipInvSlot(player, it) }
                "re" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.clearEquip(player, it) }
                "ri" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.clearInv(player, it) }
                "p" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.loadPreset(player, it) }
                "k" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.loadSaved(player, it) }
                "s" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.saveKit(player, it) }
                "cur" -> KitEditor.loadCurrent(player)
                // Armoury search bar (TRAINING only): opens the native chatbox item finder; the
                // pick is added through the same validated path as a palette click. Bank mode
                // filters the palette client-side instead — the whole-cache finder must stay
                // unreachable there (it would "find" items the player doesn't own).
                "search" -> {
                    if (KitEditor.sessionOf(player)?.mode == KitEditor.Mode.TRAINING) {
                        player.queue {
                            val id = searchItemInputT(player, "Search the armoury:")
                            if (id > 0) KitEditor.addItem(player, id)
                        }
                    }
                }
                // The named-kit dropdown: fresh kit (asks its name), save under the current name,
                // rename (double-clicked title).
                "new" -> KitEditor.newKit(player)
                "save" -> KitEditor.saveCurrent(player)
                "rename" -> KitEditor.renameKit(player)
                "b" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.setBook(player, it) }
                "d" -> args.getOrNull(1)?.toIntOrNull()?.let { KitEditor.setDiff(player, it) }
                "start" -> KitEditor.start(player)
                "load" -> KitEditor.load(player)
                "done" -> KitEditor.done(player)
                "x" -> KitEditor.close(player)
                // "::kit" / "::kit 1" typed in chat can be swallowed by MessagePublicHandler's
                // overlay channel (public-chat route) and land HERE instead of the ::kit
                // command — fall through to the same handler so both routes work identically.
                else -> kitCommand(player, args.getOrNull(0))
            }
        }
    }

    /** Bare `::kit` opens the editor; `::kit <slot>` quick-loads that saved kit from the bank. */
    private fun kitCommand(p: Player, arg: String?) {
        if (arg == null) {
            openEditor(p)
            return
        }
        val slot = arg.toIntOrNull()
        if (slot == null || slot !in 1..KitStorage.SLOT_COUNT) {
            p.message("Usage: ::kit opens the kit editor; ::kit <1-${KitStorage.SLOT_COUNT}> quick-loads that saved kit at a bank.")
            return
        }
        if (TrainingArena.kitted(p)) {
            p.message("Hand the training kit back first (::unkit).")
            return
        }
        val kit = KitStorage.load(p)[slot - 1]
        if (kit == null || kit.isEmpty()) {
            p.message("Kit $slot is empty — build and save it with ::kits first.")
            return
        }
        if (loadFromBank(p, kit)) p.setSpellbook(spellbookOf(kit.book))
    }

    /** Open the editor in bank mode. Works anywhere — browsing, building, copying your current
     *  setup, saving AND loading; only dangerous places refuse the load itself. */
    private fun openEditor(p: Player) {
        if (KitEditor.isOpen(p)) return
        if (TrainingArena.kitted(p)) { p.message("Hand the training kit back first (::unkit)."); return }
        // Push the bank container to the client: its palette is the client-side bank cache, which
        // is wiped on every login/world-hop while the server only resends on CHANGE (bank.dirty) —
        // without this, opening the editor before visiting a bank showed an empty browser.
        p.bank.dirty = true
        KitEditor.open(p, KitEditor.Mode.BANK, onLoad = { kit -> loadFromBank(p, kit) })
    }

    /** Why a kit can't be loaded right here, or null when it can. Loading is a full re-arm from
     *  the bank, so anywhere you shouldn't be able to re-gear mid-fight is off the table. */
    private fun loadBlockedReason(p: Player): String? = when {
        PvpZones.isWilderness(p.tile) -> "You can't load a kit in the wilderness."
        world.instanceAllocator.getMap(p.tile) != null -> "You can't load a kit in here."
        DuelArena.duelOf(p) != null -> "You can't load a kit during a duel."
        LmsGame.inGame(p) -> "You can't load a kit during Last Man Standing."
        CastleWars.inGame(p) -> "You can't load a kit during Castle Wars."
        TrainingArena.kitted(p) -> "Hand the training kit back first (::unkit)."
        else -> null
    }

    private fun spellbookOf(book: Int): org.alter.api.Spellbook =
        when (book) {
            KitSetup.BOOK_ANCIENTS -> org.alter.api.Spellbook.ANCIENTS
            KitSetup.BOOK_LUNAR -> org.alter.api.Spellbook.LUNAR
            else -> org.alter.api.Spellbook.NORMAL
        }

    // ───────────────────────────── bank loader ─────────────────────────────

    /**
     * One-click re-arm from the player's own bank: deposit everything carried, then withdraw and
     * equip the kit. Works anywhere outside danger zones ([loadBlockedReason] — the deposit and
     * withdrawal act on the server-side containers directly, no bank interface needed), and
     * strictly conservative: any step that can't complete skips and reports, never duplicates.
     * Returns false when nothing happened (blocked/locked/bank full) so the editor can stay open.
     */
    private fun loadFromBank(p: Player, kit: KitSetup): Boolean {
        loadBlockedReason(p)?.let { p.message(it); return false }
        if (p.isLocked()) return false

        // 1) Everything carried goes into the bank first (LMS-style clean slate).
        depositInventory(p)
        depositEquipment(p)
        if (!p.inventory.isEmpty || p.equipment.occupiedSlotCount != 0) {
            // Bank couldn't take it all (full) — abort before withdrawing anything.
            p.message("<col=801700>Your bank is too full to stash what you're carrying — kit not loaded.</col>")
            return false
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
        return true
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
