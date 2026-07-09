package org.alter.plugins.content.companion

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.game.action.EquipAction
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.World

/**
 * Ranged ammo supply for companions — and the bank-filtered item list that powers the panel's gear
 * picker.
 *
 * **Ammo (the owner restocks it):** a ranged companion fires the owner's arrows, which are consumed
 * normally (break ~20% / drop the rest as pick-up-able ground items, exactly like a real player). When
 * its quiver runs low, [ensureAmmo] tops it up with **more of the same arrow type pulled from the
 * owner's bank**. This is NOT the reverted free-ammo prototype: nothing is minted — it draws real
 * stock from the bank, so when the bank is empty the companion simply runs out and stops firing. The
 * owner just keeps arrows in the bank instead of hand-feeding the quiver.
 */
object CompanionRange {
    private val AMMO_SLOT = EquipmentType.AMMO.id
    /** Top the quiver up to this many arrows per restock. TUNABLE. */
    private const val REFILL_TO = 1000
    /** Restock once the quiver dips to/below this (also fires on a fully empty quiver). TUNABLE. */
    private const val LOW = 150

    /**
     * Keep a ranged companion's quiver stocked from the owner's bank. No-op for non-ranged companions,
     * when the owner is offline, or when the bank has no matching arrows. Returns true if it pulled any.
     */
    fun ensureAmmo(world: World, comp: Companion): Boolean {
        if (comp.archetype != CompanionStyle.RANGE) return false
        val current = comp.equipment[AMMO_SLOT]
        // Remember the live ammo type so we can still refill the SAME arrows after the stack hits 0.
        if (current != null) comp.lastAmmoId = current.id
        val ammoId = current?.id ?: comp.lastAmmoId
        if (ammoId < 0) return false
        val have = current?.amount ?: 0
        if (have > LOW) return false

        val owner = CompanionRegistry.ownerOf(world, comp) ?: return false
        val want = REFILL_TO - have
        if (want <= 0) return false
        val taken = owner.bank.remove(ammoId, want, assureFullRemoval = false)
        if (taken.completed <= 0) return false // bank is out of this ammo — companion just runs dry
        comp.equipment[AMMO_SLOT] = Item(ammoId, have + taken.completed)
        comp.calculateBonuses() // ranged-strength bonus follows the ammo
        return true
    }

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
