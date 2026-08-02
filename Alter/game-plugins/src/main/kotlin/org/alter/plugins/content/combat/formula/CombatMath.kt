package org.alter.plugins.content.combat.formula

/**
 * The pure OSRS combat math, shared by all three combat formulas and unit-testable
 * without a live world (see CombatMathTests). Formulas per the OSRS Wiki
 * (Damage per second/Melee):
 *
 *   effectiveLevel = floor( floor(visible × prayer) + styleBonus + 8 )   [× void, floored]
 *   maxRoll        = effectiveLevel × (equipmentBonus + 64)
 *   maxHit         = floor( 0.5 + effectiveStrength × (strengthBonus + 64) / 640 )
 *   P(hit)         = attack > defence ? 1 − (defence + 2) / (2 × (attack + 1))
 *                                     : attack / (2 × (defence + 1))
 */
object CombatMath {
    fun hitChance(attackRoll: Int, defenceRoll: Int): Double =
        if (attackRoll > defenceRoll) {
            1.0 - (defenceRoll + 2.0) / (2.0 * (attackRoll + 1.0))
        } else {
            attackRoll / (2.0 * (defenceRoll + 1))
        }

    fun effectiveLevel(
        visibleLevel: Int,
        prayerMultiplier: Double = 1.0,
        styleBonus: Int = 0,
        voidMultiplier: Double = 1.0,
    ): Double {
        var level = Math.floor(visibleLevel * prayerMultiplier) + styleBonus + 8.0
        if (voidMultiplier != 1.0) {
            level = Math.floor(level * voidMultiplier)
        }
        return Math.floor(level)
    }

    fun maxRoll(effectiveLevel: Double, equipmentBonus: Double): Int = (effectiveLevel * (equipmentBonus + 64.0)).toInt()

    fun baseMaxHit(effectiveStrength: Double, strengthBonus: Double): Int =
        Math.floor(0.5 + effectiveStrength * (strengthBonus + 64.0) / 640.0).toInt()

    /**
     * Dharok the Wretched's set effect: 1 + (lost HP / 100) × (max HP / 100)
     * (OSRS Wiki, Dharok the Wretched's equipment). At 1/99 HP that's ×1.9702 —
     * the famous 90+ hits. Overhealed HP (current above max) never penalises:
     * lost HP is floored at zero.
     */
    fun dharokMultiplier(maxHp: Int, currentHp: Int): Double {
        val lost = (maxHp - currentHp).coerceAtLeast(0) / 100.0
        return 1.0 + (lost * (maxHp / 100.0))
    }

    /**
     * Twisted bow damage modifier as a MULTIPLIER (the wiki formula yields a
     * percentage — dividing by 100 here is what stops the bow one-shotting the
     * game). Capped at [capPercent] (250 outside the Chambers of Xeric, 350
     * inside) and floored at 0 for very low target magic.
     */
    fun twistedBowDamageModifier(targetMagic: Int, capPercent: Double = 250.0): Double {
        val magic = targetMagic.toDouble()
        val percent = 250.0 + ((magic * 3.0) - 14.0) / 100.0 - Math.pow(((magic * 3.0) / 10.0) - 140.0, 2.0) / 100.0
        return percent.coerceIn(0.0, capPercent) / 100.0
    }

    /**
     * Twisted bow accuracy modifier as a multiplier — same shape, capped at
     * [capPercent] (140 outside CoX, 250 inside), floored at 0.
     */
    fun twistedBowAccuracyModifier(targetMagic: Int, capPercent: Double = 140.0): Double {
        val magic = targetMagic.toDouble()
        val percent = 140.0 + ((magic * 3.0) - 10.0) / 100.0 - Math.pow(((magic * 3.0) / 10.0) - 100.0, 2.0) / 100.0
        return percent.coerceIn(0.0, capPercent) / 100.0
    }
}
