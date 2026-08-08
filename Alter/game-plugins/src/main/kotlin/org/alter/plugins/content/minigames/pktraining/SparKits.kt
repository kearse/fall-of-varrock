package org.alter.plugins.content.minigames.pktraining

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.Spellbook
import org.alter.api.WeaponType
import org.alter.api.ext.getSpellbook
import org.alter.game.action.EquipAction
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.bots.BotLoadout
import org.alter.plugins.content.bots.BotStats
import org.alter.plugins.content.bots.BotStyle
import org.alter.plugins.content.bots.FightProfile
import org.alter.plugins.content.bots.GearBlock
import org.alter.plugins.content.kits.KitSetup
import org.alter.rscm.RSCM.asRSCM
import org.alter.plugins.content.companion.Companion as CompanionPawn

private val logger = KotlinLogging.logger {}

/**
 * Builds the sparring companion's per-bout [BotLoadout] from a kit — its own current gear, a mirror
 * of the owner's loadout, or a kit-locker build. The conversion is what turns a pile of items into
 * an NH opponent: worn gear becomes the base style's [GearBlock], inventory wearables become the
 * other styles' switch overlays (exactly how a human carries switches), spec finishers arm the KO
 * rotation, and everything is level-gated against the COMPANION's own levels (skipped, not fatal —
 * reported once at bout start). The bout's Max Stats option waives the gate: at all-99s everything
 * is legal, and the gear is loaner-only either way.
 */
object SparKits {

    /** A built spar kit: the fight loadout + the exact inventory to deal + what didn't fit. */
    class Built(
        val loadout: BotLoadout,
        /** Inventory contents (dealt into the companion's container verbatim, by item id). */
        val invItems: List<Item>,
        /** Display names of level-gated kit items that were skipped. */
        val skipped: List<String>,
        /** Non-fatal notes for the owner (e.g. "no ranged weapon in the kit — ranged mode fell back"). */
        val notes: List<String>,
    )

    /** KO spec finishers the brain can rotate, in lead→follow-up priority order. */
    private val SPEC_FINISHERS = listOf(
        "item.armadyl_godsword", "item.dragon_claws", "item.voidwaker", "item.dragon_dagger", "item.granite_maul",
    )

    /** Snapshot [p]'s REAL worn gear + carried inventory as a kit (the Mirror source). */
    fun snapshotKit(p: Player): KitSetup {
        val kit = KitSetup()
        for (type in EquipmentType.values) {
            val worn = p.equipment[type.id] ?: continue
            kit.gear[type.id] = Item(worn.id, worn.amount.coerceIn(1, KitSetup.MAX_QTY))
        }
        for (i in 0 until minOf(p.inventory.capacity, KitSetup.INV_SIZE)) {
            val carried = p.inventory[i] ?: continue
            kit.inv[i] = Item(carried.id, carried.amount.coerceIn(1, KitSetup.MAX_QTY))
        }
        kit.book = when (p.getSpellbook()) {
            Spellbook.ANCIENTS -> KitSetup.BOOK_ANCIENTS
            Spellbook.LUNAR -> KitSetup.BOOK_LUNAR
            else -> KitSetup.BOOK_STANDARD
        }
        return kit
    }

    /** Snapshot the sparring companion's REAL (stashed) containers as a kit (the Current-gear source). */
    fun kitFromStash(s: CompanionSparring.SparSession): KitSetup {
        val kit = KitSetup()
        s.realEquip.forEach { (slot, it) -> kit.gear[slot] = Item(it.id, it.amount) }
        s.realInv.forEach { (slot, it) -> if (slot < KitSetup.INV_SIZE) kit.inv[slot] = Item(it.id, it.amount) }
        return kit
    }

    /**
     * Convert [kit] into the sparring companion's fight loadout. [comp]'s levels gate every gear
     * item unless the bout runs at Max Stats. The heavy lifting:
     *  1. classify every wearable into MELEE/RANGED/MAGIC (weapon type for weapons, name heuristics
     *     for armour; unclassified pieces stay in the base block only),
     *  2. worn gear = the base block; each other style with a weapon = base overlaid with that
     *     style's switch pieces (how a human actually switches),
     *  3. spec finishers from the inventory arm [BotLoadout.meleeSpecRotation],
     *  4. single-style modes filter the gear map to one block — `chooseOffence` only ever picks
     *     from `gear.keys`, so that alone enforces the mode.
     */
    fun toLoadout(kit: KitSetup, comp: CompanionPawn, settings: SparSettings): Built {
        val skipped = ArrayList<String>()
        val notes = ArrayList<String>()
        val waiveGate = settings.rules.maxStats

        // ── collect wearables that pass the level gate ──
        class Piece(val type: EquipmentType, val id: Int, val style: BotStyle?)

        fun gateOk(id: Int): Boolean =
            waiveGate || EquipAction.meetsLevelRequirements(comp, id).also {
                if (!it) skipped += itemName(id)
            }

        val worn = ArrayList<Piece>()
        kit.gear.forEach { (slotId, item) ->
            val type = EquipmentType.values.firstOrNull { it.id == slotId } ?: return@forEach
            if (!gateOk(item.id)) return@forEach
            worn += Piece(type, item.id, classify(item.id))
        }
        // Inventory wearables are the switch pieces; everything else is dealt as consumables.
        val switches = ArrayList<Piece>()
        val consumables = ArrayList<Item>()
        kit.inv.values.forEach { item ->
            val def = runCatching { getItem(item.id) }.getOrNull() ?: return@forEach
            val type = if (def.equipSlot >= 0) EquipmentType.values.firstOrNull { it.id == def.equipSlot } else null
            if (type != null && isWearable(item.id)) {
                if (!gateOk(item.id)) return@forEach
                switches += Piece(type, item.id, classify(item.id))
                // Spec weapons ride in the inventory too (the brain equips them straight from the
                // rotation), and stackable switch ammo should still be dealt.
                if (rscmOf(item.id) in SPEC_FINISHERS || type == EquipmentType.AMMO) {
                    consumables += Item(item.id, item.amount)
                }
            } else {
                consumables += Item(item.id, item.amount)
            }
        }

        // ── base style + per-style blocks ──
        val wornWeapon = worn.firstOrNull { it.type == EquipmentType.WEAPON }
        val baseStyle = wornWeapon?.style ?: comp.archetype.botStyle
        val base = HashMap<EquipmentType, String>()
        worn.forEach { p -> rscmOf(p.id)?.let { base[p.type] = it } ?: run { skipped += itemName(p.id) } }

        val gear = HashMap<BotStyle, GearBlock>()
        gear[baseStyle] = HashMap(base)
        for (style in BotStyle.values()) {
            if (style == baseStyle) continue
            val styleSwitches = switches.filter { it.style == style }
            if (styleSwitches.none { it.type == EquipmentType.WEAPON }) continue // no weapon → no block
            val block = HashMap(base)
            // A switch style drops the base block's weapon-adjacent slots it doesn't replace: the
            // shield stays (humans keep defenders through msb switches), ammo stays, armour overlays.
            styleSwitches.forEach { p -> rscmOf(p.id)?.let { block[p.type] = it } }
            // A two-handed switch weapon clears the shield slot.
            val weap = styleSwitches.first { it.type == EquipmentType.WEAPON }
            val wDef = runCatching { getItem(weap.id) }.getOrNull()
            if (wDef != null && wDef.equipType == EquipmentType.SHIELD.id) block.remove(EquipmentType.SHIELD)
            gear[style] = block
        }

        // ── magic: level-pick the ice spell; a castless block is dead weight ──
        val magicLevel = if (waiveGate) 99 else comp.getSkills().getBaseLevel(Skills.MAGIC)
        val spellKey = when {
            magicLevel >= 94 -> "ice_barrage"
            magicLevel >= 82 -> "ice_blitz"
            magicLevel >= 70 -> "ice_burst"
            else -> null
        }
        if (gear.containsKey(BotStyle.MAGIC) && spellKey == null && baseStyle != BotStyle.MAGIC) {
            gear.remove(BotStyle.MAGIC)
            notes += "Sir ${comp.username}'s Magic level is too low to cast in a fight — the magic switch sits out."
        }

        // ── single-style modes filter the map ──
        val requested = when (settings.fightStyle) {
            SparStyle.FULL_NH -> null
            SparStyle.MELEE -> BotStyle.MELEE
            SparStyle.RANGED -> BotStyle.RANGED
            SparStyle.MAGIC -> BotStyle.MAGIC
        }
        var finalBase = baseStyle
        if (requested != null) {
            if (gear.containsKey(requested)) {
                val keep = gear[requested]!!
                gear.clear(); gear[requested] = keep
                finalBase = requested
            } else {
                notes += "The kit has no ${requested.name.lowercase()} weapon — Sir ${comp.username} fights with what it has."
            }
        }
        if (!gear.containsKey(finalBase)) finalBase = gear.keys.first()

        // ── spec rotation from carried finishers (lead → instant follow-up order) ──
        val carriedSpecs = switches.mapNotNull { rscmOf(it.id) }.filter { it in SPEC_FINISHERS }
        val rotation = if (settings.rules.noSpec) emptyList() else SPEC_FINISHERS.filter { it in carriedSpecs }

        val s = comp.getSkills()
        fun lvl(skill: Int) = if (waiveGate) 99 else s.getBaseLevel(skill)
        val diff = settings.difficulty
        val loadout = BotLoadout(
            key = "spar_${comp.username.lowercase().replace(' ', '_')}",
            displayName = comp.username,
            tier = "companion",
            combatLevel = comp.combatLevel,
            stats = BotStats(
                attack = lvl(Skills.ATTACK), strength = lvl(Skills.STRENGTH), defence = lvl(Skills.DEFENCE),
                hitpoints = lvl(Skills.HITPOINTS), ranged = lvl(Skills.RANGED),
                magic = magicLevel, prayer = lvl(Skills.PRAYER),
            ),
            baseStyle = finalBase,
            gear = gear,
            spell = if (spellKey != null && gear.containsKey(BotStyle.MAGIC)) mapOf(BotStyle.MAGIC to spellKey) else emptyMap(),
            meleeSpecRotation = rotation,
            usesPrayer = diff.usesPrayer && !settings.rules.noPrayer,
            eatAt = diff.eatAt,
            profile = FightProfile(koAtHp = 55, baitOneIn = diff.baitOneIn, specOnPidSwap = diff.specOnPidSwap),
        )
        // Consumables are dealt verbatim by id (no rscm round-trip); honour the no-food/no-potions
        // rules by simply not dealing what the brain would eat/drink.
        val dealt = consumables.filterNot {
            (settings.rules.noFood && isFood(it.id)) || (settings.rules.noDrinks && isPotion(it.id))
        }
        return Built(loadout, dealt, skipped, notes)
    }

    // ── classification ──

    private val RANGED_WEAPON_TYPES = setOf(
        WeaponType.BOW.id, WeaponType.CROSSBOW.id, WeaponType.THROWN.id, WeaponType.CHINCHOMPA.id,
    )
    private val MAGIC_WEAPON_TYPES = setOf(
        WeaponType.STAFF.id, WeaponType.MAGIC_STAFF.id, WeaponType.STAFF_HALBERD.id, WeaponType.TRIDENT.id,
    )

    private val RANGED_HINTS = listOf(
        "dhide", "d'hide", "karil", "masori", "morrigan", "coif", "snakeskin", "archer", "anguish",
        "ava's", "accumulator", "pegasian", "arrow", "bolt", "dart", "knife", "javelin",
    )
    private val MAGIC_HINTS = listOf(
        "mystic", "ahrim", "ancestral", "occult", "infinity", "seers", "tormented", "eternal",
        "wizard", "robe", "zamorak monk", "farseer", "moonclan", "splitbark",
    )

    /** Which fight school a wearable belongs to; null = neutral (rides in the base block only). */
    private fun classify(itemId: Int): BotStyle? {
        val def = runCatching { getItem(itemId) }.getOrNull() ?: return null
        if (def.equipSlot == EquipmentType.WEAPON.id) {
            return when (def.weaponType) {
                in RANGED_WEAPON_TYPES -> BotStyle.RANGED
                in MAGIC_WEAPON_TYPES -> BotStyle.MAGIC
                else -> BotStyle.MELEE
            }
        }
        val name = (def.name ?: return null).lowercase()
        return when {
            RANGED_HINTS.any { it in name } -> BotStyle.RANGED
            MAGIC_HINTS.any { it in name } -> BotStyle.MAGIC
            else -> null
        }
    }

    private fun isWearable(itemId: Int): Boolean {
        val def = runCatching { getItem(itemId) }.getOrNull() ?: return false
        return def.interfaceOptions.any {
            it != null && (it.equals("Wield", true) || it.equals("Wear", true) ||
                it.equals("Equip", true) || it.equals("Hold", true))
        }
    }

    private fun isFood(itemId: Int): Boolean {
        val name = runCatching { getItem(itemId).name }.getOrNull()?.lowercase() ?: return false
        return listOf("shark", "karambwan", "anglerfish", "manta", "lobster", "swordfish", "salmon", "trout").any { it in name }
    }

    private fun isPotion(itemId: Int): Boolean {
        val name = runCatching { getItem(itemId).name }.getOrNull()?.lowercase() ?: return false
        return "potion" in name || "brew" in name || "restore" in name
    }

    /** Reverse item-id → RSCM name (`item.*`), or null (skipped with a message) for unmapped ids. */
    private fun rscmOf(id: Int): String? = runCatching { id.asRSCM("item") }.getOrNull()

    private fun itemName(id: Int): String = runCatching { getItem(id).name ?: "item $id" }.getOrDefault("item $id")
}
