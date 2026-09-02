package org.alter.plugins.content.interfaces.bank

import org.alter.api.ext.message
import org.alter.game.model.attr.LMS_STASH_ATTR
import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.entity.Player

/**
 * @author Tom <rspsmods@gmail.com>
 */
fun Player.openBank() {
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
