package org.alter.plugins.content.mechanics.poison

import org.alter.game.model.entity.Pawn

/**
 * @author Tom <rspsmods@gmail.com>
 */

fun Pawn.poison(
    initialDamage: Int,
    onPoison: () -> Unit,
) {
    // Poison.poison() refreshes the HP orb itself now — every source shares that one path.
    if (!Poison.isImmune(this) && Poison.poison(this, initialDamage)) {
        onPoison()
    }
}
