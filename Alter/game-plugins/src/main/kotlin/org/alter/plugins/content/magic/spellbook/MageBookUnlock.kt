package org.alter.plugins.content.magic.spellbook

import org.alter.api.Spellbook
import org.alter.game.model.attr.MAGE_BOOKS_UNLOCKED_ATTR
import org.alter.game.model.entity.Player

/**
 * **Mage Tower unlock** — the reward gate for the special spellbooks.
 *
 * The Standard book is always available to everyone. The three "mage books" (Ancient, Lunar,
 * Arceuus) are locked until the player **clears the Mage Tower raid**, at which point the unlock
 * is persisted ([MAGE_BOOKS_UNLOCKED_ATTR]) and they may swap to those books whenever via
 * `::spellbook`. The raid simply calls [Player.unlockMageBooks] on completion; everything else
 * (the `::spellbook` gate) reads [Player.mageBooksUnlocked].
 */

/** True once the player has cleared the Mage Tower and the special books are unlocked. */
val Player.mageBooksUnlocked: Boolean get() = attr[MAGE_BOOKS_UNLOCKED_ATTR] == true

/**
 * Permanently unlock the Ancient/Lunar/Arceuus spellbooks for this player. Idempotent — safe to
 * call again on a repeat clear. Returns true if this call was the one that flipped the flag (so
 * callers can show a first-time "you have unlocked..." message).
 */
fun Player.unlockMageBooks(): Boolean {
    if (mageBooksUnlocked) return false
    attr[MAGE_BOOKS_UNLOCKED_ATTR] = true
    return true
}

/** Re-lock the special books (admin/test only — clears the Mage Tower unlock). */
fun Player.lockMageBooks() {
    attr.remove(MAGE_BOOKS_UNLOCKED_ATTR)
}

/** The Standard book is free; the three special books require the Mage Tower unlock. */
fun Spellbook.requiresMageTower(): Boolean = this != Spellbook.NORMAL
