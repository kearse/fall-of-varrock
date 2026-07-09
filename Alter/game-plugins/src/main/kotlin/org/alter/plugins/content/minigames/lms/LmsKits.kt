package org.alter.plugins.content.minigames.lms

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Spellbook
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * The OSRS-style **"build your kit"** loadout customization for Last Man Standing.
 *
 * Like real LMS, you bring nothing of your own — but you DO choose your starting loadout: an armour
 * style, a main weapon, a spec weapon, a magic pack (spellbook + runes), and a food plan. The choice
 * is made at Lisa ("Build my kit"), saved **persistently** per player ([LMS_KIT_ATTR]) so it applies
 * to every future game until changed, and dealt out — worn gear EQUIPPED, consumables in the pack —
 * the moment a game starts. Chest loot upgrades from there.
 *
 * All item keys are cache-verified; application is guarded so a bad key is skipped, never fatal.
 */
object LmsKits {

    /** Persistent per-player kit selection, a tiny JSON of category -> choice key. */
    val LMS_KIT_ATTR = AttributeKey<String>("lms_kit")

    /** One selectable kit piece: worn gear and/or carried items, plus an optional spellbook. */
    class Choice(
        val key: String,
        val label: String,
        val gear: Map<EquipmentType, String> = emptyMap(),
        val inv: List<Pair<String, Int>> = emptyList(),
        val spellbook: Spellbook? = null,
    )

    class Category(val key: String, val title: String, val choices: List<Choice>) {
        fun byKey(k: String?): Choice = choices.firstOrNull { it.key == k } ?: choices.first()
    }

    // ───────────────────────────── the kit menu ─────────────────────────────

    val ARMOUR = Category(
        "armour", "Armour style", listOf(
            Choice(
                "melee", "Melee (Neitiznot, Fighter torso, Defender)",
                gear = mapOf(
                    EquipmentType.HEAD to "item.helm_of_neitiznot",
                    EquipmentType.CHEST to "item.fighter_torso",
                    EquipmentType.LEGS to "item.rune_platelegs",
                    EquipmentType.SHIELD to "item.dragon_defender",
                    EquipmentType.BOOTS to "item.dragon_boots",
                    EquipmentType.GLOVES to "item.barrows_gloves",
                    EquipmentType.AMULET to "item.amulet_of_torture",
                    EquipmentType.RING to "item.berserker_ring",
                ),
            ),
            Choice(
                "ranged", "Ranged (Black d'hide)",
                gear = mapOf(
                    EquipmentType.HEAD to "item.coif",
                    EquipmentType.CHEST to "item.black_dhide_body",
                    EquipmentType.LEGS to "item.black_dhide_chaps",
                    EquipmentType.BOOTS to "item.snakeskin_boots",
                    EquipmentType.GLOVES to "item.barrows_gloves",
                    EquipmentType.AMULET to "item.amulet_of_glory",
                    EquipmentType.RING to "item.berserker_ring",
                ),
            ),
            Choice(
                "mage", "Magic (Mystic, Occult)",
                gear = mapOf(
                    EquipmentType.HEAD to "item.mystic_hat",
                    EquipmentType.CHEST to "item.mystic_robe_top",
                    EquipmentType.LEGS to "item.mystic_robe_bottom",
                    EquipmentType.BOOTS to "item.wizard_boots",
                    EquipmentType.GLOVES to "item.barrows_gloves",
                    EquipmentType.AMULET to "item.occult_necklace",
                    EquipmentType.RING to "item.berserker_ring",
                ),
            ),
            Choice(
                "hybrid", "Hybrid (d'hide + mystic switches carried)",
                gear = mapOf(
                    EquipmentType.HEAD to "item.helm_of_neitiznot",
                    EquipmentType.CHEST to "item.black_dhide_body",
                    EquipmentType.LEGS to "item.black_dhide_chaps",
                    EquipmentType.BOOTS to "item.dragon_boots",
                    EquipmentType.GLOVES to "item.barrows_gloves",
                    EquipmentType.AMULET to "item.amulet_of_glory",
                    EquipmentType.RING to "item.berserker_ring",
                ),
                inv = listOf(
                    "item.mystic_robe_top" to 1,
                    "item.mystic_robe_bottom" to 1,
                ),
            ),
        )
    )

    val WEAPON = Category(
        "weapon", "Main weapon", listOf(
            Choice("whip", "Abyssal whip", gear = mapOf(EquipmentType.WEAPON to "item.abyssal_whip")),
            Choice(
                "msb", "Magic shortbow (rune arrows)",
                gear = mapOf(
                    EquipmentType.WEAPON to "item.magic_shortbow",
                    EquipmentType.AMMO to "item.rune_arrow",
                ),
            ),
            Choice("staff", "Ancient staff", gear = mapOf(EquipmentType.WEAPON to "item.ancient_staff")),
        )
    )

    val SPEC = Category(
        "spec", "Special-attack weapon", listOf(
            Choice("dds", "Dragon dagger", inv = listOf("item.dragon_dagger" to 1)),
            Choice("gmaul", "Granite maul", inv = listOf("item.granite_maul" to 1)),
        )
    )

    val MAGIC = Category(
        "magic", "Magic pack", listOf(
            Choice(
                "ice", "Ice mage (Ancient book, barrage runes)",
                inv = listOf("item.death_rune" to 2000, "item.water_rune" to 4000, "item.blood_rune" to 1500),
                spellbook = Spellbook.ANCIENTS,
            ),
            Choice(
                "veng", "Vengeance (Lunar book, veng runes)",
                inv = listOf("item.astral_rune" to 1500, "item.death_rune" to 1500, "item.earth_rune" to 3000),
                spellbook = Spellbook.LUNAR,
            ),
            Choice("none", "No magic (Normal book)", spellbook = Spellbook.NORMAL),
        )
    )

    val FOOD = Category(
        "food", "Food plan", listOf(
            Choice(
                "brews", "Brews & restores (8 brews, 4 restores, karambwans)",
                inv = listOf(
                    "item.saradomin_brew4" to 8, "item.super_restore4" to 4,
                    "item.cooked_karambwan" to 4, "item.super_combat_potion4" to 1,
                ),
            ),
            Choice(
                "sharks", "Sharks (16 sharks, karambwans)",
                inv = listOf(
                    "item.shark" to 16, "item.cooked_karambwan" to 4, "item.super_combat_potion4" to 1,
                ),
            ),
        )
    )

    val CATEGORIES = listOf(ARMOUR, WEAPON, SPEC, MAGIC, FOOD)

    // ───────────────────────────── selection persistence ─────────────────────────────

    /** The player's current selection (category key -> choice key), defaulting each missing pick. */
    fun selection(p: Player): MutableMap<String, String> {
        val out = HashMap<String, String>()
        val blob = p.attr[LMS_KIT_ATTR]
        val doc = if (blob != null) runCatching { Document.parse(blob) }.getOrNull() else null
        CATEGORIES.forEach { cat ->
            val k = doc?.getString(cat.key)
            out[cat.key] = cat.byKey(k).key
        }
        return out
    }

    fun save(p: Player, categoryKey: String, choiceKey: String) {
        val sel = selection(p)
        sel[categoryKey] = choiceKey
        val doc = Document()
        sel.forEach { (k, v) -> doc.append(k, v) }
        p.attr[LMS_KIT_ATTR] = doc.toJson()
    }

    /** One-line summary of the player's kit, for Lisa's dialogue. */
    fun describe(p: Player): String {
        val sel = selection(p)
        return CATEGORIES.joinToString(", ") { cat -> cat.byKey(sel[cat.key]).label.substringBefore(" (") }
    }

    // ───────────────────────────── application ─────────────────────────────

    /**
     * Deal the player's built kit into their (already wiped) containers: gear EQUIPPED, consumables
     * in the pack. Returns the spellbook the kit wants (Normal if none picked). Ammo stacks and bad
     * keys are handled defensively.
     */
    fun apply(p: Player): Spellbook {
        val sel = selection(p)
        var book: Spellbook = Spellbook.NORMAL
        var slot = 0
        CATEGORIES.forEach { cat ->
            val choice = cat.byKey(sel[cat.key])
            choice.spellbook?.let { book = it }
            choice.gear.forEach { (equipSlot, name) ->
                runCatching {
                    val amount = if (equipSlot == EquipmentType.AMMO) ARROW_COUNT else 1
                    p.equipment[equipSlot.id] = Item(getRSCM(name), amount)
                }.onFailure { logger.warn { "lms-kit: bad gear rscm '$name': ${it.message}" } }
            }
            for ((name, amt) in choice.inv) {
                if (slot >= p.inventory.capacity) break
                val idx = slot
                runCatching { p.inventory[idx] = Item(getRSCM(name), amt) }
                    .onFailure { logger.warn { "lms-kit: bad inv rscm '$name': ${it.message}" } }
                slot++
            }
        }
        return book
    }

    private const val ARROW_COUNT = 400
}
