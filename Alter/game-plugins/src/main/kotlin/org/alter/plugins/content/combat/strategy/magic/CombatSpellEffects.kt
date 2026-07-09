package org.alter.plugins.content.combat.strategy.magic

import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.magic.SpellMetadata

/**
 * Registry for **non-damaging combat spells** cast on a target (Tele Block, Curse, Vulnerability,
 * Stat Spy, …). These share the spellbook's combat-cast path but aren't in the [CombatSpell] damage
 * table, so [CombatSpellsPlugin.castCombatSpellOnPawn] dispatches here when no [CombatSpell] matches
 * — instead of silently doing nothing. A dedicated plugin registers a handler by exact spell name;
 * the handler owns its own level/rune checks and xp (via MagicSpells.canCast / removeRunes).
 *
 * Mirrors [org.alter.plugins.content.combat.WeaponEffects] for spell effects.
 */
object CombatSpellEffects {
    private val effects = mutableMapOf<String, (caster: Player, target: Pawn, spell: SpellMetadata) -> Unit>()

    fun register(spellName: String, handler: (caster: Player, target: Pawn, spell: SpellMetadata) -> Unit) {
        effects[spellName.lowercase()] = handler
    }

    /** Returns true if a registered effect handled this spell. */
    fun tryExecute(caster: Player, target: Pawn, spell: SpellMetadata): Boolean {
        val handler = effects[spell.name.lowercase()] ?: return false
        handler(caster, target, spell)
        return true
    }
}
