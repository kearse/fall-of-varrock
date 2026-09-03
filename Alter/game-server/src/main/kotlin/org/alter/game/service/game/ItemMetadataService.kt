package org.alter.game.service.game

import AnimationData
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.openrune.cache.CacheManager
import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.filestore.definition.data.ItemType
import dev.openrune.cache.filestore.definition.data.ParamMapper
import gg.rsmod.util.ServerProperties
import gg.rsmod.util.Stopwatch
import io.github.oshai.kotlinlogging.KotlinLogging
import it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.yaml.snakeyaml.LoaderOptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * @author Tom <rspsmods@gmail.com>
 */
class ItemMetadataService : Service {
    override fun init(
        server: Server,
        world: World,
        serviceProperties: ServerProperties,
    ) {
        loadAll()
    }

    var ms: Long = 0
    fun loadAll() {
        val stopwatch = Stopwatch.createStarted().reset().start()
        val loaderOptions = LoaderOptions()
        loaderOptions.codePointLimit = 10 * 1024 * 1024 // 10 MB
        val yamlFactory =
            YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .build()
        val mapper = YAMLMapper(yamlFactory)

        val path = Paths.get("../data/cfg/items")

        try {
            /**
             * Loads item examine text from an external CSV file and assigns it to item definitions.
             *
             * The file is expected to be located at `../data/cfg/objs.csv` and should contain item IDs
             * paired with their respective examine text, separated by commas.
             *
             * - The first value in each line is treated as the item ID.
             * - The remaining text after the first comma is treated as the examine description.
             * - The examine text is assigned to the corresponding item definition if the ID is valid.
             *
             * This ensures that item examine information gets loaded from an external source at runtime.
             */
            Paths.get("../data/cfg/objs.csv").toFile().forEachLine { line ->
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val id = parts[0].toIntOrNull()
                    val examine = line.substringAfter(',').trim()
                    if (id != null) {
                        getItem(id).examine = examine
                    }
                }
            }

            /**
             * Initializes item definitions by loading cached item configurations and updating specific attributes.
             *
             * - Adjusts item weight by dividing the cached value by 1000.
             * - Sets the attack speed using a validated parameter (ID 14).
             * - Determines the weapon type for equippable items in the weapon slot (equipSlot 3) based on their category.
             * - Assigns the equip type from the item's appearance override.
             * - Populates item bonuses using a predefined set of validated parameters.
             *
             * This process ensures that item attributes are properly loaded and validated from cache for use in gameplay.
             */
            var wearReqCount = 0
            CacheManager.getItems().forEach { (_, item) ->
                val def = getItem(item.id)

                def.weight /= 1000
                def.equipType = def.appearanceOverride1

                def.attackSpeed = def.getValidatedParam(
                    ParamMapper.item.ATTACK_RATE,
                    7
                ) // Just in case the Attack Rate would be not configurated in cache.

                if (def.equipSlot == 3) {
                    def.weaponType = WeaponCategory.get(def, def.category)
                }


                def.bonuses =
                    intArrayOf(
                        def.getValidatedParam(ParamMapper.item.STAB_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.SLASH_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.CRUSH_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.RANGED_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.STAB_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.SLASH_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.CRUSH_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.RANGED_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.MELEE_STRENGTH),
                        def.getValidatedParam(ParamMapper.item.RANGED_STRENGTH_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_DAMAGE_STRENGTH) / 10,
                        def.getValidatedParam(ParamMapper.item.PRAYER_BONUS),
                    )

                // EVERY equippable item keeps its classic cache skill-level requirements — weapons
                // AND armour (40 Defence for rune, 60 Attack for a dragon scimitar, ...). Armour is
                // ADDITIONALLY gated by the player's bought feudal rank (TitlePlugin's armour-tier
                // gate, see Title.kt / docs/war-system-design.md) — both checks must pass.
                if (def.equipSlot >= 0 && def.params?.containsKey(ParamMapper.item.PRIMARY_SKILL) == true) {
                    // Only write requirement pairs whose skill param actually exists in the cache.
                    // Unconditional puts used the 0-defaults for absent secondary/tertiary/quaternary
                    // pairs, writing (skill=0, level=0) — which overwrote the attack (skill id 0)
                    // requirement of every weapon and made them equippable at any level.
                    def.skillReqs = Byte2ByteOpenHashMap().apply {
                        fun putReq(skillParam: Int, levelParam: Int) {
                            if (def.params?.containsKey(skillParam) == true) {
                                // Only real skill ids (0-22). Anything outside that range is a
                                // quest/pseudo requirement — this server has NO quest requirements
                                // on gear, and an out-of-range id would OOB the skill tables.
                                val skill = def.getValidatedParam(skillParam)
                                if (skill in 0..22) {
                                    put(skill.toByte(), def.getValidatedParam(levelParam).toByte())
                                }
                            }
                        }
                        putReq(ParamMapper.item.PRIMARY_SKILL, ParamMapper.item.PRIMARY_LEVEL)
                        putReq(ParamMapper.item.SECONDARY_SKILL, ParamMapper.item.SECONDARY_LEVEL)
                        putReq(ParamMapper.item.TERTIARY_SKILL, ParamMapper.item.TERTIARY_LEVEL)
                        putReq(ParamMapper.item.QUATERNARY_SKILL, ParamMapper.item.QUATERNARY_LEVEL)
                    }
                    wearReqCount++
                }
            }
            logger.info { "Loaded classic equip skill requirements for $wearReqCount items (armour is additionally feudal-rank gated)." }

            /**
             * Loads and assigns render animations to item definitions from external JSON files.
             *
             * - `bas_mappings.json` maps animation identifiers to their corresponding animation data (e.g., ready, walk, run animations).
             * - `item_bas.json` maps item IDs to the animation identifiers used in the mappings.
             *
             * The process:
             * - Each item ID from `item_bas.json` is matched to its animation data from `bas_mappings.json`.
             * - If a matching animation is found, it populates the item's render animations array with the relevant animation IDs.
             *
             * This ensures that items have appropriate movement and action animations during gameplay.
             */
            val animationMap: Map<String, AnimationData> =
                mapper.readValue(File("../data/cfg/items/renderAnimations/bas_mappings.json").readText())
            val valueMap: Map<Int, Int> = ObjectMapper().apply {
                findAndRegisterModules()
            }.readValue(File("../data/cfg/items/renderAnimations/item_bas.json").readText())
            valueMap.forEach { (item, animMap) ->
                val animation = animationMap[animMap.toString()] ?: return@forEach
                val def = getItem(item)
                def.renderAnimations = intArrayOf(
                    animation.readyAnim,
                    animation.turnAnim,
                    animation.walkAnim,
                    animation.walkAnimBack,
                    animation.walkAnimLeft,
                    animation.walkAnimRight,
                    animation.runAnim,
                )
            }

            /**
             * Loads item override metadata from all files within the "itemOverrides" directory.
             *
             * - The directory is resolved relative to the provided path.
             * - Files are processed in parallel for efficient loading.
             * - Each file is deserialized into a `Metadata` object and passed to the `load` function.
             *
             * This process ensures that custom item attributes or behaviors are loaded at runtime.
             *
             * @TODO Add better context as to why file could not be loaded.
             * @TODO Add support for remaining [`def`] properties override method.
             */
            // Sequential + sorted: the walk used to be a parallel stream, which made two
            // documents targeting the same item id apply in NONDETERMINISTIC order (the
            // duplicated barrows ids surfaced as randomly-named items across boots).
            val overridesApplied = java.util.concurrent.atomic.AtomicInteger()
            Files.walk(path.resolve("itemOverrides")).sorted().filter { it.toFile().isFile }.forEach { file ->
                if (file.fileName.toString().contains("FileExample.yml")) return@forEach

                val content = file.toFile().readText()
                content.split(Regex("(?m)^---\\s*$"))
                    .filter { it.isNotBlank() }.forEach { document ->
                        // One malformed document must not abort the whole override walk (the outer
                        // catch would silently drop an arbitrary subset of the parallel stream).
                        try {
                            val data = mapper.readValue(document, Metadata::class.java)
                            load(data)
                            overridesApplied.incrementAndGet()
                        } catch (e: Exception) {
                            logger.error(e) { "Item override document failed to apply in $file — skipped." }
                        }
                    }
            }
            logger.info { "Applied ${overridesApplied.get()} item override documents." }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        ms = stopwatch.elapsed(TimeUnit.MILLISECONDS)
    }

    fun load(item: Metadata) {
        val def = getItem(item.id)

        if (item.name.isNotBlank()) def.name = item.name
        // Only when declared: every override document that omitted `examine:` (all of barrows/**,
        // classicTierReqs/**) was blanking the examine text objs.csv loaded a moment earlier.
        item.examine?.let { def.examine = it }
        // Only apply when the document declares it. With the old non-null `= false` default,
        // every override file that omitted `tradeable:` (all of barrows/**) silently flagged
        // its items untradeable in the cache def — kept on death, refused by the GE.
        item.tradeable?.let { def.isTradeable = it }
        item.weight?.let { def.weight = it }

        // Economy override fields. These were parsed and silently DROPPED — TradeableCapes.yml's
        // "cost/alch MUST stay 0" safety note relied on them applying, which left the fire cape
        // alchable at its 65k cache Value the moment the Jad port started handing capes out.
        // cost writes straight to the cache def (alch/shop/TP pricing all derive from it);
        // explicit low/high alch values, GE exclusion and buy limits land in the override maps
        // below for the systems that consume them (AlchemyPlugin, GrandExchange).
        item.cost?.let { def.cost = it }
        item.lowalch?.let { lowAlchOverrides[item.id] = it }
        item.highalch?.let { highAlchOverrides[item.id] = it }
        item.buy_limit?.let { buyLimits[item.id] = it }
        if (item.tradeable_on_ge == false) geExcluded.add(item.id)

        if (item.equipment != null) {
            val equipment = item.equipment
            val slots = if (equipment.equipSlot != null) getEquipmentSlots(equipment.equipSlot, def.id) else null

            /**
             * TODO def.attackSounds = equipment.attackSounds
             *  - Create Array of AttackStyleID -> It's Sound
             *  accurateAnim : accurateSound
             *  aggressiveAnim : aggressiveSound
             *  controlledAnim : controlledSound
             *  defensiveAnim : defensiveSound
             *  <--- AttackStyleID:[Anim, Sound]
             *  AttackStyle can be from 0-3
             *  If no data on it, it will be -1
             *  blockAnim = When target attacks the Pawn on next tick?
             *
             *
             *  TODO def.equipSound = equipment.equipSound
             */
            if (slots != null) {
                // Full-definition override: the YAML claims the item's whole equipment identity
                // (equipSlot provided), so absent fields fall back to the historical sentinels and
                // zeroes — the CustomLaunch cosmetics rely on this implicit stat wipe.
                def.attackSpeed = equipment.attackSpeed ?: -1
                val weaponType = equipment.weaponType ?: -1
                if (weaponType == -1) {
                    if (slots.slot == 3) def.weaponType = 17
                } else {
                    def.weaponType = weaponType
                }
                def.renderAnimations = equipment.renderAnimations?.getAsArray()
                def.equipSlot = slots.slot
                def.equipType = slots.secondary
                def.bonuses = intArrayOf(
                    equipment.attackStab ?: 0,
                    equipment.attackSlash ?: 0,
                    equipment.attackCrush ?: 0,
                    equipment.attackMagic ?: 0,
                    equipment.attackRanged ?: 0,
                    equipment.defenceStab ?: 0,
                    equipment.defenceSlash ?: 0,
                    equipment.defenceCrush ?: 0,
                    equipment.defenceMagic ?: 0,
                    equipment.defenceRanged ?: 0,
                    equipment.meleeStrength ?: 0,
                    equipment.rangedStrength ?: 0,
                    equipment.magicDamage ?: 0,
                    equipment.prayer ?: 0,
                )
            } else {
                // Partial override: no equipSlot means the document only patches the fields it
                // names (the barrows files carry just skillReqs; warlords_regalia lists every
                // bonus explicitly). Absent fields keep their cache-loaded values — writing the
                // -1/0 defaults here wiped attack speed, weapon type and every bonus of all
                // Barrows gear, turning a Dharok's greataxe into a 1-tick unarmed kick.
                equipment.attackSpeed?.let { def.attackSpeed = it }
                equipment.weaponType?.let { def.weaponType = it }
                equipment.renderAnimations?.let { def.renderAnimations = it.getAsArray() }
                val bonusOverrides = arrayOf(
                    equipment.attackStab,
                    equipment.attackSlash,
                    equipment.attackCrush,
                    equipment.attackMagic,
                    equipment.attackRanged,
                    equipment.defenceStab,
                    equipment.defenceSlash,
                    equipment.defenceCrush,
                    equipment.defenceMagic,
                    equipment.defenceRanged,
                    equipment.meleeStrength,
                    equipment.rangedStrength,
                    equipment.magicDamage,
                    equipment.prayer,
                )
                if (bonusOverrides.any { it != null }) {
                    val bonuses = def.bonuses.copyOf(bonusOverrides.size)
                    bonusOverrides.forEachIndexed { i, v -> if (v != null) bonuses[i] = v }
                    def.bonuses = bonuses
                }
            }

            // Same rule as the cache load above: YAML-override skill reqs apply to any equippable
            // item — weapons and armour alike (armour is additionally rank-gated by TitlePlugin).
            if (equipment.skillReqs != null) {
                val reqs = Byte2ByteOpenHashMap()
                equipment.skillReqs.filter { it.skill != null }.forEach { req ->
                    reqs[getSkillId(req.skill!!)] = req.level!!.toByte()
                }

                def.skillReqs = reqs
            }
        }
    }

    private fun getEquipmentSlots(
        slot: String,
        id: Int? = null,
    ): EquipmentSlots {
        val equipSlot: Int
        var equipType = -1
        when (slot) {
            "hat" -> equipSlot = 0
            "cape" -> equipSlot = 1
            "neck" -> equipSlot = 2
            "weapon" -> equipSlot = 3
            "torso" -> equipSlot = 4
            "shield" -> equipSlot = 5
            "legs" -> equipSlot = 7
            "hands" -> equipSlot = 9
            "feet" -> equipSlot = 10
            "ring" -> equipSlot = 12
            "ammo" -> equipSlot = 13

            "head" -> {
                equipSlot = 0
                equipType = 8
            }
            // For hats that requires hair removal
            "nohair" -> {
                equipSlot = 0
                equipType = 11
            }

            "2h" -> {
                equipSlot = 3
                equipType = 5
            }

            "body" -> {
                equipSlot = 4
                equipType = 6
            }

            else -> throw IllegalArgumentException("Illegal equipment slot: $slot, $id")
        }
        return EquipmentSlots(equipSlot, equipType)
    }

    private data class EquipmentSlots(val slot: Int, val secondary: Int)


    private fun ItemType.getValidatedParam(key: Int, defaultValue: Int = 0): Int {
        if (this.params?.get(key) != null) {
            try {
                return this.params?.get(key) as Int
            } catch (e: Exception) {
                println("${this.id} || ${this.params}")
                e.printStackTrace()
            }
        }

        /**
         * @TODO Rethink the logic, gets printed out even for items that are not wearable.
         * logger.warn {
         *   "Item with ID: ${this.id} is missing the key '$key' in its params. Full params list: ${this.params}. Default value was set: $defaultValue."
         * }
         */
        return defaultValue
    }

    private fun getSkillId(name: String): Byte =
        when (name) {
            // Need to get a better dump db. As we can see, this one has some
            // inconsistency for some reason.
            "attack" -> 0
            "defence" -> 1
            "strength" -> 2
            "hitpoints" -> 3
            "range", "ranged" -> 4
            "prayer" -> 5
            "magic" -> 6
            "cooking" -> 7
            "woodcutting" -> 8
            "fletching" -> 9
            "fishing" -> 10
            "firemaking" -> 11
            "crafting" -> 12
            "smithing" -> 13
            "mining" -> 14
            "herblore" -> 15
            "agility" -> 16
            "thieving", "theiving" -> 17
            "slayer" -> 18
            "farming" -> 19
            "runecrafting", "runecraft" -> 20
            "hunter" -> 21
            "construction", "contruction" -> 22
            "combat" -> 3
            else -> throw IllegalArgumentException("Illegal skill name: $name")
        }

    data class Metadata(
        val id: Int = -1,
        val name: String = "",
        val examine: String? = null,
        // Nullable: an absent key leaves the cache default in place (see load()).
        val tradeable: Boolean? = null,
        val weight: Double? = null,
        // Nullable so an absent YAML key is distinguishable from an explicit value — with
        // the old `= 0` defaults, applying these would have zeroed every overridden item.
        val tradeable_on_ge: Boolean? = null,
        val cost: Int? = null,
        val lowalch: Int? = null,
        val highalch: Int? = null,
        val buy_limit: Int? = null,
        val equipment: Equipment? = null,
    )

    // Every stat field is nullable so an absent YAML key is distinguishable from an explicit
    // value: load() treats a document without equipSlot as a PARTIAL override and leaves the
    // cache-loaded value in place for any field left null.
    data class Equipment(
        @JsonProperty("equip_slot") val equipSlot: String? = null,
        @JsonProperty("equip_sound") val equipSound: Int? = -1,
        @JsonProperty("weapon_type") val weaponType: Int? = null,
        @JsonProperty("attack_speed") val attackSpeed: Int? = null,
        @JsonProperty("attack_stab") val attackStab: Int? = null,
        @JsonProperty("attack_slash") val attackSlash: Int? = null,
        @JsonProperty("attack_crush") val attackCrush: Int? = null,
        @JsonProperty("attack_magic") val attackMagic: Int? = null,
        @JsonProperty("attack_ranged") val attackRanged: Int? = null,
        @JsonProperty("defence_stab") val defenceStab: Int? = null,
        @JsonProperty("defence_slash") val defenceSlash: Int? = null,
        @JsonProperty("defence_crush") val defenceCrush: Int? = null,
        @JsonProperty("defence_magic") val defenceMagic: Int? = null,
        @JsonProperty("defence_ranged") val defenceRanged: Int? = null,
        @JsonProperty("melee_strength") val meleeStrength: Int? = null,
        @JsonProperty("ranged_strength") val rangedStrength: Int? = null,
        @JsonProperty("magic_damage") val magicDamage: Int? = null,
        @JsonProperty("prayer") val prayer: Int? = null,
        @JsonProperty("render_animations") val renderAnimations: RenderAnimations? = null,
        @JsonProperty("attackSounds") val attackSounds: IntArray? = null,
        @JsonProperty("skill_reqs") val skillReqs: Array<SkillRequirement>? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Equipment
            if (equipSlot != other.equipSlot) return false
            if (equipSound != other.equipSound) return false
            if (weaponType != other.weaponType) return false
            if (attackSpeed != other.attackSpeed) return false
            if (attackStab != other.attackStab) return false
            if (attackSlash != other.attackSlash) return false
            if (attackCrush != other.attackCrush) return false
            if (attackMagic != other.attackMagic) return false
            if (attackRanged != other.attackRanged) return false
            if (defenceStab != other.defenceStab) return false
            if (defenceSlash != other.defenceSlash) return false
            if (defenceCrush != other.defenceCrush) return false
            if (defenceMagic != other.defenceMagic) return false
            if (defenceRanged != other.defenceRanged) return false
            if (meleeStrength != other.meleeStrength) return false
            if (rangedStrength != other.rangedStrength) return false
            if (magicDamage != other.magicDamage) return false
            if (prayer != other.prayer) return false

            if (renderAnimations != null) {
                if (other.renderAnimations == null) return false
            } else if (other.renderAnimations != null) {
                return false
            }

            if (attackSounds != null) return false

            if (skillReqs != null) {
                if (other.skillReqs == null) return false
                if (!skillReqs.contentEquals(other.skillReqs)) return false
            } else if (other.skillReqs != null) {
                return false
            }

            return true
        }

        override fun hashCode(): Int {
            var result = equipSlot?.hashCode() ?: 0
            result = 31 * result + (weaponType ?: -1)
            result = 31 * result + (attackSpeed ?: -1)
            result = 31 * result + (attackStab ?: 0)
            result = 31 * result + (attackSlash ?: 0)
            result = 31 * result + (attackCrush ?: 0)
            result = 31 * result + (attackMagic ?: 0)
            result = 31 * result + (attackRanged ?: 0)
            result = 31 * result + (defenceStab ?: 0)
            result = 31 * result + (defenceSlash ?: 0)
            result = 31 * result + (defenceCrush ?: 0)
            result = 31 * result + (defenceMagic ?: 0)
            result = 31 * result + (defenceRanged ?: 0)
            result = 31 * result + (meleeStrength ?: 0)
            result = 31 * result + (rangedStrength ?: 0)
            result = 31 * result + (magicDamage ?: 0)
            result = 31 * result + (prayer ?: 0)
            result = 31 * result + (renderAnimations?.getAsArray().contentHashCode())
            result = 31 * result + (skillReqs?.contentHashCode() ?: 0)
            return result
        }
    }

    data class RenderAnimations(
        @JsonProperty("standAnimId") val standAnimId: Int = 0,
        @JsonProperty("turnOnSpotAnim") val turnOnSpotAnim: Int = 0,
        @JsonProperty("walkForwardAnimId") val walkForwardAnimId: Int = 0,
        @JsonProperty("walkBackwardsAnimId") val walkBackwardsAnimId: Int = 0,
        @JsonProperty("walkLeftAnimId") val walkLeftAnimId: Int = 0,
        @JsonProperty("walkRightAnimId") val walkRightAnimId: Int = 0,
        @JsonProperty("runAnimId") val runAnimId: Int = 0,
    ) {
        fun getAsArray(): IntArray {
            return listOf(
                standAnimId,
                turnOnSpotAnim,
                walkForwardAnimId,
                walkBackwardsAnimId,
                walkLeftAnimId,
                walkRightAnimId,
                runAnimId
            ).toIntArray()
        }
    }

    data class SkillRequirement(
        @JsonProperty("skill") val skill: String?,
        @JsonProperty("level") val level: Int?,
    )

    companion object {
        val logger = KotlinLogging.logger {}

        // Explicit YAML economy overrides (see load()). Populated once at boot on the
        // (sequential) override walk, read-only afterwards.
        private val lowAlchOverrides = HashMap<Int, Int>()
        private val highAlchOverrides = HashMap<Int, Int>()
        private val buyLimits = HashMap<Int, Int>()
        private val geExcluded = HashSet<Int>()

        fun lowAlchOverride(id: Int): Int? = lowAlchOverrides[id]

        fun highAlchOverride(id: Int): Int? = highAlchOverrides[id]

        fun buyLimit(id: Int): Int? = buyLimits[id]

        fun isGeExcluded(id: Int): Boolean = id in geExcluded
    }
}
