package org.alter.plugins.content.magic

import dev.openrune.cache.CacheManager.getEnum
import dev.openrune.cache.CacheManager.getItem
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.ext.getEquipment
import org.alter.api.ext.getSpellbook
import org.alter.api.ext.getVarbit
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.rscm.RSCM.getRSCM

/**
 * @author Tom <rspsmods@gmail.com>
 */
object MagicSpells {
    const val INF_RUNES_VARBIT = 4145

    private const val SPELLBOOK_POINTER_ENUM = 1981

    private const val SPELL_SPELLBOOK_KEY = 336
    private const val SPELL_RUNE1_ID_KEY = 365
    private const val SPELL_RUNE1_AMT_KEY = 366
    private const val SPELL_RUNE2_ID_KEY = 367
    private const val SPELL_RUNE2_AMT_KEY = 368
    private const val SPELL_RUNE3_ID_KEY = 369
    private const val SPELL_RUNE3_AMT_KEY = 370
    private const val SPELL_COMPONENT_HASH_KEY = 596
    private const val SPELL_ID_KEY = 599
    private const val SPELL_NAME_KEY = 601
    private const val SPELL_DESC_KEY = 602
    private const val SPELL_LVL_REQ_KEY = 604
    private const val SPELL_TYPE_KEY = 605

    private const val COMBAT_SPELL_TYPE = 0
    private const val MISC_SPELL_TYPE = 1
    private const val TELEPORT_SPELL_TYPE = 2

    private val STAFF_ITEMS =
        arrayOf(
            "item.ibans_staff",
            "item.ibans_staff_u",
            "item.slayers_staff",
            "item.slayers_staff_e",
            "item.saradomin_staff",
            "item.guthix_staff",
            "item.zamorak_staff",
        )

    /**
     * Elemental staves supply an endless amount of their own element while wielded:
     * the rune is neither required to cast nor consumed. Combination staves supply
     * both of their elements. Keyed by rscm name -- [getRSCM] throws on an unknown
     * name, so a bad entry fails at first cast rather than silently granting nothing.
     */
    private val STAFF_RUNE_SOURCES: Map<String, Array<String>> =
        mapOf(
            // Air
            "item.staff_of_air" to arrayOf("item.air_rune"),
            "item.air_battlestaff" to arrayOf("item.air_rune"),
            "item.mystic_air_staff" to arrayOf("item.air_rune"),
            // Water
            "item.staff_of_water" to arrayOf("item.water_rune"),
            "item.water_battlestaff" to arrayOf("item.water_rune"),
            "item.mystic_water_staff" to arrayOf("item.water_rune"),
            "item.kodai_wand" to arrayOf("item.water_rune"),
            "item.kodai_wand_23626" to arrayOf("item.water_rune"),
            // Earth
            "item.staff_of_earth" to arrayOf("item.earth_rune"),
            "item.earth_battlestaff" to arrayOf("item.earth_rune"),
            "item.mystic_earth_staff" to arrayOf("item.earth_rune"),
            // Fire
            "item.staff_of_fire" to arrayOf("item.fire_rune"),
            "item.fire_battlestaff" to arrayOf("item.fire_rune"),
            "item.mystic_fire_staff" to arrayOf("item.fire_rune"),
            // Combination staves.
            "item.lava_battlestaff" to arrayOf("item.fire_rune", "item.earth_rune"),
            "item.lava_battlestaff_21198" to arrayOf("item.fire_rune", "item.earth_rune"),
            "item.mystic_lava_staff" to arrayOf("item.fire_rune", "item.earth_rune"),
            "item.mystic_lava_staff_21200" to arrayOf("item.fire_rune", "item.earth_rune"),
            "item.mud_battlestaff" to arrayOf("item.water_rune", "item.earth_rune"),
            "item.mystic_mud_staff" to arrayOf("item.water_rune", "item.earth_rune"),
            "item.steam_battlestaff" to arrayOf("item.water_rune", "item.fire_rune"),
            "item.steam_battlestaff_12795" to arrayOf("item.water_rune", "item.fire_rune"),
            "item.mystic_steam_staff" to arrayOf("item.water_rune", "item.fire_rune"),
            "item.mystic_steam_staff_12796" to arrayOf("item.water_rune", "item.fire_rune"),
            "item.smoke_battlestaff" to arrayOf("item.air_rune", "item.fire_rune"),
            "item.mystic_smoke_staff" to arrayOf("item.air_rune", "item.fire_rune"),
            "item.mist_battlestaff" to arrayOf("item.air_rune", "item.water_rune"),
            "item.mystic_mist_staff" to arrayOf("item.air_rune", "item.water_rune"),
            "item.dust_battlestaff" to arrayOf("item.air_rune", "item.earth_rune"),
            "item.mystic_dust_staff" to arrayOf("item.air_rune", "item.earth_rune"),
        )

    private val staffRunes: Map<Int, Set<Int>> by lazy {
        STAFF_RUNE_SOURCES.entries.associate { (staff, runes) ->
            getRSCM(staff) to runes.map { getRSCM(it) }.toSet()
        }
    }

    /**
     * Whether the weapon the player is wielding makes [runeId] free -- an air staff
     * covers the air runes of every spell, and so on.
     */
    fun suppliesRune(
        p: Player,
        runeId: Int,
    ): Boolean {
        val weapon = p.getEquipment(EquipmentType.WEAPON) ?: return false
        return staffRunes[weapon.id]?.contains(runeId) == true
    }

    private val metadata = Int2ObjectOpenHashMap<SpellMetadata>()

    fun getMetadata(spellId: Int): SpellMetadata? = metadata[spellId]

    fun getCombatSpells(): Map<Int, SpellMetadata> = metadata.filter { it.value.spellType == COMBAT_SPELL_TYPE }

    fun getTeleportSpells(): Map<Int, SpellMetadata> = metadata.filter { it.value.spellType == TELEPORT_SPELL_TYPE }

    fun getMiscSpells(): Map<Int, SpellMetadata> = metadata.filter { it.value.spellType == MISC_SPELL_TYPE }

    fun canCast(
        p: Player,
        lvl: Int,
        items: List<Item>,
        requiredBook: Int,
    ): Boolean {
        if (requiredBook != -1 && p.getSpellbook().id != requiredBook) {
            p.message("You can't cast this spell.")
            return false
        }
        if (p.getSkills().getBaseLevel(Skills.MAGIC) < lvl) {
            p.message("Your Magic level is not high enough for this spell.")
            return false
        }
        if (p.getVarbit(INF_RUNES_VARBIT) == 0) {
            for (item in items) {
                if (suppliesRune(p, item.id)) {
                    continue
                }
                if (p.inventory.getItemCount(item.id) < item.amount && p.equipment.getItemCount(item.id) < item.amount) {
                    p.message("You do not have enough ${item.getDef().name}s to cast this spell.")
                    return false
                }
            }
        }
        return true
    }

    fun removeRunes(
        p: Player,
        items: List<Item>,
    ) {
        if (p.getVarbit(INF_RUNES_VARBIT) == 0) {
            for (item in items) {
                /*
                 * Do not remove staff item requirements.
                 */
                if (item.id in getRSCM(STAFF_ITEMS)) {
                    continue
                }
                /*
                 * An elemental staff is an endless supply of its own rune -- don't
                 * charge the player for what the staff provides.
                 */
                if (suppliesRune(p, item.id)) {
                    continue
                }
                p.inventory.remove(item)
            }
        }
    }

    fun isLoaded(): Boolean = metadata.isNotEmpty()

    fun loadSpellRequirements(world: World) {
        val spellBookEnums = getEnum(SPELLBOOK_POINTER_ENUM)
        val spellBooks = spellBookEnums.values.values.map { it as Int }
        spellBooks.forEach { spellBook ->
            val spellBookEnum = getEnum(spellBook)
            val spellItems = spellBookEnum.values.values.map { it as Int }

            for (item in spellItems) {
                val itemDef = getItem(item)
                val params = itemDef.params ?: continue

                val spellbook = params[SPELL_SPELLBOOK_KEY] as Int
                val name = params[SPELL_NAME_KEY] as String
                val lvl = params[SPELL_LVL_REQ_KEY] as Int
                val componentHash = params[SPELL_COMPONENT_HASH_KEY] as Int
                val spellType = params[SPELL_TYPE_KEY] as Int

                val interfaceId = componentHash shr 16
                val component = componentHash and 0xFFFF
                val runes = mutableListOf<Item>()

                if (params.containsKey(SPELL_RUNE1_ID_KEY)) {
                    runes.add(Item(params[SPELL_RUNE1_ID_KEY] as Int, params[SPELL_RUNE1_AMT_KEY] as Int))
                }
                if (params.containsKey(SPELL_RUNE2_ID_KEY)) {
                    runes.add(Item(params[SPELL_RUNE2_ID_KEY] as Int, params[SPELL_RUNE2_AMT_KEY] as Int))
                }
                if (params.containsKey(SPELL_RUNE3_ID_KEY)) {
                    runes.add(Item(params[SPELL_RUNE3_ID_KEY] as Int, params[SPELL_RUNE3_AMT_KEY] as Int))
                }

                val spell = SpellMetadata(interfaceId, component, item, spellbook, spellType, name, lvl, runes)
                metadata[item] = spell
            }
        }
    }

    // fun KotlinPlugin.on_magic_spell_button(name: String, plugin: Plugin.(SpellMetadata) -> Unit) {
    //    if (!MagicSpells.isLoaded()) {
    //        MagicSpells.loadSpellRequirements(world)
    //    }
    //    // If this line throws an error, it means the spell with said name
    //    // is not found in cache.
    //    val spell = metadata.values.first { it.name == name }
    //    on_button(spell.interfaceId, spell.component) {
    //        plugin(this, spell)
    //    }
    // }
}
