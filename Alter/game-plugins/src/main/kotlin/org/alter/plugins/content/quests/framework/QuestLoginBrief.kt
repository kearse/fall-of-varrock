package org.alter.plugins.content.quests.framework

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **The one login reminder.** Every quest chain used to say its own piece on login — the framework
 * engine printed "current objective" (plus the step's nudge) per live quest, and each War-Prep
 * chain printed an objective line plus a "check it with ::warprep" tail. A player mid-campaign
 * logged in to six or more lines, two of them restating a quest that had just announced itself.
 *
 * Now the chains stay quiet on login and this prints **one line per live quest, [MAX] at most**,
 * with the rest collapsed into a single "+N more" pointer at `::quests`.
 *
 * Timing: [arm] is called from the login hook, [flush] from the next quest poll (≤3 ticks). The
 * deferral is what makes it correct — every chain resumes in its own plugin's login hook, in no
 * guaranteed order, so the brief can only be accurate once all of them have run.
 *
 * A quest that announced itself during that window (an auto-begin's "— begun." / "— next
 * objective:", a legacy chain's back-fill) calls [markAnnounced] and is left out: it has already
 * said the same thing, louder.
 */
object QuestLoginBrief {

    /** Objective lines printed before the "+N more" pointer takes over. */
    private const val MAX = 3

    /** Transient: set at login, cleared by the poll that prints the brief. */
    private val PENDING = AttributeKey<Boolean>()

    /** Transient: chain keys that spoke for themselves between login and the brief. */
    private val ANNOUNCED = AttributeKey<MutableSet<String>>()

    /** Login hook: schedule the brief for the next quest poll. */
    fun arm(p: Player) {
        p.attr[PENDING] = true
        p.attr[ANNOUNCED] = HashSet()
    }

    /** "I already told them" — the brief skips this chain (only matters before it fires). */
    fun markAnnounced(p: Player, chainKey: String) {
        (p.attr[ANNOUNCED] ?: return).add(chainKey)
    }

    /** Quest poll: print the brief once, if one is armed. */
    fun flush(p: Player) {
        if (p.attr[PENDING] != true) return
        p.attr.remove(PENDING)
        val announced: Set<String> = p.attr[ANNOUNCED] ?: emptySet()
        p.attr.remove(ANNOUNCED)

        val focus = QuestRegistry.activeChainIndex(p)
        val live = QuestRegistry.all()
            .filter { it.loginReminder && !it.hidden && it.key !in announced && it.started(p) && !it.complete(p) }
            // The quest `::quests` would open on leads; the rest follow the journal's own order.
            .sortedWith(
                compareByDescending<QuestChain> { it.chainIndex == focus }
                    .thenBy { it.chainIndex ?: Int.MAX_VALUE }
            )
        if (live.isEmpty()) return

        live.take(MAX).forEach { p.message(it.briefLine(p)) }
        val rest = live.size - MAX
        if (rest > 0) {
            p.message("<col=801700>$rest more quest${if (rest == 1) "" else "s"} in progress</col> — see <col=0000ff>::quests</col>.")
        }
    }
}
