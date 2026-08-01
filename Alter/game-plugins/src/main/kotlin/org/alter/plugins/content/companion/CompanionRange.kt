package org.alter.plugins.content.companion

import dev.openrune.cache.CacheManager.getItem
import org.alter.game.action.EquipAction
import org.alter.game.model.entity.Player

/**
 * The bank-filtered item list that powers the panel's gear picker.
 *
 * Companions no longer consume ammo (see the PkBot exemption in RangedCombatStrategy) — a ranged
 * companion fires whatever is in its quiver indefinitely, so there is no bank-draining restock here
 * anymore.
 */
object CompanionRange {
    /** One bank item that fits a requested equipment slot, for the panel's gear picker. */
    class GearOption(val itemId: Int, val qty: Int, val wearable: Boolean)

    /**
     * Every distinct item in the **owner's bank** that can be worn in equipment [equipSlot], with the
     * total quantity held and whether [comp] meets its skill requirements. Drives the panel's
     * "click a slot → pick from bank" picker (the search box filters this list by name client-side).
     */
    fun gearOptions(owner: Player, comp: Companion, equipSlot: Int): List<GearOption> {
        val totals = LinkedHashMap<Int, Int>()
        for (i in 0 until owner.bank.capacity) {
            val it = owner.bank[i] ?: continue
            val def = runCatching { getItem(it.id) }.getOrNull() ?: continue
            if (def.equipSlot != equipSlot) continue
            if (!CompanionGear.isWearable(it.id)) continue // beer has equipSlot 3; slot alone isn't proof
            totals[it.id] = (totals[it.id] ?: 0) + it.amount
        }
        return totals.map { (id, qty) ->
            GearOption(id, qty, meetsReqs(comp, id) && CompanionGear.rankAllowsArmour(owner, id))
        }
    }

    /** True if [comp] satisfies the item's gear-level requirements — the SAME engine check players get. */
    private fun meetsReqs(comp: Companion, itemId: Int): Boolean =
        runCatching { EquipAction.meetsLevelRequirements(comp, itemId) }.getOrDefault(false)
}
