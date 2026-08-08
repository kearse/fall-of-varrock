package org.alter.plugins.content.kits

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.game.model.attr.KIT_LOADOUTS_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * One kit loadout: worn gear + a 28-slot inventory + spellbook. The unit the kit editor edits,
 * the trainer loans out, and the bank loader withdraws. Item amounts are capped at [MAX_QTY]
 * (the per-slot ceiling the editor's varp encoding can carry — no PK kit needs more).
 */
class KitSetup(
    /** Equipment-slot id ([EquipmentType].id) → item. */
    val gear: MutableMap<Int, Item> = HashMap(),
    /** Inventory slot (0..27) → item. */
    val inv: MutableMap<Int, Item> = HashMap(),
    /** 0 = Standard, 1 = Ancients, 2 = Lunar. */
    var book: Int = 0,
) {
    fun copy(): KitSetup = KitSetup(
        HashMap(gear.mapValues { Item(it.value.id, it.value.amount) }),
        HashMap(inv.mapValues { Item(it.value.id, it.value.amount) }),
        book,
    )

    fun isEmpty(): Boolean = gear.isEmpty() && inv.isEmpty()

    companion object {
        const val MAX_QTY = 65535
        const val INV_SIZE = 28
        const val BOOK_STANDARD = 0
        const val BOOK_ANCIENTS = 1
        const val BOOK_LUNAR = 2
    }
}

/**
 * Per-account persistence for the three "My kit" save slots — one JSON blob under
 * [KIT_LOADOUTS_ATTR], the same bson shape as the PK-arena stash so a bad blob never throws.
 */
object KitStorage {
    const val SLOT_COUNT = 3

    fun load(p: Player): Array<KitSetup?> {
        val out = arrayOfNulls<KitSetup>(SLOT_COUNT)
        val blob = p.attr[KIT_LOADOUTS_ATTR] ?: return out
        runCatching {
            val root = Document.parse(blob)
            for (i in 0 until SLOT_COUNT) {
                val d = root.get(i.toString(), Document::class.java) ?: continue
                out[i] = KitSetup(
                    gear = itemsFrom(d, "gear"),
                    inv = itemsFrom(d, "inv"),
                    book = d.getInteger("book", 0),
                )
            }
        }.onFailure { logger.warn { "kits: bad loadout blob for ${p.username}: ${it.message}" } }
        return out
    }

    /** Per-slot kit NAMES (the dropdown labels). Legacy blobs without names read "Kit N". */
    fun names(p: Player): Array<String> {
        val out = Array(SLOT_COUNT) { "Kit ${it + 1}" }
        val blob = p.attr[KIT_LOADOUTS_ATTR] ?: return out
        runCatching {
            val root = Document.parse(blob)
            for (i in 0 until SLOT_COUNT) {
                root.get(i.toString(), Document::class.java)?.getString("name")
                    ?.takeIf { it.isNotBlank() }?.let { out[i] = it }
            }
        }
        return out
    }

    /** Rename saved slot [slot] in place (a no-op for an empty slot). */
    fun rename(p: Player, slot: Int, name: String) {
        if (slot !in 0 until SLOT_COUNT) return
        val kits = load(p)
        if (kits[slot] == null) return
        val allNames = names(p).also { it[slot] = name }
        write(p, kits, allNames)
    }

    fun save(p: Player, slot: Int, kit: KitSetup, name: String? = null) {
        if (slot !in 0 until SLOT_COUNT) return
        val kits = load(p)
        kits[slot] = kit.copy()
        val allNames = names(p)
        if (name != null && name.isNotBlank()) allNames[slot] = name
        write(p, kits, allNames)
    }

    private fun write(p: Player, kits: Array<KitSetup?>, names: Array<String>) {
        val root = Document()
        kits.forEachIndexed { i, k ->
            if (k != null) {
                root.append(
                    i.toString(),
                    Document()
                        .append("gear", itemsDoc(k.gear))
                        .append("inv", itemsDoc(k.inv))
                        .append("book", k.book)
                        .append("name", names[i]),
                )
            }
        }
        p.attr[KIT_LOADOUTS_ATTR] = root.toJson()
    }

    private fun itemsDoc(items: Map<Int, Item>): Document = Document().also { d ->
        items.forEach { (slot, it) -> d.append(slot.toString(), Document("id", it.id).append("amount", it.amount)) }
    }

    private fun itemsFrom(doc: Document, key: String): MutableMap<Int, Item> {
        val out = HashMap<Int, Item>()
        doc.get(key, Document::class.java)?.forEach { (slot, v) ->
            val d = v as Document
            out[slot.toInt()] = Item(d.getInteger("id"), d.getInteger("amount", 1).coerceIn(1, KitSetup.MAX_QTY))
        }
        return out
    }
}

/**
 * The curated item pool the **training-mode** editor may hand out as loaner gear, plus the two
 * classic presets. Server-authoritative: the client overlay shows the same pool, but every add is
 * validated here — an id outside the pool is refused in training mode. (Bank mode has no pool:
 * any item is fine in a kit because loading only ever withdraws what the player actually banks.)
 *
 * RSCM names resolve lazily; a bad name is logged and skipped, never fatal (the PK-training rule).
 */
object KitArmoury {

    /** name → (defaultQty, forced equip slot or null to let the item def decide). */
    private val POOL_DEFS: List<Pair<String, Int>> = listOf(
        // Melee
        "item.abyssal_whip" to 1, "item.dragon_dagger" to 1, "item.dragon_claws" to 1,
        "item.dharoks_greataxe" to 1, "item.dragon_defender" to 1,
        // Ranged
        "item.magic_shortbow" to 1, "item.rune_arrow" to 500,
        "item.black_dhide_body" to 1, "item.black_dhide_chaps" to 1,
        // Magic
        "item.ancient_staff" to 1, "item.mystic_hat" to 1, "item.mystic_robe_top" to 1,
        "item.mystic_robe_bottom" to 1, "item.occult_necklace" to 1,
        // Armour
        "item.dharoks_helm" to 1, "item.dharoks_platebody" to 1, "item.dharoks_platelegs" to 1,
        "item.helm_of_neitiznot" to 1, "item.fighter_torso" to 1, "item.fire_cape" to 1,
        "item.amulet_of_torture" to 1, "item.barrows_gloves" to 1, "item.dragon_boots" to 1,
        "item.berserker_ring" to 1, "item.berserker_ring_i" to 1,
        // Supplies
        "item.super_combat_potion4" to 1, "item.saradomin_brew4" to 1, "item.super_restore4" to 1,
        "item.shark" to 1, "item.cooked_karambwan" to 1,
        // Runes
        "item.astral_rune" to 5000, "item.death_rune" to 5000, "item.earth_rune" to 5000,
        "item.water_rune" to 5000, "item.blood_rune" to 5000,
    )

    /** Resolved id → default quantity. Lazy so a cache miss degrades to a smaller pool. */
    val pool: Map<Int, Int> by lazy {
        val out = HashMap<Int, Int>()
        POOL_DEFS.forEach { (name, qty) ->
            runCatching { out[getRSCM(name)] = qty }
                .onFailure { logger.warn { "kits: bad armoury rscm '$name': ${it.message}" } }
        }
        out
    }

    fun contains(id: Int): Boolean = pool.containsKey(id)
    fun defaultQty(id: Int): Int = pool[id] ?: 1

    // ── the two classic presets, shared with the PK trainer ──

    val DHAROK: KitSetup by lazy {
        preset(
            gear = mapOf(
                EquipmentType.HEAD to "item.dharoks_helm",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.dharoks_greataxe",
                EquipmentType.CHEST to "item.dharoks_platebody",
                EquipmentType.LEGS to "item.dharoks_platelegs",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
            inv = listOf(
                "item.super_combat_potion4" to 1,
                "item.saradomin_brew4" to 8,
                "item.super_restore4" to 4,
                "item.shark" to 4,
                "item.cooked_karambwan" to 4,
                "item.dragon_claws" to 1,
                "item.astral_rune" to 5000,
                "item.death_rune" to 5000,
                "item.earth_rune" to 5000,
            ),
            book = KitSetup.BOOK_LUNAR,
        )
    }

    val NH: KitSetup by lazy {
        preset(
            gear = mapOf(
                EquipmentType.HEAD to "item.helm_of_neitiznot",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.abyssal_whip",
                EquipmentType.CHEST to "item.fighter_torso",
                EquipmentType.SHIELD to "item.dragon_defender",
                EquipmentType.LEGS to "item.black_dhide_chaps",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring",
            ),
            inv = listOf(
                "item.mystic_hat" to 1, "item.mystic_robe_top" to 1, "item.mystic_robe_bottom" to 1,
                "item.ancient_staff" to 1, "item.occult_necklace" to 1,
                "item.magic_shortbow" to 1, "item.black_dhide_body" to 1, "item.rune_arrow" to 500,
                "item.dragon_claws" to 1,
                "item.saradomin_brew4" to 6, "item.super_restore4" to 4, "item.cooked_karambwan" to 3,
                "item.super_combat_potion4" to 1,
                "item.death_rune" to 5000, "item.water_rune" to 5000, "item.blood_rune" to 5000,
                "item.astral_rune" to 5000, "item.earth_rune" to 5000,
            ),
            book = KitSetup.BOOK_LUNAR,
        )
    }

    private fun preset(gear: Map<EquipmentType, String>, inv: List<Pair<String, Int>>, book: Int): KitSetup {
        val kit = KitSetup(book = book)
        gear.forEach { (slot, name) ->
            runCatching { kit.gear[slot.id] = Item(getRSCM(name)) }
                .onFailure { logger.warn { "kits: bad preset gear rscm '$name': ${it.message}" } }
        }
        var slot = 0
        inv.forEach { (name, amt) ->
            runCatching {
                val item = Item(getRSCM(name), amt.coerceIn(1, KitSetup.MAX_QTY))
                // Stackables sit in one slot; an unstackable "to 8" occupies 8 slots of one.
                if (item.getDef().stackable) {
                    if (slot < KitSetup.INV_SIZE) kit.inv[slot++] = item
                } else {
                    repeat(item.amount) { if (slot < KitSetup.INV_SIZE) kit.inv[slot++] = Item(item.id) }
                }
            }.onFailure { logger.warn { "kits: bad preset inv rscm '$name': ${it.message}" } }
        }
        return kit
    }
}
