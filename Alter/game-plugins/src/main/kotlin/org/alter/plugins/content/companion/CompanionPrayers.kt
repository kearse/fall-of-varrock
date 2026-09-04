package org.alter.plugins.content.companion

import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.rscm.RSCM.getRSCM

/**
 * Companion **Prayer**: the training path and the combat payoff.
 *
 * Training — [feedBones]: a clientless companion can never click "Bury", so the owner hands
 * over the bones (`::companion bones [slot]`): every bone in the OWNER's inventory is buried
 * by the companion for the standard bone xp (through `addXp`, so the server xp rate applies —
 * the same rate the owner's own burying gets).
 *
 * Combat — [update], called from [CompanionBrain.tick]: while fighting, a companion runs the
 * best offence prayer its live Prayer level unlocks (the same level ladder the PK bots use),
 * and protects against a PLAYER opponent's combat class (PvP defense — never vs NPCs, matching
 * the PK-bot rule). The ~1.2s brain cadence is the "human" reaction lag. Out of combat all
 * prayers drop and the points recharge to base — companions have no altar access, so prayer
 * points can never be permanently stuck low.
 */
object CompanionPrayers {

    /** OSRS prayer xp per bone — the SHARED table (mechanics/prayer/Bones.kt), never a second copy. */
    private val boneIds: Map<Int, Double>
        get() = org.alter.plugins.content.mechanics.prayer.Bones.byId

    private const val BURY_ANIM = 827

    /** Bury every bone in [owner]'s inventory as [comp]'s prayer training. Returns bones fed. */
    fun feedBones(owner: Player, comp: Companion): Int {
        var fed = 0
        var xp = 0.0
        boneIds.forEach { (id, boneXp) ->
            while (owner.inventory.remove(item = id, amount = 1).completed == 1) {
                comp.addXp(Skills.PRAYER, boneXp)
                xp += boneXp
                fed++
            }
        }
        if (fed > 0) {
            owner.animate(BURY_ANIM)
            owner.message(
                "<col=4f9b4f>Sir ${comp.username} buries $fed bone${if (fed == 1) "" else "s"} " +
                    "(Prayer ${comp.getSkills().getBaseLevel(Skills.PRAYER)}).</col>",
            )
        }
        return fed
    }

    /** Per-brain-tick prayer upkeep (see class doc). */
    fun update(comp: Companion) {
        if (!comp.isAttacking()) {
            if (Prayer.values.any { Prayers.isActive(comp, it) }) Prayers.deactivateAll(comp)
            val base = comp.getSkills().getBaseLevel(Skills.PRAYER)
            if (comp.getSkills().getCurrentLevel(Skills.PRAYER) < base) {
                comp.getSkills().setCurrentLevel(Skills.PRAYER, base)
            }
            return
        }
        if (comp.getSkills().getCurrentLevel(Skills.PRAYER) <= 0) return
        val prayer = comp.getSkills().getBaseLevel(Skills.PRAYER)

        // Offence first, protection last — so the defensive overhead is the one that "wins".
        offence(comp.archetype, prayer)?.let { if (!Prayers.isActive(comp, it)) Prayers.activate(comp, it) }
        val foe = comp.getCombatTarget()
        if (foe is Player && foe !is Companion) {
            val protect = when (CombatConfigs.getCombatClass(foe)) {
                CombatClass.MELEE -> if (prayer >= 43) Prayer.PROTECT_FROM_MELEE else null
                CombatClass.RANGED -> if (prayer >= 40) Prayer.PROTECT_FROM_MISSILES else null
                CombatClass.MAGIC -> if (prayer >= 37) Prayer.PROTECT_FROM_MAGIC else null
                else -> null
            }
            if (protect != null && !Prayers.isActive(comp, protect)) Prayers.activate(comp, protect)
        }
    }

    /** The best offence prayer [prayer] unlocks for this school (PK-bot ladder + low-level gates). */
    private fun offence(archetype: CompanionStyle, prayer: Int): Prayer? = when (archetype) {
        CompanionStyle.MELEE -> when {
            prayer >= 70 -> Prayer.PIETY
            prayer >= 31 -> Prayer.ULTIMATE_STRENGTH
            prayer >= 13 -> Prayer.SUPERHUMAN_STRENGTH
            prayer >= 4 -> Prayer.BURST_OF_STRENGTH
            else -> null
        }
        CompanionStyle.RANGE -> when {
            prayer >= 74 -> Prayer.RIGOUR
            prayer >= 44 -> Prayer.EAGLE_EYE
            prayer >= 26 -> Prayer.HAWK_EYE
            prayer >= 8 -> Prayer.SHARP_EYE
            else -> null
        }
        CompanionStyle.MAGE -> when {
            prayer >= 77 -> Prayer.AUGURY
            prayer >= 45 -> Prayer.MYSTIC_MIGHT
            else -> null
        }
    }
}
