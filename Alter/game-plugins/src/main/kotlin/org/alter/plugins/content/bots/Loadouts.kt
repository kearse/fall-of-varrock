package org.alter.plugins.content.bots

import org.alter.api.EquipmentType

/**
 * Bot PKer gear + stat data, fully data-driven.
 *
 * A [BotLoadout] is everything needed to dress and stat a fake-player NHer. Because a bot is a
 * real [org.alter.game.model.entity.Player] wearing real items, these RSCM item names resolve to
 * real cache items and feed the SAME item-driven combat/special/formula code that human players
 * use — so "gear swaps" are literal, not faked.
 *
 * Design intent (see also the bots task list): start with the **Modern NH meta** and a
 * **Classic hybrid**; the model is built to accept more archetypes later (low-budget pures,
 * Dharok DHers, melee-only Maxers) without code changes — just add [BotLoadout]s to [BotLoadouts].
 */

/** The three combat schools a bot can fight in. The NH brain swaps between these. */
enum class BotStyle { MELEE, RANGED, MAGIC }

/** Base (real) skill levels the bot fights at. */
data class BotStats(
    val attack: Int,
    val strength: Int,
    val defence: Int,
    val hitpoints: Int,
    val ranged: Int,
    val magic: Int,
    val prayer: Int,
)

/** A worn-equipment set: slot -> RSCM item name (e.g. "item.voidwaker"). */
typealias GearBlock = Map<EquipmentType, String>

/** One consumable/spec-weapon line the bot carries in its inventory. */
data class BotItem(val name: String, val amount: Int = 1)

data class BotLoadout(
    /** Stable key used by commands/spawners (e.g. "elite_nh"). */
    val key: String,
    /** Human-facing name (used for the bot's display name pool prefix / admin output). */
    val displayName: String,
    /** Difficulty band: "elite", "mid", "budget", ... drives spawn-depth gating later. */
    val tier: String,
    /** Displayed combat level on right-click. */
    val combatLevel: Int,
    val stats: BotStats,
    /** The style the bot spawns wearing. */
    val baseStyle: BotStyle,
    /** Full worn set per style — swapping = replacing the whole equipment block. */
    val gear: Map<BotStyle, GearBlock>,
    /** Spec weapon the bot favours per style (null = use the style's main weapon's spec). */
    val specWeapon: Map<BotStyle, String> = emptyMap(),
    /** Autocast/attack spell per style (mage NH freeze/barrage), RSCM/spell key resolved later. */
    val spell: Map<BotStyle, String> = emptyMap(),
    /**
     * Ordered melee spec-weapon rotation the brain cycles through on consecutive specs (e.g. AGS
     * then granite maul for the big combo). Empty = just spec the equipped melee weapon.
     */
    val meleeSpecRotation: List<String> = emptyList(),
    /** Food, brews, restores, and carried spec weapons. */
    val inventory: List<BotItem> = emptyList(),
    /** Whether the NH brain uses prayer (protect + offensive). Off for basic metal-tier bots. */
    val usesPrayer: Boolean = true,
    /**
     * HP fraction (0..1) below which the brain eats. Null = the default (~0.55). Dharok DHers set
     * this LOW (e.g. 0.18) so they stay in the high-damage missing-HP band and only emergency-eat.
     */
    val eatAt: Double? = null,
)

private fun gb(vararg pairs: Pair<EquipmentType, String>): GearBlock = mapOf(*pairs)

object BotLoadouts {

    /**
     * MODERN OSRS NH META — max-level spec hybrid. The thing players should fear: Torva + whip
     * (+ avernic) as the sustained melee main, then the AGS → granite-maul spec combo to FINISH a
     * low target; swaps to Masori + magic shortbow(i) and Ancestral + Kodai, brews + restores +
     * karambwans to sustain. (No Voidwaker — admin-only.)
     */
    val ELITE_NH = BotLoadout(
        key = "elite_nh",
        displayName = "Elite",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 99, magic = 99, prayer = 99),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(
            BotStyle.MELEE to gb(
                // Whip + avernic is the sustained main; the brain swaps in AGS then maul to FINISH.
                // Bandos + neitiznot faceguard (NOT Torva — Nex gear is reserved for killing Nex).
                EquipmentType.HEAD to "item.neitiznot_faceguard",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.abyssal_whip",
                EquipmentType.CHEST to "item.bandos_chestplate",
                EquipmentType.SHIELD to "item.avernic_defender",
                EquipmentType.LEGS to "item.bandos_tassets",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
            BotStyle.RANGED to gb(
                EquipmentType.HEAD to "item.masori_mask_f",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.necklace_of_anguish",
                EquipmentType.WEAPON to "item.magic_shortbow_i",
                EquipmentType.CHEST to "item.masori_body_f",
                EquipmentType.LEGS to "item.masori_chaps_f",
                EquipmentType.GLOVES to "item.barrows_gloves", // not Zaryte vambraces (Nex)
                EquipmentType.BOOTS to "item.pegasian_boots",
                EquipmentType.RING to "item.archers_ring_i",
                EquipmentType.AMMO to "item.dragon_arrow",
            ),
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.ancestral_hat",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.occult_necklace",
                EquipmentType.WEAPON to "item.kodai_wand",
                EquipmentType.CHEST to "item.ancestral_robe_top",
                EquipmentType.LEGS to "item.ancestral_robe_bottom",
                EquipmentType.GLOVES to "item.tormented_bracelet",
                EquipmentType.BOOTS to "item.eternal_boots",
                EquipmentType.RING to "item.seers_ring_i",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "ice_barrage"),
        meleeSpecRotation = listOf("item.armadyl_godsword", "item.granite_maul"),
        inventory = listOf(
            BotItem("item.armadyl_godsword"), // spec finisher + drops on death
            BotItem("item.granite_maul"),     // combo finisher + drops on death
            BotItem("item.saradomin_brew4", 8),
            BotItem("item.super_restore4", 6),
            BotItem("item.cooked_karambwan", 4),
            BotItem("item.ranging_potion4", 1),
            BotItem("item.super_combat_potion4", 1),
        ),
    )

    /**
     * CLASSIC / MID HYBRID — the "old-school" NHer. Mystic + ancient staff, Karil's + black d'hide
     * + magic shortbow, fighter torso + whip + dragon defender. Cheaper look, easier to balance;
     * a real threat without the elite ceiling.
     */
    val CLASSIC_HYBRID = BotLoadout(
        key = "classic_hybrid",
        displayName = "Rogue",
        tier = "mid",
        combatLevel = 112,
        stats = BotStats(attack = 90, strength = 90, defence = 80, hitpoints = 90, ranged = 90, magic = 94, prayer = 70),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.helm_of_neitiznot",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.abyssal_whip",
                EquipmentType.CHEST to "item.fighter_torso",
                EquipmentType.SHIELD to "item.dragon_defender",
                EquipmentType.LEGS to "item.rune_platelegs",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring",
            ),
            BotStyle.RANGED to gb(
                EquipmentType.HEAD to "item.karils_coif",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.magic_shortbow",
                EquipmentType.CHEST to "item.black_dhide_body",
                EquipmentType.LEGS to "item.black_dhide_chaps",
                EquipmentType.GLOVES to "item.black_dhide_vambraces",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.archers_ring",
                EquipmentType.AMMO to "item.rune_arrow",
            ),
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.mystic_hat",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.ancient_staff",
                EquipmentType.CHEST to "item.mystic_robe_top",
                EquipmentType.LEGS to "item.mystic_robe_bottom",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.seers_ring",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "ice_blitz"),
        meleeSpecRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = listOf(
            BotItem("item.dragon_dagger"),
            BotItem("item.granite_maul"),
            BotItem("item.saradomin_brew4", 6),
            BotItem("item.super_restore4", 4),
            BotItem("item.cooked_karambwan", 3),
        ),
    )

    /**
     * MAGE NH (elite) — a dedicated freezer. Ancestral + Kodai autocasting Ice Barrage to perma-lock
     * a target, with a melee (dragon dagger) switch the brain swaps to ONLY if the target overheads
     * magic — then bursts them down. Magic main, so it spends most of the fight freezing + barraging.
     */
    val MAGE_ELITE = BotLoadout(
        key = "mage_elite",
        displayName = "Sorcerer",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 1, magic = 99, prayer = 99),
        baseStyle = BotStyle.MAGIC,
        gear = mapOf(
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.ancestral_hat",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.occult_necklace",
                EquipmentType.WEAPON to "item.kodai_wand",
                EquipmentType.SHIELD to "item.elidinis_ward_f",
                EquipmentType.CHEST to "item.ancestral_robe_top",
                EquipmentType.LEGS to "item.ancestral_robe_bottom",
                EquipmentType.GLOVES to "item.tormented_bracelet",
                EquipmentType.BOOTS to "item.eternal_boots",
                EquipmentType.RING to "item.seers_ring_i",
            ),
            // Melee switch — only swapped in to burst a frozen target that's overheading magic.
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.helm_of_neitiznot",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.dragon_dagger",
                EquipmentType.CHEST to "item.fighter_torso",
                EquipmentType.SHIELD to "item.avernic_defender",
                EquipmentType.LEGS to "item.torva_platelegs",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "ice_barrage"),
        meleeSpecRotation = listOf("item.dragon_dagger"),
        inventory = listOf(
            BotItem("item.dragon_dagger"),
            BotItem("item.saradomin_brew4", 8),
            BotItem("item.super_restore4", 6),
            BotItem("item.cooked_karambwan", 4),
        ),
    )

    /**
     * MAGE NH (mid) — the budget freezer. Mystic + ancient staff autocasting Ice Blitz; a dragon
     * dagger melee switch for the finish. Cheaper kit, lower stats, but the freeze still bites.
     */
    val MAGE_MID = BotLoadout(
        key = "mage_mid",
        displayName = "Warlock",
        tier = "mid",
        combatLevel = 100,
        stats = BotStats(attack = 75, strength = 75, defence = 70, hitpoints = 85, ranged = 1, magic = 94, prayer = 70),
        baseStyle = BotStyle.MAGIC,
        gear = mapOf(
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.ahrims_hood",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.occult_necklace",
                EquipmentType.WEAPON to "item.ancient_staff",
                EquipmentType.CHEST to "item.ahrims_robetop",
                EquipmentType.LEGS to "item.ahrims_robeskirt",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.infinity_boots",
                EquipmentType.RING to "item.seers_ring",
            ),
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.helm_of_neitiznot",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.dragon_dagger",
                EquipmentType.CHEST to "item.fighter_torso",
                EquipmentType.SHIELD to "item.dragon_defender",
                EquipmentType.LEGS to "item.rune_platelegs",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "ice_blitz"),
        meleeSpecRotation = listOf("item.dragon_dagger"),
        inventory = listOf(
            BotItem("item.dragon_dagger"),
            BotItem("item.saradomin_brew4", 6),
            BotItem("item.super_restore4", 4),
            BotItem("item.cooked_karambwan", 3),
        ),
    )

    /**
     * RANGER NH (elite) — Masori + magic shortbow(i) with dragon arrows, opening with the msb(i)
     * special, plus an Armadyl godsword melee switch the brain swaps to if the target overheads
     * missiles. Ranged main, so it kites + bursts with the bow spec.
     */
    val RANGE_ELITE = BotLoadout(
        key = "range_elite",
        displayName = "Marksman",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 99, magic = 1, prayer = 99),
        baseStyle = BotStyle.RANGED,
        gear = mapOf(
            BotStyle.RANGED to gb(
                EquipmentType.HEAD to "item.masori_mask_f",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.necklace_of_anguish",
                EquipmentType.WEAPON to "item.magic_shortbow_i",
                EquipmentType.CHEST to "item.masori_body_f",
                EquipmentType.LEGS to "item.masori_chaps_f",
                EquipmentType.GLOVES to "item.barrows_gloves", // not Zaryte vambraces (Nex)
                EquipmentType.BOOTS to "item.pegasian_boots",
                EquipmentType.RING to "item.archers_ring_i",
                EquipmentType.AMMO to "item.dragon_arrow",
            ),
            // Melee switch — swapped in to finish a target that's overheading missiles.
            // Bandos + neitiznot faceguard (NOT Torva — reserved for Nex).
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.neitiznot_faceguard",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.armadyl_godsword",
                EquipmentType.CHEST to "item.bandos_chestplate",
                EquipmentType.LEGS to "item.bandos_tassets",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
        ),
        meleeSpecRotation = listOf("item.armadyl_godsword"),
        inventory = listOf(
            BotItem("item.armadyl_godsword"), // finisher + drops on death
            BotItem("item.saradomin_brew4", 8),
            BotItem("item.super_restore4", 6),
            BotItem("item.cooked_karambwan", 4),
            BotItem("item.ranging_potion4", 1),
        ),
    )

    /**
     * RANGER NH (mid) — Karil's + black d'hide + magic shortbow (rune arrows), with a dragon dagger
     * melee switch. The budget archer: real msb DPS without the elite ceiling.
     */
    val RANGE_MID = BotLoadout(
        key = "range_mid",
        displayName = "Archer",
        tier = "mid",
        combatLevel = 100,
        stats = BotStats(attack = 80, strength = 80, defence = 75, hitpoints = 90, ranged = 90, magic = 1, prayer = 70),
        baseStyle = BotStyle.RANGED,
        gear = mapOf(
            BotStyle.RANGED to gb(
                EquipmentType.HEAD to "item.karils_coif",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.magic_shortbow",
                EquipmentType.CHEST to "item.black_dhide_body",
                EquipmentType.LEGS to "item.black_dhide_chaps",
                EquipmentType.GLOVES to "item.black_dhide_vambraces",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.archers_ring",
                EquipmentType.AMMO to "item.rune_arrow",
            ),
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.helm_of_neitiznot",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.dragon_dagger",
                EquipmentType.CHEST to "item.fighter_torso",
                EquipmentType.SHIELD to "item.dragon_defender",
                EquipmentType.LEGS to "item.rune_platelegs",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring",
            ),
        ),
        meleeSpecRotation = listOf("item.dragon_dagger"),
        inventory = listOf(
            BotItem("item.dragon_dagger"),
            BotItem("item.saradomin_brew4", 6),
            BotItem("item.super_restore4", 4),
            BotItem("item.cooked_karambwan", 3),
        ),
    )

    /**
     * DHAROK DHER (elite) — the brutal gimmick. Full Dharok's + greataxe; the set effect (in
     * [org.alter.plugins.content.combat.formula.MeleeCombatFormula]) scales max hit with MISSING hp,
     * so [eatAt] is set LOW (18%) — the bot deliberately fights near death where its hits are huge.
     * No spec (greataxe has none); pure prayer-melee terror.
     */
    val DHAROK_DHER = BotLoadout(
        key = "dharok_dher",
        displayName = "Reaper",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 1, magic = 1, prayer = 99),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.dharoks_helm",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.dharoks_greataxe",
                EquipmentType.CHEST to "item.dharoks_platebody",
                EquipmentType.LEGS to "item.dharoks_platelegs",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
        ),
        eatAt = 0.18, // stay in the low-HP, high-damage Dharok band — only emergency-eat
        inventory = listOf(
            BotItem("item.saradomin_brew4", 6),
            BotItem("item.super_restore4", 4),
            BotItem("item.cooked_karambwan", 2),
        ),
    )

    /**
     * DHAROK DHER (mid) — budget brute. Same gimmick on lower stats + cheaper trinkets. Eats at 25%.
     */
    val DHAROK_MID = BotLoadout(
        key = "dharok_mid",
        displayName = "Brute",
        tier = "mid",
        combatLevel = 110,
        stats = BotStats(attack = 80, strength = 80, defence = 75, hitpoints = 90, ranged = 1, magic = 1, prayer = 70),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.dharoks_helm",
                EquipmentType.CAPE to "item.fire_cape",
                EquipmentType.AMULET to "item.amulet_of_fury",
                EquipmentType.WEAPON to "item.dharoks_greataxe",
                EquipmentType.CHEST to "item.dharoks_platebody",
                EquipmentType.LEGS to "item.dharoks_platelegs",
                EquipmentType.GLOVES to "item.barrows_gloves",
                EquipmentType.BOOTS to "item.dragon_boots",
                EquipmentType.RING to "item.berserker_ring",
            ),
        ),
        eatAt = 0.25,
        inventory = listOf(
            BotItem("item.saradomin_brew4", 5),
            BotItem("item.super_restore4", 3),
            BotItem("item.cooked_karambwan", 2),
        ),
    )

    /**
     * ANCIENT (BLOOD) MAGE (elite) — the sustain caster. Ancient sceptre autocasting Blood Barrage,
     * healing 25% of its damage (see [org.alter.plugins.content.combat.strategy.MagicCombatStrategy]).
     * Drains you while it tanks; a dragon dagger melee switch finishes a magic-praying target.
     */
    val ANCIENT_MAGE = BotLoadout(
        key = "ancient_mage",
        displayName = "Defiler",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 1, magic = 99, prayer = 99),
        baseStyle = BotStyle.MAGIC,
        gear = mapOf(
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.ancestral_hat",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.occult_necklace",
                EquipmentType.WEAPON to "item.ancient_sceptre",
                EquipmentType.SHIELD to "item.elidinis_ward_f",
                EquipmentType.CHEST to "item.ancestral_robe_top",
                EquipmentType.LEGS to "item.ancestral_robe_bottom",
                EquipmentType.GLOVES to "item.tormented_bracelet",
                EquipmentType.BOOTS to "item.eternal_boots",
                EquipmentType.RING to "item.seers_ring_i",
            ),
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.neitiznot_faceguard",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.dragon_dagger",
                EquipmentType.CHEST to "item.bandos_chestplate",
                EquipmentType.SHIELD to "item.avernic_defender",
                EquipmentType.LEGS to "item.bandos_tassets",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "blood_barrage"),
        meleeSpecRotation = listOf("item.dragon_dagger"),
        inventory = listOf(
            BotItem("item.dragon_dagger"),
            BotItem("item.saradomin_brew4", 6),
            BotItem("item.super_restore4", 6),
            BotItem("item.cooked_karambwan", 4),
        ),
    )

    /**
     * CLAWS BRID (elite) — the spec-KO hybrid. Whip + avernic sustained main, but the finisher is a
     * Dragon Claws spec, and it carries a Kodai/Ice switch to freeze a melee-praying runner before
     * the claws land. A different threat shape from [ELITE_NH] (AGS/maul) and the freezers.
     */
    val CLAWS_BRID = BotLoadout(
        key = "claws_brid",
        displayName = "Slayer",
        tier = "elite",
        combatLevel = 126,
        stats = BotStats(attack = 99, strength = 99, defence = 99, hitpoints = 99, ranged = 1, magic = 99, prayer = 99),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(
            BotStyle.MELEE to gb(
                EquipmentType.HEAD to "item.neitiznot_faceguard",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.amulet_of_torture",
                EquipmentType.WEAPON to "item.abyssal_whip",
                EquipmentType.CHEST to "item.bandos_chestplate",
                EquipmentType.SHIELD to "item.avernic_defender",
                EquipmentType.LEGS to "item.bandos_tassets",
                EquipmentType.GLOVES to "item.ferocious_gloves",
                EquipmentType.BOOTS to "item.primordial_boots",
                EquipmentType.RING to "item.berserker_ring_i",
            ),
            BotStyle.MAGIC to gb(
                EquipmentType.HEAD to "item.ancestral_hat",
                EquipmentType.CAPE to "item.infernal_cape",
                EquipmentType.AMULET to "item.occult_necklace",
                EquipmentType.WEAPON to "item.kodai_wand",
                EquipmentType.SHIELD to "item.elidinis_ward_f",
                EquipmentType.CHEST to "item.ancestral_robe_top",
                EquipmentType.LEGS to "item.ancestral_robe_bottom",
                EquipmentType.GLOVES to "item.tormented_bracelet",
                EquipmentType.BOOTS to "item.eternal_boots",
                EquipmentType.RING to "item.seers_ring_i",
            ),
        ),
        spell = mapOf(BotStyle.MAGIC to "ice_blitz"),
        meleeSpecRotation = listOf("item.dragon_claws"),
        inventory = listOf(
            BotItem("item.dragon_claws"),
            BotItem("item.saradomin_brew4", 8),
            BotItem("item.super_restore4", 6),
            BotItem("item.cooked_karambwan", 4),
        ),
    )

    /**
     * Real **bracketed PKer** — melee main + ranged switch, full prayer + a dragon-dagger(/granite-
     * maul) spec finisher. NOT a "metal-armour knight": this is an authentic hybrid PK kit, so the NH
     * [BotBrain] prays protect + (level-appropriate) offence, switches off the opponent's overhead,
     * and specs to finish — it fights like a real player. Gear/stats are tuned per bracket; the gear
     * blocks reuse the verified meta tank/DPS sets (d'hide + fighter torso + defender, msb switch).
     */
    private fun pkKit(
        key: String,
        displayName: String,
        combatLevel: Int,
        melee: Int,
        hp: Int,
        ranged: Int,
        prayer: Int,
        meleeGear: GearBlock,
        rangeGear: GearBlock,
        specRotation: List<String>,
        inventory: List<BotItem>,
    ): BotLoadout = BotLoadout(
        key = key,
        displayName = displayName,
        tier = "pk",
        combatLevel = combatLevel,
        stats = BotStats(
            attack = melee, strength = melee, defence = melee,
            hitpoints = hp, ranged = ranged, magic = 1, prayer = prayer,
        ),
        baseStyle = BotStyle.MELEE,
        gear = mapOf(BotStyle.MELEE to meleeGear, BotStyle.RANGED to rangeGear),
        meleeSpecRotation = specRotation,
        inventory = inventory,
        usesPrayer = true,
    )

    // --- Gear grades (LOW → MID → HIGH). Authentic PK switches: d'hide tank + fighter torso melee,
    // berserker/strength jewellery + defender, magic shortbow + d'hide for the ranged switch. ---

    private fun lowMelee(weapon: String) = gb(
        EquipmentType.HEAD to "item.berserker_helm",
        EquipmentType.CAPE to "item.obsidian_cape",
        EquipmentType.AMULET to "item.amulet_of_strength",
        EquipmentType.WEAPON to weapon,
        EquipmentType.CHEST to "item.fighter_torso",
        EquipmentType.SHIELD to "item.rune_defender",
        EquipmentType.LEGS to "item.black_dhide_chaps",
        EquipmentType.GLOVES to "item.combat_bracelet",
        EquipmentType.BOOTS to "item.climbing_boots",
        EquipmentType.RING to "item.berserker_ring",
    )
    private fun lowRange(ammo: String) = gb(
        EquipmentType.HEAD to "item.coif",
        EquipmentType.CAPE to "item.obsidian_cape",
        EquipmentType.AMULET to "item.amulet_of_power",
        EquipmentType.WEAPON to "item.magic_shortbow",
        EquipmentType.CHEST to "item.black_dhide_body",
        EquipmentType.LEGS to "item.black_dhide_chaps",
        EquipmentType.GLOVES to "item.black_dhide_vambraces",
        EquipmentType.BOOTS to "item.snakeskin_boots",
        EquipmentType.RING to "item.archers_ring",
        EquipmentType.AMMO to ammo,
    )
    private fun midMelee(weapon: String, amulet: String, legs: String) = gb(
        EquipmentType.HEAD to "item.helm_of_neitiznot",
        EquipmentType.CAPE to "item.fire_cape",
        EquipmentType.AMULET to amulet,
        EquipmentType.WEAPON to weapon,
        EquipmentType.CHEST to "item.fighter_torso",
        EquipmentType.SHIELD to "item.dragon_defender",
        EquipmentType.LEGS to legs,
        EquipmentType.GLOVES to "item.barrows_gloves",
        EquipmentType.BOOTS to "item.dragon_boots",
        EquipmentType.RING to "item.berserker_ring",
    )
    private fun midRange(amulet: String) = gb(
        EquipmentType.HEAD to "item.karils_coif",
        EquipmentType.CAPE to "item.fire_cape",
        EquipmentType.AMULET to amulet,
        EquipmentType.WEAPON to "item.magic_shortbow",
        EquipmentType.CHEST to "item.black_dhide_body",
        EquipmentType.LEGS to "item.black_dhide_chaps",
        EquipmentType.GLOVES to "item.black_dhide_vambraces",
        EquipmentType.BOOTS to "item.dragon_boots",
        EquipmentType.RING to "item.archers_ring",
        EquipmentType.AMMO to "item.rune_arrow",
    )

    // Standard PK inventory: spec weapon + food (light kits), or the full NH pack (dds + gmaul +
    // brews/restores/karambwan) for the upper brackets that actually prayer-switch and combo.
    private fun lightPack(food: String, foodAmt: Int, restores: Int = 0): List<BotItem> =
        listOf(BotItem("item.dragon_dagger"), BotItem(food, foodAmt)) +
            (if (restores > 0) listOf(BotItem("item.super_restore4", restores)) else emptyList())
    private fun fullPack(brews: Int, restores: Int, karam: Int): List<BotItem> = listOf(
        BotItem("item.dragon_dagger"),
        BotItem("item.granite_maul"),
        BotItem("item.shark", 4),
        BotItem("item.saradomin_brew4", brews),
        BotItem("item.super_restore4", restores),
        BotItem("item.cooked_karambwan", karam),
        BotItem("item.super_combat_potion4", 1),
    )

    // Bronze → rune progression: LOW pures (rune scim + dds), MID hybrids (d scim/whip + dds + gmaul),
    // HIGH bruisers (whip + dds + gmaul, brews). Combat level + stats + kit scale with the bracket.
    val BRONZE = pkKit(
        "bronze_pker", "Bandit", combatLevel = 52, melee = 50, hp = 55, ranged = 50, prayer = 43,
        meleeGear = lowMelee("item.rune_scimitar"), rangeGear = lowRange("item.adamant_arrow"),
        specRotation = listOf("item.dragon_dagger"),
        inventory = lightPack("item.lobster", 5),
    )
    val IRON = pkKit(
        "iron_pker", "Brigand", combatLevel = 63, melee = 55, hp = 60, ranged = 55, prayer = 44,
        meleeGear = lowMelee("item.rune_scimitar"), rangeGear = lowRange("item.rune_arrow"),
        specRotation = listOf("item.dragon_dagger"),
        inventory = lightPack("item.swordfish", 5, restores = 1),
    )
    val STEEL = pkKit(
        "steel_pker", "Marauder", combatLevel = 76, melee = 65, hp = 70, ranged = 65, prayer = 52,
        meleeGear = midMelee("item.dragon_scimitar", "item.amulet_of_glory", "item.black_dhide_chaps"),
        rangeGear = midRange("item.amulet_of_glory"),
        specRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = fullPack(brews = 2, restores = 2, karam = 2),
    )
    val BLACK = pkKit(
        "black_pker", "Mercenary", combatLevel = 85, melee = 70, hp = 75, ranged = 70, prayer = 60,
        meleeGear = midMelee("item.dragon_scimitar", "item.amulet_of_glory", "item.black_dhide_chaps"),
        rangeGear = midRange("item.amulet_of_fury"),
        specRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = fullPack(brews = 3, restores = 2, karam = 2),
    )
    val MITHRIL = pkKit(
        "mithril_pker", "Raider", combatLevel = 95, melee = 80, hp = 85, ranged = 80, prayer = 70,
        meleeGear = midMelee("item.abyssal_whip", "item.amulet_of_fury", "item.black_dhide_chaps"),
        rangeGear = midRange("item.amulet_of_fury"),
        specRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = fullPack(brews = 4, restores = 3, karam = 3),
    )
    val ADAMANT = pkKit(
        "adamant_pker", "Reaver", combatLevel = 103, melee = 85, hp = 90, ranged = 85, prayer = 74,
        meleeGear = midMelee("item.abyssal_whip", "item.amulet_of_fury", "item.rune_platelegs"),
        rangeGear = midRange("item.amulet_of_fury"),
        specRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = fullPack(brews = 5, restores = 3, karam = 3),
    )
    val RUNE = pkKit(
        "rune_pker", "Warlord", combatLevel = 110, melee = 90, hp = 92, ranged = 90, prayer = 80,
        meleeGear = midMelee("item.abyssal_whip", "item.amulet_of_fury", "item.rune_platelegs"),
        rangeGear = midRange("item.amulet_of_fury"),
        specRotation = listOf("item.dragon_dagger", "item.granite_maul"),
        inventory = fullPack(brews = 6, restores = 4, karam = 4),
    )

    private val byKey: Map<String, BotLoadout> =
        listOf(
            BRONZE, IRON, STEEL, BLACK, MITHRIL, ADAMANT, RUNE,
            CLASSIC_HYBRID, ELITE_NH,
            MAGE_MID, MAGE_ELITE, RANGE_MID, RANGE_ELITE,
            DHAROK_MID, DHAROK_DHER, ANCIENT_MAGE, CLAWS_BRID,
        ).associateBy { it.key }

    fun get(key: String): BotLoadout? = byKey[key.lowercase()]

    fun all(): Collection<BotLoadout> = byKey.values

    fun keys(): Set<String> = byKey.keys
}
