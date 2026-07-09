package org.alter.plugins.content.minigames.pktraining

import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.entity.Player
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tiny cross-plugin registry: which players are currently **mid-bout** at the PK Training Arena
 * (teleported into the pit — countdown or fighting). Kept as a top-level object so the companion
 * brain can consult it statically (the LMS `inGame` pattern): companions stand down while their
 * owner spars, because the spar bot only ever targets the trainee — a "helping" companion would
 * farm it for free and ruin the lesson. Driven by [PkTrainingArenaPlugin] (set on startBout,
 * cleared on endRound/endBout/cleanup).
 *
 * Identity-keyed + weak: a logged-out player's entry can't survive to poison a reused player
 * index, and an entry missed by cleanup is garbage-collected with the player object.
 */
object TrainingArena {
    private val inBout: MutableSet<Player> = Collections.newSetFromMap(WeakHashMap())

    fun setInBout(p: Player, on: Boolean) {
        if (on) inBout.add(p) else inBout.remove(p)
    }

    fun inBout(p: Player): Boolean = p in inBout

    /**
     * True while [p] is wearing a LOANED training kit (their real gear is in the stash). Kits are
     * applied at bout start and returned the moment the round ends (LMS-style), so this is only ever
     * true mid-fight — and while true, every wealth-transfer sink (bank, trade, drop, alch, stake,
     * companion hand-off) is sealed, or the kit would leak into the economy.
     */
    fun kitted(p: Player): Boolean = p.attr[PK_ARENA_STASH_ATTR] != null
}
