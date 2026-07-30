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
     *   bits 1-3  — companions currently fielded (0-7)
     *   bits 4-6  — companion cap for the player's rank (0-7)
     *   bits 7-10 — feudal title ordinal (0 = Peasant … 7 = King)
     */
    const val OPEN_VARP = 4619

    /** Coin price to raise one companion soldier (was General Zo's dialogue price — TUNABLE). */
    const val RECRUIT_COST = 1_000_000

    /** Pulse the open signal. */
    fun open(p: Player) = pulse(p)

    /** Re-pulse after a successful recruit so an open window updates its banner in place. */
    fun refresh(p: Player) = pulse(p)

    private fun pulse(p: Player) {
        val v = 1 or
            (CompanionRegistry.rosterSize(p).coerceIn(0, 7) shl 1) or
            (CompanionRegistry.companionCap(p).coerceIn(0, 7) shl 4) or
            (p.title.ordinal.coerceIn(0, 15) shl 7)
        p.setVarp(OPEN_VARP, v)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}
