package org.alter.plugins.content.economy.pk

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getInteractingOption
import org.alter.api.ext.getInteractingSlot
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.LOOT_KEYS_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.SKULL_ICON_DURATION_TIMER
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * **Wilderness loot keys** (OSRS-style). When a real player kills another player (or a PKer bot)
 * in the wilderness, the victim's lost items are sealed into a **loot key** placed in the killer's
 * inventory instead of scattering on the ground.
 *
 *  - **Claiming works like rev-228 OSRS:** the **Loot Chest** at the Lumbridge market opens the
 *    stock Wilderness Loot Key popup showing ONE key at a time (oldest first — see
 *    [LootChestInterface] for why there are no per-key tabs at this revision). Items can be
 *    withdrawn/banked singly from the grid or all at once with the chest buttons; emptying a key
 *    advances to the next. The key's inventory "Check" option lists contents in chat.
 *  - Up to [MAX_KEYS] keys at once (the 5 distinct OSRS key items, one per bundle); at the cap, or
 *    with a full inventory, the kill falls back to normal ground drops.
 *  - Key contents persist on [LOOT_KEYS_ATTR] as a JSON blob (companion-roster pattern: bson
 *    [Document], decode never throws). The key ITEM is just a handle — `::lootkeys` re-issues a
 *    lost handle as long as the bundle exists.
 *  - **Dying loses your unclaimed keys** (OSRS): on any non-safe death the handles are destroyed
 *    and the sealed contents join the rest of your lost loot — in the wilderness your killer gets
 *    them (sealed into THEIR key), elsewhere they land on your reclaim pile
 *    ([org.alter.plugins.content.combat.PvpDeathDropPlugin.dropOnDeath] calls [LootKeys.confiscate]).
 *
 * Only wilderness kills mint keys — safe-zone bot kills (road highwaymen) still drop their full
 * kit on the ground as before.
 */
object LootKeys {
    const val MAX_KEYS = 5

    /** Skull icons 8..12 = the PK skull carrying 1..5 loot keys (RuneLite `SkullIcon.LOOT_KEYS_*`). */
    private const val KEYED_SKULL_BASE = 8

    /** A key holds up to this many item stacks (OSRS) — the excess ground-drops at the kill. */
    const val MAX_STACKS = 28

    /** The five OSRS loot key items (rev-228 cache ids 26651-26655), resolved once via RSCM. */
    private val keyNames = listOf(
        "item.loot_key", "item.loot_key_26652", "item.loot_key_26653", "item.loot_key_26654", "item.loot_key_26655",
    )
    private val keyHandles: List<Pair<String, Int>> by lazy {
        keyNames.mapNotNull { key -> runCatching { key to getRSCM(key) }.getOrNull() }
    }
    private val keyIds: List<Int> by lazy { keyHandles.map { it.second } }

    class Bundle(val keyId: Int, val victim: String, var items: MutableList<Item>)

    fun keyItemHandles(): List<Pair<String, Int>> = keyHandles

    fun isKeyItem(id: Int): Boolean = id in keyIds

    /** The key's tab slot (0..4) in the loot chest interface — its index in the 5 key item ids. */
    fun keySlot(keyId: Int): Int = keyIds.indexOf(keyId)

    /**
     * Seal [items] into a new loot key in [killer]'s inventory. A key holds up to [MAX_STACKS]
     * item stacks (OSRS): the most valuable are sealed and the rest is RETURNED as overflow for
     * the caller to ground-drop. Returns null (caller drops EVERYTHING) when there are no items,
     * no free key slot/id, or no inventory space.
     */
    fun tryAward(killer: Player, victimName: String, items: List<Item>): List<Item>? {
        if (items.isEmpty() || keyIds.isEmpty()) return null
        val bundles = load(killer)
        if (bundles.size >= MAX_KEYS) {
            killer.message("You already carry $MAX_KEYS loot keys — this one's loot falls to the ground.")
            return null
        }
        val used = bundles.map { it.keyId }
        val keyId = keyIds.firstOrNull { it !in used } ?: return null
        if (killer.inventory.add(item = keyId, amount = 1).completed == 0) {
            killer.message("Your inventory is too full for a loot key — the loot falls to the ground.")
            return null
        }
        val sorted = items.sortedByDescending { runCatching { getItem(it.id).cost }.getOrDefault(0) }
        val sealed = sorted.take(MAX_STACKS)
        bundles += Bundle(keyId, victimName, sealed.map { Item(it.id, it.amount) }.toMutableList())
        save(killer, bundles)
        killer.message("<col=801700>You received a loot key from $victimName!</col> (${bundles.size}/$MAX_KEYS — open it at the Loot Chest in Lumbridge)")
        return sorted.drop(MAX_STACKS)
    }

    /** "Check" a key: list what's sealed inside (claiming happens at the Loot Chest). */
    fun checkKey(p: Player, keyId: Int) {
        val bundle = load(p).firstOrNull { it.keyId == keyId }
        if (bundle == null) {
            // A handle with no bundle (shouldn't happen) — remove it so it can't linger.
            p.inventory.remove(item = keyId, amount = 1)
            p.message("The loot key is empty — it crumbles to dust.")
            return
        }
        p.message("<col=801700>Loot key from ${bundle.victim}:</col>")
        bundle.items.forEach { item ->
            val name = runCatching { getItem(item.id).name }.getOrDefault("item ${item.id}")
            p.message("- $name${if (item.amount > 1) " x ${item.amount}" else ""}")
        }
        p.message("Open it at the <col=801700>Loot Chest</col> at the Lumbridge market.")
    }

    /** Chest action: move a key's ENTIRE contents to the inventory or the bank (partial ok).
     *  [noted] withdraws notable items as bank notes (inventory only — banking always unnotes). */
    fun takeAll(p: Player, keyId: Int, toBank: Boolean, noted: Boolean = false) {
        val bundles = load(p)
        val bundle = bundles.firstOrNull { it.keyId == keyId } ?: return
        val remaining = mutableListOf<Item>()
        bundle.items.forEach { item ->
            val give = when {
                toBank -> item.toUnnoted()
                noted -> item.toNoted()
                else -> item
            }
            val tx = if (toBank) {
                p.bank.add(item = give.id, amount = give.amount, assureFullInsertion = false)
            } else {
                p.inventory.add(item = give.id, amount = give.amount, assureFullInsertion = false)
            }
            val left = item.amount - tx.completed
            if (left > 0) remaining += Item(item.id, left)
        }
        bundle.items = remaining
        finish(p, bundles, bundle, if (toBank) "banked" else "claimed")
    }

    /** The chest's Destroy button (confirmation handled by the caller): wipe ALL sealed loot. */
    fun destroyAll(p: Player) {
        val bundles = load(p)
        if (bundles.isEmpty()) return
        bundles.forEach { removeHandle(p, it.keyId) }
        save(p, emptyList())
        p.message("<col=801700>You destroy the sealed loot</col> — the keys crumble to dust.")
    }

    /** Chest action: take ONE listed item (by its current index in the bundle) to the inventory
     *  or the bank. [noted] withdraws notable items as bank notes (inventory only). */
    fun takeOne(p: Player, keyId: Int, index: Int, toBank: Boolean = false, noted: Boolean = false) {
        val bundles = load(p)
        val bundle = bundles.firstOrNull { it.keyId == keyId } ?: return
        val item = bundle.items.getOrNull(index) ?: return
        val give = when {
            toBank -> item.toUnnoted()
            noted -> item.toNoted()
            else -> item
        }
        val tx = if (toBank) {
            p.bank.add(item = give.id, amount = give.amount, assureFullInsertion = false)
        } else {
            p.inventory.add(item = give.id, amount = give.amount, assureFullInsertion = false)
        }
        when {
            tx.completed >= item.amount -> bundle.items.removeAt(index)
            tx.completed > 0 -> bundle.items[index] = Item(item.id, item.amount - tx.completed)
            else -> {
                p.message(if (toBank) "You need free bank space for that." else "You need free inventory space for that.")
                return
            }
        }
        finish(p, bundles, bundle, if (toBank) "banked" else "claimed")
    }

    private fun finish(p: Player, bundles: MutableList<Bundle>, bundle: Bundle, verb: String) {
        if (bundle.items.isEmpty()) {
            removeHandle(p, bundle.keyId)
            bundles.remove(bundle)
            p.message("<col=801700>${bundle.victim}'s loot key is emptied</col> — the key crumbles to dust.")
        } else if (bundle.items.isNotEmpty() && verb == "banked") {
            p.message("Some items wouldn't fit in your bank — they stay sealed in the key.")
        } else {
            p.message("Some items wouldn't fit — they stay sealed in the key.")
        }
        save(p, bundles)
    }

    /**
     * Death: destroy every key handle (inventory AND bank) and return the sealed contents so the
     * death drop routes them like the rest of the victim's lost loot. Returns empty when keyless.
     */
    fun confiscate(victim: Player): List<Item> {
        val bundles = load(victim)
        if (bundles.isEmpty()) return emptyList()
        val contents = bundles.flatMap { it.items }
        bundles.forEach { removeHandle(victim, it.keyId) }
        save(victim, emptyList())
        victim.message("Your unclaimed loot keys are lost with you!")
        return contents
    }

    private fun removeHandle(p: Player, keyId: Int) {
        if (p.inventory.remove(item = keyId, amount = 1).completed == 0) {
            p.bank.remove(item = keyId, amount = 1)
        }
    }

    fun load(p: Player): MutableList<Bundle> {
        val blob = p.attr[LOOT_KEYS_ATTR] ?: return mutableListOf()
        return runCatching {
            Document.parse(blob).getList("keys", Document::class.java).map { d ->
                Bundle(
                    d.getInteger("key"),
                    d.getString("victim") ?: "an unknown player",
                    d.getList("items", Document::class.java)
                        .map { Item(it.getInteger("id"), it.getInteger("amount", 1)) }
                        .toMutableList(),
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(p: Player, bundles: List<Bundle>) {
        p.attr[LOOT_KEYS_ATTR] = Document(
            "keys",
            bundles.map { b ->
                Document("key", b.keyId)
                    .append("victim", b.victim)
                    .append("items", b.items.map { Document("id", it.id).append("amount", it.amount) })
            },
        ).toJson()
        syncOverhead(p, bundles.size)
    }

    /**
     * Keys-above-head (OSRS): while carrying loot keys the skull becomes the keyed variant
     * (icons 8..12 for 1..5 keys). With none left, fall back to the plain white skull while the
     * PK skull timer still runs, else clear the icon. Called on every key mutation and at login.
     */
    fun syncOverhead(p: Player, count: Int = load(p).size) {
        val icon = when {
            count > 0 -> KEYED_SKULL_BASE + (count.coerceAtMost(MAX_KEYS) - 1)
            p.timers.has(SKULL_ICON_DURATION_TIMER) -> 0
            else -> -1
        }
        // Write both: the engine's skull()/setSkullIcon only touch the avatar, while the
        // skullIcon field is what appearance rebuilds read — desyncing them re-breaks this.
        p.skullIcon = icon
        p.avatar.extendedInfo.setSkullIcon(icon)
    }
}

class LootKeyPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The Loot Chests (Ferox-style, native "Loot" action) in the market, facing each other.
        // Spawned in onWorldInit so the region's collision is loaded first
        // (MiningPlugin/SmithingPlugin pattern).
        onWorldInit {
            world.spawn(DynamicObject(getRSCM(CHEST), OBJ_TYPE, SOUTH_CHEST_ROT, Tile(CHEST_X, SOUTH_CHEST_Z, 0)))
            world.spawn(DynamicObject(getRSCM(CHEST), OBJ_TYPE, NORTH_CHEST_ROT, Tile(CHEST_X, NORTH_CHEST_Z, 0)))
        }
        onObjOption(obj = CHEST, option = "loot") { LootChestInterface.open(player) }

        // Keys show above the head (keyed skull) — restore the icon when a key-carrier logs in.
        onLogin { LootKeys.syncOverhead(player) }

        // ---- the REAL OSRS loot chest popup (stock interface 742) — static buttons from the
        // cache dump; everything else (tab/grid clicks) hits the discovery catch-all below.
        val iface = LootChestInterface.IFACE
        onButton(iface, LootChestInterface.COMP_WITHDRAW_INV) { LootChestInterface.withdrawAll(player, toBank = false) }
        onButton(iface, LootChestInterface.COMP_WITHDRAW_BANK) { LootChestInterface.withdrawAll(player, toBank = true) }
        onButton(iface, LootChestInterface.COMP_MODE_ITEM) { LootChestInterface.setNoted(player, false) }
        onButton(iface, LootChestInterface.COMP_MODE_NOTE) { LootChestInterface.setNoted(player, true) }
        onButton(iface, LootChestInterface.COMP_DESTROY) {
            player.queue {
                when (options(player, "Destroy ALL the sealed loot.", "Cancel.", title = "Destroy the chest's contents?")) {
                    1 -> {
                        LootKeys.destroyAll(player)
                        LootChestInterface.sendContents(player)
                        LootChestInterface.drawCurrent(player)
                    }
                }
            }
        }
        // Item-grid cells (dynamic children of comp 4): slot = grid cell = bundle item index.
        onButton(iface, LootChestInterface.COMP_GRID) {
            LootChestInterface.onGridClick(player, player.getInteractingSlot(), player.getInteractingOption())
        }
        onInterfaceClose(iface) { LootChestInterface.onClosed(player) }
        // Discovery: log any other component op so live testing reveals remaining click wiring.
        val handled = setOf(
            LootChestInterface.COMP_DESTROY, LootChestInterface.COMP_WITHDRAW_INV,
            LootChestInterface.COMP_WITHDRAW_BANK, LootChestInterface.COMP_MODE_ITEM, LootChestInterface.COMP_MODE_NOTE,
            LootChestInterface.COMP_GRID,
        )
        for (comp in 0..40) {
            if (comp in handled) continue
            onButton(iface, comp) {
                LootChestInterface.debugClick(player, comp, player.getInteractingOption(), player.getInteractingSlot())
            }
        }

        // "Check" on each key variant lists the sealed contents. Guarded like BondPlugin's binds —
        // cache defs vary and a missing option must not drop the plugin.
        LootKeys.keyItemHandles().forEach { (keyName, keyId) ->
            try {
                onItemOption(item = keyName, option = "check") { LootKeys.checkKey(player, keyId) }
            } catch (e: Exception) {
                logger.info { "lootkeys: no 'check' option on $keyName (${e.message})" }
            }
        }

        onCommand("lootkeys", description = "List your loot keys / recover a lost key handle") {
            val bundles = LootKeys.load(player)
            if (bundles.isEmpty()) {
                player.message("You have no loot keys. Kill someone in the wilderness to earn one.")
                return@onCommand
            }
            bundles.forEachIndexed { i, b ->
                player.message("Key ${i + 1}/${LootKeys.MAX_KEYS} — from <col=801700>${b.victim}</col>: ${b.items.size} item(s) sealed.")
                // Re-issue the handle if it went missing (not in inventory or bank).
                val held = player.inventory.getItemCount(b.keyId) + player.bank.getItemCount(b.keyId)
                if (held == 0 && player.inventory.add(item = b.keyId, amount = 1).completed > 0) {
                    player.message("...its key was missing, so a replacement has been issued.")
                }
            }
            player.message("Open your keys at the <col=801700>Loot Chest</col> at the Lumbridge market.")
        }

        // ---- dev harnesses: open the popup anywhere / mint a test key without a wildy kill.
        onCommand("lootchest", Privilege.DEV_POWER, description = "Open the loot chest popup here") {
            LootChestInterface.open(player)
        }
        onCommand("testkey", Privilege.DEV_POWER, description = "Mint a test loot key (dummy loot)") {
            val loot = listOf(Item(995, 250_000), Item(1333, 1), Item(1127, 1), Item(379, 5), Item(563, 100))
            if (LootKeys.tryAward(player, "Test Victim", loot) == null) {
                player.message("Couldn't mint a test key (at the cap or full inventory).")
            }
        }
    }

    private companion object {
        const val CHEST = "object.loot_chest"
        const val OBJ_TYPE = 10 // standard interactable scenery

        /** A facing pair on the market's x=3224 column: south chest (3224,3217) reads north,
         *  north chest (3224,3220) reads south. Chest rot cycle is clockwise from N (swamp
         *  bank chest rot=1 reads east) — cosmetic, TUNE in-game if it reads backwards. */
        const val CHEST_X = 3224
        const val SOUTH_CHEST_Z = 3217
        const val SOUTH_CHEST_ROT = 0
        const val NORTH_CHEST_Z = 3220
        const val NORTH_CHEST_ROT = 2
    }
}
