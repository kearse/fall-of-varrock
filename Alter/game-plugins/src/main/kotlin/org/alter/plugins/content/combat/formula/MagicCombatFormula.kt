package org.alter.plugins.content.combat.formula

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.combat.strategy.magic.PoweredStaves
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.plugins.content.skills.slayer.SlayerCombat

/**
 * @author Tom <rspsmods@gmail.com>
 */
object MagicCombatFormula : CombatFormula {
    private val BLACK_MASKS =
        arrayOf(
            "item.black_mask",
            "item.black_mask_1",
            "item.black_mask_2",
            "item.black_mask_3",
            "item.black_mask_4",
            "item.black_mask_5",
            "item.black_mask_6",
            "item.black_mask_7",
            "item.black_mask_8",
            "item.black_mask_9",
            "item.black_mask_10",
        )

    private val BLACK_MASKS_I =
        arrayOf(
            "item.black_mask_i",
            "item.black_mask_1_i",
            "item.black_mask_2_i",
            "item.black_mask_3_i",
            "item.black_mask_4_i",
            "item.black_mask_5_i",
            "item.black_mask_6_i",
            "item.black_mask_7_i",
            "item.black_mask_8_i",
            "item.black_mask_9_i",
            "item.black_mask_10_i",
        )

    private val SLAYER_HELM_I =
        arrayOf(
            "item.slayer_helmet_i",
            "item.black_slayer_helmet_i",
            "item.green_slayer_helmet_i",
            "item.purple_slayer_helmet_i",
            "item.red_slayer_helmet_i",
            "item.turquoise_slayer_helmet_i",
        )

    private val MAGE_VOID = arrayOf("item.void_mage_helm", "item.void_knight_top", "item.void_knight_robe", "item.void_knight_gloves")

    private val MAGE_ELITE_VOID = arrayOf("item.void_mage_helm", "item.elite_void_top", "item.elite_void_robe", "item.void_knight_gloves")

    private val BOLT_SPELLS = enumSetOf(CombatSpell.WIND_BOLT, CombatSpell.WATER_BOLT, CombatSpell.EARTH_BOLT, CombatSpell.FIRE_BOLT)

    private val FIRE_SPELLS =
        enumSetOf(CombatSpell.FIRE_STRIKE, CombatSpell.FIRE_BOLT, CombatSpell.FIRE_BLAST, CombatSpell.FIRE_WAVE, CombatSpell.FIRE_SURGE)

    override fun getAccuracy(
        pawn: Pawn,
        target: Pawn,
        specialAttackMultiplier: Double,
    ): Double {
        val attack = getAttackRoll(pawn, target)
        val defence =
            if (target is Player) {
                getDefenceRoll(target)
            } else if (target is Npc) {
                getDefenceRoll(target)
            } else {
                throw IllegalArgumentException("Unhandled pawn.")
            }
        return CombatMath.hitChance(attack, defence)
    }

    override fun getMaxHit(
        pawn: Pawn,
        target: Pawn,
        specialAttackMultiplier: Double,
        specialPassiveMultiplier: Double,
    ): Int {
        val spell = pawn.attr[Combat.CASTING_SPELL]
        var hit = spell?.maxHit?.toDouble() ?: 1.0
        if (pawn is Player) {
            val magic = pawn.getSkills().getCurrentLevel(Skills.MAGIC)
            if (pawn.hasEquipped(
                    EquipmentType.WEAPON,
                    "item.trident_of_the_seas",
                    "item.trident_of_the_seas_e",
                    "item.trident_of_the_seas_full",
                )
            ) {
                hit = (Math.floor(magic / 3.0) - 5.0)
            } else if (pawn.hasEquipped(EquipmentType.WEAPON, "item.trident_of_the_swamp", "item.trident_of_the_swamp_e")) {
                hit = (Math.floor(magic / 3.0) - 2.0)
            } else if (pawn.hasEquipped(EquipmentType.WEAPON, "item.sanguinesti_staff", "item.holy_sanguinesti_staff")) {
                hit = (Math.floor(magic / 3.0) - 1.0)
            } else if (PoweredStaves.isWieldingShadow(pawn)) {
                hit = Math.floor(magic / 3.0 + 1.0)
            }

            if (pawn.hasEquipped(EquipmentType.GLOVES, "item.chaos_gauntlets") && spell != null && spell in BOLT_SPELLS) {
                hit += 3
            }

            // Tumeken's shadow triples the gear magic-damage bonus, capped at +100% total
            // (OSRS Wiki, Tumeken's shadow). Applies to whatever it casts, built-in or not.
            var damageBonus = pawn.getMagicDamageBonus().toDouble()
            if (PoweredStaves.isWieldingShadow(pawn)) {
                damageBonus = Math.min(100.0, damageBonus * SHADOW_BONUS_MULTIPLIER)
            }
            var multiplier = 1.0 + (damageBonus / 100.0)

            if (pawn.hasEquipped(
                    EquipmentType.AMULET,
                    "item.amulet_of_the_damned_full",
                ) &&
                pawn.hasEquipped(
                    EquipmentType.WEAPON,
                    "item.ahrims_staff",
                    "item.ahrims_staff_25",
                    "item.ahrims_staff_50",
                    "item.ahrims_staff_75",
                    "item.ahrims_staff_100",
                ) &&
                pawn.world.chance(1, 4)
            ) {
                multiplier += 0.3
            }

            if (pawn.hasEquipped(EquipmentType.WEAPON, "item.mystic_smoke_staff") && pawn.hasSpellbook(Spellbook.NORMAL)) {
                multiplier += 0.1
            }

            if (pawn.hasEquipped(MAGE_ELITE_VOID)) {
                multiplier += 0.025
            }

            hit *= multiplier
            hit = Math.floor(hit)

            if (pawn.hasEquipped(EquipmentType.SHIELD, "item.tome_of_fire") && spell in FIRE_SPELLS) {
                // TODO: check tome of fire has charges
                // ×1.1 since the 2023 tome rework (dps-calc MAX_HIT_TOME factor [11, 10]);
                // the old ×1.5 was the pre-rework value.
                hit *= 1.1
                hit = Math.floor(hit)
            }

            if (target is Npc) {
                // Salve (undead) takes precedence over the on-task mask/helm; the two never stack.
                if (pawn.hasEquipped(EquipmentType.AMULET, "item.salve_amuletei") && target.isSpecies(NpcSpecies.UNDEAD)) {
                    hit *= 1.20
                    hit = Math.floor(hit)
                } else if ((pawn.hasEquipped(EquipmentType.HEAD, *BLACK_MASKS_I) || pawn.hasEquipped(EquipmentType.HEAD, *SLAYER_HELM_I)) &&
                    SlayerCombat.isOnTaskAgainst(pawn, target)
                ) {
                    hit *= 1.15
                    hit = Math.floor(hit)
                }
            }
        } else if (pawn is Npc) {
            val multiplier = 1.0 + (pawn.getMagicDamageBonus() / 100.0)
            hit *= multiplier
            hit = Math.floor(hit)
        }

        hit *= getDamageDealMultiplier(pawn)
        hit = Math.floor(hit)

        // Parity with the melee/ranged formulas: the TARGET-side multiplier (boss immunity
        // phases, scripted damage caps) applies to magic damage too.
        hit *= getDamageTakeMultiplier(target)
        hit = Math.floor(hit)

        // Overhead protection is NOT part of the max hit: it's applied to the rolled
        // damage at hit-application time (see dealHit), so mid-flight prayer switches work.
        return hit.toInt()
    }

    private fun getAttackRoll(
        pawn: Pawn,
        target: Pawn,
    ): Int {
        val a =
            if (pawn is Player) {
                getEffectiveAttackLevel(pawn)
            } else if (pawn is Npc) {
                getEffectiveAttackLevel(pawn)
            } else {
                0.0
            }
        var b = getEquipmentAttackBonus(pawn)
        // Tumeken's shadow triples the gear magic ATTACK bonus too (OSRS Wiki).
        if (pawn is Player && PoweredStaves.isWieldingShadow(pawn)) {
            b *= SHADOW_BONUS_MULTIPLIER
        }

        var maxRoll = a * (b + 64.0)
        if (pawn is Player) {
            maxRoll = applyAttackSpecials(pawn, target, maxRoll)
        }
        return maxRoll.toInt()
    }

    /** Tumeken's shadow: ×3 outside the Tombs of Amascut (×4 inside; no raid here). */
    private const val SHADOW_BONUS_MULTIPLIER = 3.0

    private fun getDefenceRoll(target: Npc): Int {
        // OSRS: an NPC's magic evasion rolls off its MAGIC level (not Defence), +9 for the
        // implicit style base, against its magic defence bonus.
        val a = target.stats.getCurrentLevel(NpcSkills.MAGIC) + 9.0
        val b = getEquipmentDefenceBonus(target)

        val maxRoll = a * (b + 64.0)
        return maxRoll.toInt()
    }

    private fun getDefenceRoll(target: Player): Int {
        // 70% prayer-adjusted Magic + 30% prayer-adjusted Defence, mixed BEFORE the stance
        // bonus and the base +8 (dps-calc NPCVsPlayerCalc.getPlayerDefenceRoll; the mystic/
        // augury prayers boost magic defence by the same factor as magic accuracy).
        val effMagic = Math.floor(target.getSkills().getCurrentLevel(Skills.MAGIC) * getPrayerAttackMultiplier(target))
        val effDefence = Math.floor(target.getSkills().getCurrentLevel(Skills.DEFENCE) * getPrayerDefenceMultiplier(target))
        val stanceBonus =
            when (CombatConfigs.getAttackStyle(target)) {
                AttackStyle.DEFENSIVE -> 3
                AttackStyle.CONTROLLED -> 1
                AttackStyle.LONG_RANGE -> 3
                else -> 0
            }

        val a = CombatMath.magicDefenceEffectiveLevel(effMagic, effDefence, stanceBonus)
        val b = getEquipmentDefenceBonus(target)

        val maxRoll = a * (b + 64.0)
        return maxRoll.toInt()
    }

    private fun applyAttackSpecials(
        player: Player,
        target: Pawn,
        base: Double,
    ): Double {
        var hit = base

        hit *= getEquipmentMultiplier(player, target)
        hit = Math.floor(hit)

        // The smoke battlestaff's 10% accuracy bonus only applies on the STANDARD
        // spellbook, same as its damage bonus (OSRS Wiki, Smoke battlestaff).
        if (player.hasEquipped(EquipmentType.WEAPON, "item.mystic_smoke_staff") && player.hasSpellbook(Spellbook.NORMAL)) {
            hit *= 1.1
            hit = Math.floor(hit)
        }

        return hit
    }

    private fun getEffectiveAttackLevel(player: Player): Double {
        var effectiveLevel = Math.floor(player.getSkills().getCurrentLevel(Skills.MAGIC) * getPrayerAttackMultiplier(player))

        // Style bonuses only exist for powered staves in OSRS: accurate +2, longrange +1.
        // Standard-spellbook casts take no style bonus (defensive casting affects XP only).
        if (player.hasWeaponType(WeaponType.TRIDENT)) {
            effectiveLevel +=
                when (CombatConfigs.getAttackStyle(player)) {
                    AttackStyle.ACCURATE -> 2.0
                    AttackStyle.LONG_RANGE -> 1.0
                    else -> 0.0
                }
        }

        // Magic ACCURACY uses a +9 base, not the melee/ranged +8
        // (dps-calc PlayerVsNPCCalc.getPlayerMaxMagicAttackRoll: `effectiveLevel += 9`).
        effectiveLevel += 9.0

        if (player.hasEquipped(MAGE_VOID) || player.hasEquipped(MAGE_ELITE_VOID)) {
            effectiveLevel *= 1.45
            effectiveLevel = Math.floor(effectiveLevel)
        }

        return Math.floor(effectiveLevel)
    }

    // NPC effective magic level is level + 9: the standard +8 plus the implicit +1 style base.
    private fun getEffectiveAttackLevel(npc: Npc): Double = npc.stats.getCurrentLevel(NpcSkills.MAGIC) + 9.0

    private fun getEquipmentAttackBonus(pawn: Pawn): Double {
        return pawn.getBonus(BonusSlot.ATTACK_MAGIC).toDouble()
    }

    private fun getEquipmentDefenceBonus(target: Pawn): Double {
        return target.getBonus(BonusSlot.DEFENCE_MAGIC).toDouble()
    }

    /**
     * Only the imbued salve variants and imbued black mask work with magic in OSRS
     * (the regular salve/mask are melee-only). Salve requires an undead target, the
     * mask requires the current Slayer assignment, and salve takes precedence.
     */
    private fun getEquipmentMultiplier(player: Player, target: Pawn): Double {
        val undead = isUndead(target)
        return when {
            undead && player.hasEquipped(EquipmentType.AMULET, "item.salve_amuletei") -> 1.2
            undead && player.hasEquipped(EquipmentType.AMULET, "item.salve_amuleti") -> 1.15
            // Any IMBUED slayer head (black mask (i) or slayer helmet (i) of any colour).
            SlayerCombat.wearingImbuedSlayerHead(player) && SlayerCombat.isOnTaskAgainst(player, target) -> 1.15
            else -> 1.0
        }
    }

    private fun isUndead(pawn: Pawn): Boolean {
        if (pawn.entityType.isNpc) {
            return (pawn as Npc).isSpecies(NpcSpecies.UNDEAD)
        }
        return false
    }

    private fun getPrayerAttackMultiplier(player: Player): Double =
        when {
            Prayers.isActive(player, Prayer.MYSTIC_WILL) -> 1.05
            Prayers.isActive(player, Prayer.MYSTIC_LORE) -> 1.10
            Prayers.isActive(player, Prayer.MYSTIC_MIGHT) -> 1.15
            Prayers.isActive(player, Prayer.AUGURY) -> 1.25
            else -> 1.0
        }

    private fun getPrayerDefenceMultiplier(player: Player): Double =
        when {
            Prayers.isActive(player, Prayer.THICK_SKIN) -> 1.05
            Prayers.isActive(player, Prayer.ROCK_SKIN) -> 1.10
            Prayers.isActive(player, Prayer.STEEL_SKIN) -> 1.15
            Prayers.isActive(player, Prayer.CHIVALRY) -> 1.20
            Prayers.isActive(player, Prayer.PIETY) -> 1.25
            Prayers.isActive(player, Prayer.RIGOUR) -> 1.25
            Prayers.isActive(player, Prayer.AUGURY) -> 1.25
            else -> 1.0
        }

    private fun getDamageDealMultiplier(pawn: Pawn): Double = pawn.attr[Combat.DAMAGE_DEAL_MULTIPLIER] ?: 1.0

    private fun getDamageTakeMultiplier(pawn: Pawn): Double = pawn.attr[Combat.DAMAGE_TAKE_MULTIPLIER] ?: 1.0
}
