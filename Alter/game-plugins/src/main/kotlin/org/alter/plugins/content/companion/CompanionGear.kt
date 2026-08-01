package org.alter.plugins.content.companion

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.api.cfg.Varbit
import org.alter.api.ext.message
import org.alter.api.ext.setVarbit
import org.alter.game.action.EquipAction
import org.alter.game.info.PlayerInfo
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.war.RANK_GATED_ARMOUR_SLOTS
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.armourTier
import org.alter.plugins.content.war.canWear
import org.alter.rscm.RSCM.getRSCM

/**
 * Player-given gear + the server's free food supply for companions.
 *
 * **Gear** is the owner's responsibility: they wear the kit they want to give and choose "Gear" on
 * the companion, which moves their WORN equipment onto it (the companion's old kit returns to the
 * owner's inventory). "Dismiss" hands it all back. (Phase 1 — the bank-drag UI is Phase 2.)
 *
 * **Food** is NOT player-given: the server keeps a companion topped up with a standard food supply,
 * restocked whenever it's in a safezone (town). The brain eats it via `BotBrain.maybeEat`.
 */
object CompanionGear {
    /** Most of a stackable item (ammo, darts) a single panel equip will pull from the bank. Companions
     *  don't consume ammo, so this is just how much of the owner's stack rides on the companion. */
    private const val MAX_STACKABLE_EQUIP = 100

    /**
     * The standard PKing supply the server keeps a companion stocked with — a real NH kit (brews,
     * restores, sharks, karambwan combo food) plus the archetype's combat potion. Mirrors the bot
     * loadouts; the brain eats brews/food/karambwan via `BotBrain.maybeEat`. TUNE amounts here.
     */
    private fun kit(archetype: CompanionStyle): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        fun add(name: String, count: Int) { m[name] = (m[name] ?: 0) + count }
        when (archetype) {
            CompanionStyle.MELEE -> {
                food(::add); add("item.super_combat_potion4", 1)
            }
            CompanionStyle.RANGE -> {
                food(::add); add("item.ranging_potion4", 1)
            }
            CompanionStyle.MAGE -> {
                // Companions cast for free (infinite-runes varbit set at spawn — see CompanionRegistry),
                // so no rune supply is needed; the freed inventory goes to a fuller food + magic pot kit.
                food(::add); add("item.magic_potion4", 1)
            }
        }
        return m
    }

    private inline fun food(add: (String, Int) -> Unit) {
        add("item.saradomin_brew4", 6)
        add("item.super_restore4", 4)
        add("item.cooked_karambwan", 4)
        add("item.shark", 8)
    }

    /**
     * True if the item is genuinely equippable — it carries a Wield/Wear-style inventory option.
     * The cache gives plenty of consumables (beer, food, potions) `equipSlot == 3`, so slot alone
     * is NOT proof an item belongs in a hand: filtering on it let a companion "wield" a beer as a
     * melee weapon (weaponType 0 → unarmed punches). Both the panel picker and the equip path check
     * this.
     */
    fun isWearable(itemId: Int): Boolean {
        val def = runCatching { getItem(itemId) }.getOrNull() ?: return false
        return def.interfaceOptions.any {
            it != null && (it.equals("Wield", true) || it.equals("Wear", true) ||
                it.equals("Equip", true) || it.equals("Hold", true))
        }
    }

    /**
     * True unless [itemId] is rank-gated metal armour above the OWNER's feudal rank ceiling.
     * Armour has no level requirements on this server — rank is THE armour requirement — so a
     * companion's armour is capped by its owner's rank, exactly like the owner's own worn gear.
     * Weapons and non-tiered armour (hide/leather etc.) always pass.
     */
    fun rankAllowsArmour(owner: Player, itemId: Int): Boolean {
        val def = runCatching { getItem(itemId) }.getOrNull() ?: return false
        if (def.equipSlot !in RANK_GATED_ARMOUR_SLOTS) return true
        val tier = armourTier(def.name) ?: return true
        return owner.canWear(tier)
    }

    /** Move the owner's currently-worn equipment onto [comp]; the companion's old kit returns.
     *  (Dead Phase-1 path — gear is now bank/panel-driven — but kept guardrailed: pieces the companion
     *  isn't a high enough level for are skipped and left on the owner, mirroring [equipFromBank].) */
    fun gearFromOwner(owner: Player, comp: Companion) {
        // Loaner-kit seal: a PK-training kit can't be parked on a companion — companion gear
        // persists to the roster blob and would survive the end-of-round kit restore.
        if (org.alter.plugins.content.minigames.pktraining.TrainingArena.kitted(owner)) {
            owner.message("You can't hand borrowed gear to your companion.")
            return
        }
        var moved = 0
        var blocked = 0
        for (slot in 0 until comp.equipment.capacity) {
            val give = owner.equipment[slot] ?: continue
            if (!EquipAction.meetsLevelRequirements(comp, give.id)) { blocked++; continue } // same gear-level guardrail as players
            if (!rankAllowsArmour(owner, give.id)) { blocked++; continue } // owner's feudal rank caps companion armour too
            val old = comp.equipment[slot]
            comp.equipment[slot] = Item(give.id, give.amount)
            owner.equipment[slot] = null
            old?.let { returnToOwner(owner, it.id, it.amount) } // lossless even with a full inventory
            moved++
        }
        if (blocked > 0) owner.message("<col=801700>Sir ${comp.username} wasn't a high enough level for $blocked item(s); they were left on you.</col>")
        if (moved == 0) {
            if (blocked == 0) owner.message("Wear the gear you want to give first, then choose Gear.")
            return
        }
        owner.calculateBonuses()
        PlayerInfo(owner).syncAppearance()
        refreshGear(comp)
        owner.message("<col=4f9b4f>Your companion is outfitted.</col>")
    }

    /** Return the companion's whole worn kit to the owner's inventory. */
    fun dismiss(owner: Player, comp: Companion) {
        var moved = 0
        var kept = 0
        for (slot in 0 until comp.equipment.capacity) {
            val it = comp.equipment[slot] ?: continue
            // Transactional: a full inventory used to null the slot anyway and destroy the item.
            val tx = owner.inventory.add(it.id, it.amount)
            if (tx.completed >= it.amount) {
                comp.equipment[slot] = null
                moved++
            } else {
                if (tx.completed > 0) comp.equipment[slot] = Item(it.id, it.amount - tx.completed)
                kept++
            }
        }
        if (moved == 0 && kept == 0) { owner.message("Your companion has no gear to return."); return }
        if (moved > 0) refreshGear(comp)
        if (kept > 0) owner.message("<col=801700>Your inventory is full — $kept item(s) stay on Sir ${comp.username}.</col>")
        if (moved > 0) owner.message("<col=4f9b4f>Your companion hands its gear back.</col>")
    }

    /** Top the companion's food + pots back up to the standard kit (free; on spawn + in a safezone). */
    fun restock(comp: Companion) {
        kit(comp.archetype).forEach { (name, target) ->
            val id = runCatching { getRSCM(name) }.getOrNull() ?: return@forEach
            val have = comp.inventory.getItemCount(id)
            if (have < target) comp.inventory.add(id, target - have)
        }
    }

    // ---- Panel-driven gear management (Stage 2): gear comes from / returns to the owner's BANK. ----

    /**
     * Equip [itemId] from the **owner's bank** onto [comp] (the panel's "add gear" action). Whatever
     * the companion already wears in that slot returns to the bank, and weapon/shield (2-handed)
     * conflicts are resolved back to the bank too — mirroring [org.alter.game.action.EquipAction].
     * Honours the item's skill requirements against the COMPANION's levels.
     */
    fun equipFromBank(owner: Player, comp: Companion, itemId: Int) {
        val def = getItem(itemId)
        val slot = def.equipSlot
        if (slot < 0 || !isWearable(itemId)) { owner.message("That item can't be equipped."); return }
        if (owner.bank.getItemCount(itemId) <= 0) { owner.message("That item isn't in your bank."); return }

        // Same gear-level guardrail a real player gets (EquipAction.meetsLevelRequirements — ALL skills,
        // base levels), checked against the COMPANION's own levels. The panel pre-flags this, but the
        // server is the authority (commands / a stale panel must never let an under-levelled companion equip).
        if (!EquipAction.meetsLevelRequirements(comp, itemId)) {
            owner.message("<col=801700>Sir ${comp.username} isn't a high enough level to wear ${itemName(itemId)}.</col>")
            return
        }

        // Armour is feudal-rank gated, not level gated — a companion's armour ceiling is its OWNER's
        // rank (same TitlePlugin gate the owner faces, else a Knight could dress his soldier in dragon).
        if (!rankAllowsArmour(owner, itemId)) {
            val tier = armourTier(def.name)!!
            owner.message("<col=801700>You must be a ${Title.forTier(tier).display} before your companions may wear ${itemName(itemId)}.</col>")
            return
        }

        // Cap stackable grabs — never hand a companion the owner's ENTIRE bank stack of arrows/darts.
        val amount = if (def.stackable) minOf(owner.bank.getItemCount(itemId), MAX_STACKABLE_EQUIP) else 1
        val taken = owner.bank.remove(itemId, amount, assureFullRemoval = false)
        if (taken.completed == 0) { owner.message("Couldn't take that from your bank."); return }

        // Transactional stash: returns false (leaving the worn item in place) when the bank can't
        // take it — silently nulling the slot on a full bank destroyed the displaced weapon.
        fun stash(s: Int): Boolean {
            if (s < 0 || s >= comp.equipment.capacity) return true
            val worn = comp.equipment[s] ?: return true
            val tx = owner.bank.add(worn.id, worn.amount)
            if (tx.completed < worn.amount) {
                if (tx.completed > 0) owner.bank.remove(worn.id, tx.completed)
                return false
            }
            comp.equipment[s] = null
            return true
        }
        var stashed = stash(slot)
        if (stashed && def.equipType != -1 && def.equipType != slot) stashed = stash(def.equipType) // 2h → also clear shield slot
        if (stashed) {
            for (i in 0 until comp.equipment.capacity) {                        // shield → clear a worn 2h
                val worn = comp.equipment[i] ?: continue
                val wd = getItem(worn.id)
                if (wd.equipType == slot && wd.equipType != 0 && !stash(i)) { stashed = false; break }
            }
        }
        if (!stashed) {
            returnToOwner(owner, itemId, taken.completed) // abort: hand the new item back losslessly
            owner.message("<col=801700>Your bank is full — couldn't swap Sir ${comp.username}'s gear.</col>")
            refreshGear(comp) // slots stashed before the failure did change
            return
        }
        comp.equipment[slot] = Item(itemId, taken.completed)
        refreshGear(comp)
        owner.message("<col=4f9b4f>Equipped ${itemName(itemId)} on Sir ${comp.username}.</col>")
    }

    /** Best-effort lossless return of an item to [owner]: bank → inventory → the ground at their feet. */
    private fun returnToOwner(owner: Player, id: Int, amount: Int) {
        var left = amount
        left -= owner.bank.add(id, left).completed
        if (left > 0) left -= owner.inventory.add(id, left).completed
        if (left > 0) owner.world.spawn(org.alter.game.model.entity.GroundItem(id, left, owner.tile, owner))
    }

    /** Return the item in equipment [equipSlot] of [comp] to the **owner's bank** (panel "remove"). */
    fun unequipToBank(owner: Player, comp: Companion, equipSlot: Int) {
        if (equipSlot < 0 || equipSlot >= comp.equipment.capacity) return
        val item = comp.equipment[equipSlot] ?: return
        // Transactional: a full bank returns completed < amount, and nulling the slot anyway
        // DESTROYED the item (one of the "companion's weapon disappeared" vectors).
        val tx = owner.bank.add(item.id, item.amount)
        if (tx.completed < item.amount) {
            if (tx.completed > 0) owner.bank.remove(item.id, tx.completed)
            owner.message("<col=801700>Your bank is full — ${itemName(item.id)} stays on Sir ${comp.username}.</col>")
            return
        }
        comp.equipment[equipSlot] = null
        refreshGear(comp)
        owner.message("<col=4f9b4f>Returned ${itemName(item.id)} to your bank.</col>")
    }

    /**
     * Recompute bonuses + reset the weapon-type varbit + re-render. The varbit (357) is what
     * `CombatConfigs.getCombatClass` reads to pick melee/ranged/magic; a real client sets it on wield
     * but a clientless companion never does, so EVERY path that touches companion equipment (panel
     * equip, worn-gear transfer, dismiss, spawn/restore) must come through here or the companion
     * silently fights melee regardless of its weapon.
     */
    fun refreshGear(comp: Companion) {
        comp.calculateBonuses()
        val weapon = comp.equipment[EquipmentType.WEAPON.id]
        comp.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(comp).syncAppearance()
    }

    private fun itemName(id: Int): String = runCatching { getItem(id).name ?: "the item" }.getOrDefault("the item")
}
