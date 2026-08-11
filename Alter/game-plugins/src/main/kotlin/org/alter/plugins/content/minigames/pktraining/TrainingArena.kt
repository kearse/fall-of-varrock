package org.alter.plugins.content.minigames.pktraining

import org.alter.game.model.Area
import org.alter.game.model.Tile
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
 * cleared on endRound/endBout/cleanup) and by the companion-sparring plugin.
 *
 * Identity-keyed + weak: a logged-out player's entry can't survive to poison a reused player
 * index, and an entry missed by cleanup is garbage-collected with the player object.
 *
 * Also home to the arena's SHARED GEOGRAPHY + cadence constants, so the sibling training plugins
 * (generic spar bots, companion sparring) fight in the same pits under the same rules.
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

    // ── shared arena geography (mapdump-verified; see PkTrainingArenaPlugin's class doc) ──

    /**
     * Bouts fight in a PRIVATE INSTANCED COPY of the NORTH-WEST pit (region 13362, pit interior
     * x3334..3351 z3246..3258, row z3251 fully clear of walls/objects) — one instance per bout,
     * mirroring the staked duels' NE-pit instances. This is the chunk-aligned 4x3-chunk template
     * including the pit's enclosing walls; everything beyond the copied chunks is collision-blocked
     * inside the instance, so nobody walks out mid-bout.
     */
    val PIT_SOURCE = Area(3328, 3240, 3359, 3263)

    /** Spawn tiles in SOURCE coordinates — translated into the allocated instance per bout. */
    val BOUT_PLAYER_TILE = Tile(3339, 3251, 0)
    val BOUT_BOT_TILE = Tile(3347, 3251, 0)

    /** Beside the trainer — round-end + death respawn, and each instance's fallback exit tile. */
    val EXIT_TILE = Tile(3368, 3269, 0)

    /**
     * The whole Duel-Arena grounds: SafeDeath zone + "you left, hand the kit back" boundary.
     * x-min 3330 so the NW pit's full interior (x3334+) is inside — a fighter hugging the pit's
     * west wall must NOT trip the "left the arena" kit-return check mid-bout.
     */
    val ARENA = Area(3330, 3242, 3396, 3296)

    /** Bots/companions won't chase past this from their bout corner (keeps them in the arena). */
    const val ARENA_LEASH = 40

    const val ARENA_TICK = 2 // session upkeep cadence (~1.2s)
    const val COUNTDOWN_STEPS = 4 // arena-ticks to FIGHT! (prints 3... 2... 1... at ~1.2s each)
}
