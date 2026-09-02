package org.alter.plugins.content.companion

import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.title

/**
 * Server half of the client-drawn **Muster Companions** window (`lofrecruit` in the custom
 * client): General Zo's recruiter as three discipline cards + a banner strip, replacing the
 * old four-step chat menu.
 *
 * Open/refresh signal is one packed pulse varp ([OPEN_VARP]); the recruit action comes back
 * as the public-chat token `::zo recruit <melee|range|mage>` routed to `zoclick`
 * ([RecruitClickPlugin]).
 */
object RecruitMenu {
    /**
     * Overlay-open varp (docs/overlay-design-system.md §8), pulsed to 0 after 2 ticks so it
     * never persists/re-fires on login. Packed layout:
     *   bit 0     — open flag (always 1 when pulsed)
     *   bits 1-3  — roster size (fielded + benched, 0-7)
     *   bits 4-6  — roster cap for the player's rank (0-7)
     *   bits 7-10 — feudal title ordinal (0 = Peasant … 7 = King)
     */
    const val OPEN_VARP = 4619

    /**
     * Coin price of each soldier, by how many the player already keeps (fielded or benched): the
     * first is 10M, the second 100M, the third 500M. The whole banner takes the field at once
     * ([CompanionRegistry.ACTIVE_MAX]) — real combat power — so this ladder is the sink that pays
     * for it (operator decision 2026-09-02). The client draws the same ladder from the roster size
     * in the varp: keep `LofRecruitOverlay.RECRUIT_COSTS` in step. TUNABLE.
     */
    val RECRUIT_COSTS = intArrayOf(10_000_000, 100_000_000, 500_000_000)

    /** The price of the NEXT soldier for a player who already keeps [rosterSize]. */
    fun recruitCost(rosterSize: Int): Int = RECRUIT_COSTS[rosterSize.coerceIn(0, RECRUIT_COSTS.lastIndex)]

    /** The price of [p]'s next soldier. */
    fun nextCost(p: Player): Int = recruitCost(CompanionRegistry.rosterSize(p))

    /** Pulse the open signal. */
    fun open(p: Player) = pulse(p)

    /** Re-pulse after a successful recruit so an open window updates its banner in place. */
    fun refresh(p: Player) = pulse(p)

    private fun pulse(p: Player) {
        val v = 1 or
            (CompanionRegistry.rosterSize(p).coerceIn(0, 7) shl 1) or
            (CompanionRegistry.rosterCap(p).coerceIn(0, 7) shl 4) or
            (p.title.ordinal.coerceIn(0, 15) shl 7)
        p.setVarp(OPEN_VARP, v)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}
