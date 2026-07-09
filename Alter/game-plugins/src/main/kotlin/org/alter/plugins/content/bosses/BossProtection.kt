package org.alter.plugins.content.bosses

import org.alter.api.PrayerIcon
import org.alter.api.ext.hasPrayerIcon
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Pawn

/**
 * OSRS-exact protection-prayer handling for **NPC → player** attacks.
 *
 * The stock combat formulas only apply the protection multiplier when the *attacker* is a
 * player (PvP, a 40% reduction — see `MeleeCombatFormula.applyStrengthSpecials`, guarded by
 * `pawn is Player`). NPC attacks therefore ignore the victim's overhead entirely, which is
 * wrong for PvM: in OSRS a matching protection prayer **fully blocks** an NPC's damage.
 *
 * Boss combat plugins (the KBD-pattern `onNpcCombat` loops) call [isProtectedFrom] before
 * rolling damage and deal a `0`/`BLOCK` hit when it returns true. Attacks that are designed
 * to bypass prayer — Verac's Defiled hit, Corp's stomp, Vet'ion's lightning, etc. — simply
 * skip the check, matching their OSRS behaviour.
 */
fun Pawn.isProtectedFrom(combatClass: CombatClass): Boolean = when (combatClass) {
    CombatClass.MELEE -> hasPrayerIcon(PrayerIcon.PROTECT_FROM_MELEE)
    CombatClass.RANGED -> hasPrayerIcon(PrayerIcon.PROTECT_FROM_MISSILES)
    CombatClass.MAGIC -> hasPrayerIcon(PrayerIcon.PROTECT_FROM_MAGIC)
}
