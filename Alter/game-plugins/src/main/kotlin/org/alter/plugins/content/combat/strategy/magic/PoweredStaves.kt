package org.alter.plugins.content.combat.strategy.magic

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Powered staves — weapons with a **built-in** magic attack (no spellbook, no runes, no
 * autocast varbit): the tridents and the Sanguinesti staff. The combat loop arms the staff's
 * [CombatSpell] automatically while it is wielded ([org.alter.plugins.content.combat.CombatPlugin]),
 * `postAttack` spares it from the one-shot manual-cast clear, and the attack delay uses the
 * weapon's own 4-tick speed instead of the 5-tick spell cast speed.
 *
 * Keyed by ITEM id, not weapon type — nothing in this cache resolves to
 * `WeaponType.TRIDENT` (its comment says "real powered-staff id TBD"), so every
 * weapon-type-driven trident branch is dead code; item ids are the reliable handle,
 * matching how [org.alter.plugins.content.combat.formula.MagicCombatFormula] already
 * computes trident max hits. Uncharged variants are intentionally absent (no built-in
 * attack without charges).
 */
object PoweredStaves {

    /** The built-in spells (for postAttack/delay membership checks). */
    val SPELLS = setOf(
        CombatSpell.TRIDENT_OF_THE_SEAS,
        CombatSpell.TRIDENT_OF_THE_SWAMP,
        CombatSpell.SANGUINESTI_STAFF,
        CombatSpell.TUMEKENS_SHADOW,
    )

    private val byItem: Map<Int, CombatSpell> by lazy {
        val map = HashMap<Int, CombatSpell>()
        fun add(key: String, spell: CombatSpell) {
            runCatching { getRSCM(key) }.getOrNull()?.let { map[it] = spell }
        }
        add("item.trident_of_the_seas", CombatSpell.TRIDENT_OF_THE_SEAS)
        add("item.trident_of_the_seas_full", CombatSpell.TRIDENT_OF_THE_SEAS)
        add("item.trident_of_the_seas_e", CombatSpell.TRIDENT_OF_THE_SEAS)
        add("item.trident_of_the_swamp", CombatSpell.TRIDENT_OF_THE_SWAMP)
        add("item.trident_of_the_swamp_e", CombatSpell.TRIDENT_OF_THE_SWAMP)
        add("item.sanguinesti_staff", CombatSpell.SANGUINESTI_STAFF)
        add("item.holy_sanguinesti_staff", CombatSpell.SANGUINESTI_STAFF)
        // Player report 2026-09-02: the shadow had no built-in spell, so wielding it fell
        // through to the MELEE combat class and every click was a staff bash.
        add("item.tumekens_shadow", CombatSpell.TUMEKENS_SHADOW)
        add("item.corrupted_tumekens_shadow", CombatSpell.TUMEKENS_SHADOW)
        map
    }

    private val SHADOWS: Set<Int> by lazy {
        listOf("item.tumekens_shadow", "item.corrupted_tumekens_shadow")
            .mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
    }

    /** Wielding Tumeken's shadow (either variant): its ×3 magic bonus rule applies. */
    fun isWieldingShadow(player: Player): Boolean =
        player.getEquipment(EquipmentType.WEAPON)?.id in SHADOWS

    /** The built-in spell of the wielded weapon, or null if it isn't a powered staff. */
    fun spellFor(player: Player): CombatSpell? {
        val weapon = player.getEquipment(EquipmentType.WEAPON) ?: return null
        return byItem[weapon.id]
    }

    fun isWielding(pawn: Pawn): Boolean = pawn is Player && spellFor(pawn) != null
}
