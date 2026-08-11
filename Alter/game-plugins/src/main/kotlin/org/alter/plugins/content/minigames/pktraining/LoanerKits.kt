package org.alter.plugins.content.minigames.pktraining

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.Spellbook
import org.alter.api.cfg.Varbit
import org.alter.api.ext.getSpellbook
import org.alter.api.ext.setSpellbook
import org.alter.api.ext.setVarbit
import org.alter.game.info.PlayerInfo
import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.kits.KitSetup
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * The training arena's **loaner-kit machinery** — stash the player's REAL gear to a persistent
 * blob, dress them in a borrowed kit, and hand everything back afterwards. Extracted from
 * [PkTrainingArenaPlugin] so companion sparring shares the exact same guarantees:
 *
 *  - The stash ([PK_ARENA_STASH_ATTR]) is written BEFORE any loaner item touches the player, so
 *    even a server crash mid-session restores the real gear on next login (the recovery hook in
 *    [PkTrainingArenaPlugin.onLogin] owns that path for every arena mode).
 *  - The blob's wire shape is append-only: live players carry old blobs, so existing fields
 *    ("inv"/"equip"/"book"/"magic"/"respawn") keep their exact meaning; newer fields ("levels")
 *    are optional and default to the legacy behaviour when absent.
 *  - While a stash exists, [TrainingArena.kitted] is true and every wealth-transfer sink is sealed.
 */
object LoanerKits {

    /** Vengeance's Magic requirement — Magic is boosted to here while kitted with the Lunar book. */
    const val VENG_MAGIC = 94

    /** The combat skills snapshot/restored around a bout (order fixed — it's the blob format). */
    val COMBAT_SKILLS = intArrayOf(
        Skills.ATTACK, Skills.DEFENCE, Skills.STRENGTH, Skills.HITPOINTS,
        Skills.RANGED, Skills.PRAYER, Skills.MAGIC,
    )

    /**
     * Dress the player in a loaner [kit] (built in the kit editor). Their REAL inventory/equipment/
     * spellbook/respawn/current-levels are stashed FIRST (only if not already — so switching kits
     * mid-session doesn't stash loaner gear), so nothing is lost. The kit's chosen spellbook is set;
     * Lunar boosts Magic so anyone can practise Vengeance.
     */
    fun applyLoaner(p: Player, kit: KitSetup) {
        stashIfNeeded(p)
        wipeContainers(p)
        kit.gear.forEach { (slotId, item) ->
            if (slotId < p.equipment.capacity) p.equipment[slotId] = Item(item.id, item.amount)
        }
        kit.inv.forEach { (slot, item) ->
            if (slot < p.inventory.capacity) p.inventory[slot] = Item(item.id, item.amount)
        }
        p.calculateBonuses()
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(p).syncAppearance()
        val book = when (kit.book) {
            KitSetup.BOOK_ANCIENTS -> Spellbook.ANCIENTS
            KitSetup.BOOK_LUNAR -> Spellbook.LUNAR
            else -> Spellbook.NORMAL
        }
        p.setSpellbook(book)
        if (book == Spellbook.LUNAR && p.getSkills().getCurrentLevel(Skills.MAGIC) < VENG_MAGIC) {
            p.getSkills().setCurrentLevel(Skills.MAGIC, VENG_MAGIC) // training affordance: veng at any level
        }
        p.setCurrentHp(p.getMaxHp())
        AttackTab.setEnergy(p, 100)
    }

    /**
     * Snapshot the player's real gear/spellbook/respawn/combat CURRENT levels into the persistent
     * stash blob — once per session (a later call while a stash exists is a no-op, so loaner gear
     * can never overwrite the real snapshot).
     */
    fun stashIfNeeded(p: Player) {
        if (p.attr[PK_ARENA_STASH_ATTR] != null) return
        val doc = Document()
        doc.append("inv", itemsDoc(p.inventory.rawItemsSnapshot()))
        doc.append("equip", itemsDoc(p.equipment.rawItemsSnapshot()))
        doc.append("book", p.getSpellbook().id)
        doc.append("magic", p.getSkills().getCurrentLevel(Skills.MAGIC))
        // All combat CURRENT levels (crash-safe undo for the Max Stats bout option; the boost is
        // always setCurrentLevel — base levels/XP are never touched, so they need no snapshot).
        val levels = Document()
        COMBAT_SKILLS.forEach { levels.append(it.toString(), p.getSkills().getCurrentLevel(it)) }
        doc.append("levels", levels)
        p.attr[RESPAWN_TILE_ATTR]?.let { doc.append("respawn", it) }
        p.attr[PK_ARENA_STASH_ATTR] = doc.toJson()
    }

    /** Restore the player's real gear/spellbook/respawn/levels from the stash blob and clear it. No-op if none. */
    fun restoreLoaner(p: Player) {
        val blob = p.attr[PK_ARENA_STASH_ATTR] ?: return
        wipeContainers(p)
        runCatching {
            val doc = Document.parse(blob)
            itemsFrom(doc, "inv").forEach { (idx, it) -> if (idx < p.inventory.capacity) p.inventory[idx] = it }
            itemsFrom(doc, "equip").forEach { (idx, it) -> if (idx < p.equipment.capacity) p.equipment[idx] = it }
            val book = Spellbook.values.firstOrNull { it.id == doc.getInteger("book", 0) } ?: Spellbook.NORMAL
            p.setSpellbook(book)
            val levels = doc.get("levels", Document::class.java)
            if (levels != null) {
                COMBAT_SKILLS.forEach { skill ->
                    levels.getInteger(skill.toString())?.let { p.getSkills().setCurrentLevel(skill, it) }
                }
            } else {
                // Legacy blob (pre-"levels"): only Magic was ever boosted.
                p.getSkills().setCurrentLevel(Skills.MAGIC, doc.getInteger("magic", p.getSkills().getBaseLevel(Skills.MAGIC)))
            }
            if (doc.containsKey("respawn")) p.attr[RESPAWN_TILE_ATTR] = doc.getInteger("respawn")
            else p.attr.remove(RESPAWN_TILE_ATTR)
        }.onFailure { logger.error(it) { "pktrain: failed to restore stash for ${p.username} — blob kept for retry" }; return }
        p.calculateBonuses()
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(p).syncAppearance()
        p.attr.remove(PK_ARENA_STASH_ATTR)
    }

    fun wipeContainers(p: Player) {
        for (i in 0 until p.inventory.capacity) p.inventory[i] = null
        for (i in 0 until p.equipment.capacity) p.equipment[i] = null
    }

    // ─── bson item (de)serialization — mirrors CompanionData so a bad blob never throws on login ───

    private fun itemsDoc(items: Map<Int, Item>): Document = Document().also { d ->
        items.forEach { (slot, it) -> d.append(slot.toString(), Document("id", it.id).append("amount", it.amount)) }
    }

    private fun itemsFrom(doc: Document, key: String): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        doc.get(key, Document::class.java)?.forEach { (slot, v) ->
            val d = v as Document
            out[slot.toInt()] = Item(d.getInteger("id"), d.getInteger("amount", 1))
        }
        return out
    }

    /** Snapshot a container's non-empty slots as slot→Item (an [Item] copy per occupied slot). */
    fun org.alter.game.model.container.ItemContainer.rawItemsSnapshot(): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        for (i in 0 until capacity) this[i]?.let { out[i] = Item(it.id, it.amount) }
        return out
    }
}
