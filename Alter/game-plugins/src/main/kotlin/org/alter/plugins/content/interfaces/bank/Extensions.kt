package org.alter.plugins.content.interfaces.bank

import org.alter.api.ext.message
import org.alter.game.model.attr.LAST_HIT_BY_ATTR
import org.alter.game.model.attr.LMS_STASH_ATTR
import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isBeingAttacked

/**
 * Whether [this] is in a live PvP exchange: the 10-second combat lock is running AND the other
 * party is a player (bots included). NPC fights don't count — you can still bank at a booth
 * while a goblin pokes you.
 */
private fun Player.inPvpCombat(): Boolean {
    if (!isBeingAttacked()) return false
    return getCombatTarget() is Player || attr[LAST_HIT_BY_ATTR]?.get() is Player
}

/**
 * @author Tom <rspsmods@gmail.com>
 */
fun Player.openBank() {
    // Player report 2026-09-02: "::bank should not be accessible when in PvP fights". Every
    // bank path (::bank/::pbank/::storage, booths, bankers) funnels through here.
    if (inPvpCombat()) {
        message("You can't bank while you're fighting another player.")
        return
    }
    // Loaned-gear seal: while wearing a PK-training loaner kit or competing in Last Man Standing,
    // the ENTIRE bank is off-limits — every bank path funnels through here (::bank/::pbank, booths,
    // bankers), and banking a borrowed set would smuggle it past the end-of-round gear restore
    // (which only wipes inventory+equipment). The kit/round loot must die with the round.
    if (attr[PK_ARENA_STASH_ATTR] != null || attr[LMS_STASH_ATTR] != null) {
        message("You can't bank borrowed gear — finish the fight first.")
        return
    }
    Bank.open(this)
}
