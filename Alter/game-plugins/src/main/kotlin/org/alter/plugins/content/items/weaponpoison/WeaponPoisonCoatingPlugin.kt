package org.alter.plugins.content.items.weaponpoison

import dev.openrune.cache.CacheManager
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_ITEM_ID
import org.alter.game.model.attr.INTERACTING_ITEM_SLOT
import org.alter.game.model.attr.OTHER_ITEM_ID_ATTR
import org.alter.game.model.attr.OTHER_ITEM_SLOT_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.WeaponPoisons
import org.alter.rscm.RSCM.getRSCM

/**
 * Coating weapons in poison (OSRS Wiki, Weapon poison). Use a vial of Weapon poison / (+) / (++)
 * on an unpoisoned dagger, spear, hasta, dart, knife, arrow, bolt or javelin to turn it into
 * its (p) / (p+) / (p++) form. One vial coats one melee weapon or up to five stackable ammo,
 * and hands back the empty vial.
 *
 * Recipes are DERIVED from the cache at load: every unnoted item whose name ends in a poison
 * suffix is paired with the unnoted item of the same base name, so all ~200 poisonable ids
 * are covered without a hand-written table. Player report 2026-09-02: no coating existed.
 */
class WeaponPoisonCoatingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        const val AMMO_PER_VIAL = 5
        const val EMPTY_VIAL = "item.vial"
    }

    private val vialByStrength: Map<WeaponPoisons.Strength, Int> =
        mapOf(
            WeaponPoisons.Strength.P to "item.weapon_poison",
            WeaponPoisons.Strength.PP to "item.weapon_poison_5937",
            WeaponPoisons.Strength.PPP to "item.weapon_poison_5940",
        ).mapNotNull { (strength, key) -> runCatching { getRSCM(key) }.getOrNull()?.let { strength to it } }.toMap()

    /** (vial id, base weapon id) -> poisoned weapon id. */
    private val recipes = HashMap<Pair<Int, Int>, Int>()

    init {
        val items = CacheManager.getItems()
        val unnotedByName = HashMap<String, Int>()
        items.entries.sortedBy { it.key }.forEach { (id, def) ->
            if (def.noteTemplateId <= 0 && def.name != "null") {
                unnotedByName.putIfAbsent(def.name, id)
            }
        }
        items.entries.sortedBy { it.key }.forEach { (id, def) ->
            if (def.noteTemplateId > 0) return@forEach
            val strength = WeaponPoisons.strengthOf(id) ?: return@forEach
            val vial = vialByStrength[strength] ?: return@forEach
            val base = unnotedByName[def.name.removeSuffix(strength.suffix).trim()] ?: return@forEach
            recipes.putIfAbsent(vial to base, id)
        }
        recipes.forEach { (key, poisoned) ->
            val (vial, base) = key
            r.bindItemOnItem(vial, base) { coat(player, vial, base, poisoned) }
        }
    }

    private fun coat(player: Player, vial: Int, base: Int, poisoned: Int) {
        // Either item may have been "used on" the other; pick the slots by id.
        val fromId = player.attr[INTERACTING_ITEM_ID] ?: return
        val fromSlot = player.attr[INTERACTING_ITEM_SLOT] ?: return
        val toSlot = player.attr[OTHER_ITEM_SLOT_ATTR] ?: return
        val toId = player.attr[OTHER_ITEM_ID_ATTR] ?: return
        val baseSlot = if (fromId == base) fromSlot else if (toId == base) toSlot else return
        val vialSlot = if (fromId == vial) fromSlot else if (toId == vial) toSlot else return

        val baseItem = player.inventory[baseSlot] ?: return
        if (baseItem.id != base || player.inventory[vialSlot]?.id != vial) return

        val stackable = CacheManager.getItem(base).stackable
        val amount = if (stackable) minOf(AMMO_PER_VIAL, baseItem.amount) else 1

        if (player.inventory.remove(item = vial, amount = 1, assureFullRemoval = true, beginSlot = vialSlot).completed == 0) return
        if (player.inventory.remove(item = base, amount = amount, assureFullRemoval = true, beginSlot = baseSlot).completed == 0) {
            player.inventory.add(item = vial, amount = 1) // refund the vial
            return
        }
        // Coated stackables merge into an existing poisoned stack; a lone weapon keeps its slot.
        val landed = player.inventory.add(item = poisoned, amount = amount, beginSlot = if (stackable) -1 else baseSlot)
        if (landed.completed < amount) {
            // Could not fit (full inventory with a partially coated stack): put things back.
            player.inventory.remove(item = poisoned, amount = landed.completed)
            player.inventory.add(item = base, amount = amount)
            player.inventory.add(item = vial, amount = 1)
            player.message("You don't have enough inventory space to do that.")
            return
        }
        runCatching { player.inventory.add(item = getRSCM(EMPTY_VIAL), amount = 1) }

        val name = CacheManager.getItem(base).name
        player.message(if (amount > 1) "You coat $amount ${name.lowercase()}s with poison." else "You coat the ${name.lowercase()} with poison.")
    }
}
