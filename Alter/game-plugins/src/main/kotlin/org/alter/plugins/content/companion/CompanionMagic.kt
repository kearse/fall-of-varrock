package org.alter.plugins.content.companion

import org.alter.api.Skills
import org.alter.api.ext.setVarp
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.magic.MagicSpells

/**
 * A MAGE companion autocasts a standard-spellbook attack spell. By default it **auto-scales to its
 * Magic level** (a level-1 mage can't cast Ice Barrage), but the owner can pin a specific spell from
 * the management panel ([Companion.autocastSpell]) — that choice is honoured as long as the
 * companion's level allows it, otherwise it falls back to the auto-scaled best. Companions cast for
 * free (the infinite-runes varbit is set at spawn — see [CompanionRegistry]); the Magic-level
 * requirement is still enforced by the casting system.
 */
object CompanionMagic {
    /** Keep [comp]'s autocast spell in step with its level / the owner's override (called each tick for mages). */
    fun ensureSpell(comp: Companion) {
        if (comp.archetype != CompanionStyle.MAGE) return
        val spell = effectiveSpell(comp)
        if (comp.attr[Combat.CASTING_SPELL] != spell) {
            comp.attr[Combat.CASTING_SPELL] = spell
            comp.setVarp(AttackTab.ATTACK_STYLE_VARP, 0)
        }
    }

    /** The spell a mage companion will actually cast right now: the owner's override (if level-legal) else auto-scaled. */
    fun effectiveSpell(comp: Companion): CombatSpell {
        val lvl = comp.getSkills().getBaseLevel(Skills.MAGIC)
        val chosen = comp.autocastSpell
        return if (chosen != null && canUse(chosen, lvl)) chosen else autoScale(lvl)
    }

    /** The default level-scaled wind spell (the original behaviour). */
    private fun autoScale(lvl: Int): CombatSpell = when {
        lvl >= 81 -> CombatSpell.WIND_SURGE
        lvl >= 62 -> CombatSpell.WIND_WAVE
        lvl >= 41 -> CombatSpell.WIND_BLAST
        lvl >= 17 -> CombatSpell.WIND_BOLT
        else -> CombatSpell.WIND_STRIKE
    }

    /** True if a Magic level of [lvl] meets the spell's level requirement (engine-sourced, so always correct). */
    fun canUse(spell: CombatSpell, lvl: Int): Boolean {
        val req = MagicSpells.getMetadata(spell.id)?.lvl ?: return false
        return lvl >= req
    }
}
