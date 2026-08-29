package org.alter.plugins.content.combat

import org.alter.game.model.Area
import org.alter.game.model.attr.SAFE_DEATH_ATTR
import org.alter.game.model.entity.Player

/**
 * Designed-SAFE deaths — places/states where dying must NOT run the OSRS keep-N item drop.
 * Checked by [PvpDeathDropPlugin] *before* it computes a drop, so the everywhere-death rule
 * can never grief content that was built around free deaths.
 *
 * A death is safe when ANY of:
 *  - the victim carries [SAFE_DEATH_ATTR] (scripted content sets it on entry, clears on exit),
 *  - the victim is inside an instanced map (instances manage their own exit/rewards),
 *  - the death tile is inside a registered safe arena below.
 */
object SafeDeaths {

    /** Fixed-world arenas whose deaths are safe by design. */
    private val zones = mutableListOf<Area>()

    fun register(area: Area) {
        zones += area
    }

    fun isSafeDeath(victim: Player): Boolean {
        if (victim.attr[SAFE_DEATH_ATTR] == true) return true
        if (victim.world.instanceAllocator.getMap(victim.tile) != null) return true
        return zones.any { it.contains(victim.tile) }
    }
}
