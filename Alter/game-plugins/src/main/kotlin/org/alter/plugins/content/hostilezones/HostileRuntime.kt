package org.alter.plugins.content.hostilezones

import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.HostileZone

/**
 * Live-state hooks the hostile-zone plugins publish so `::hostile` ([HostileZoneDevPlugin]) can
 * report and poke them without the plugins depending on each other. Everything is optional —
 * a plugin that failed to load simply leaves its slot empty.
 */
object HostileRuntime {
    /** Per zone key: one-line loot-spot status. */
    val lootStatus = HashMap<String, () -> String>()
    /** Per zone key: force every loot spot to refill on the next sweep. */
    val lootReset = HashMap<String, () -> Unit>()
    /** Per zone key: the mustered occupier garrison. */
    val occupiers = HashMap<String, HostileZone>()
    /** Advance the supply-drop machine one leg; returns a status line. */
    var supplyAdvance: (() -> String)? = null
    /** Complete an extraction for [Player] at their tile (no channel); returns false if not in a zone. */
    var forceExtract: ((Player) -> Boolean)? = null
    /** Per zone key: how many extraction objects spawned + the verb bound (for the list). */
    val extractionStatus = HashMap<String, String>()
}
