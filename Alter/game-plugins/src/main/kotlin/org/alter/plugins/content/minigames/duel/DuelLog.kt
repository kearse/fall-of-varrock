package org.alter.plugins.content.minigames.duel

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.saving.formats.impl.DatabaseManager
import org.bson.Document
import java.util.ArrayDeque

private val logger = KotlinLogging.logger {}

/**
 * The duel **scoreboard, virtualized** — the classic arena scoreboards (last 50 duels on the
 * wall) without a physical hub. Every resolved STAKED duel is recorded twice:
 *
 *  - an in-memory ring ([recent]) that `::duels` reads — instant, no database round-trip, and
 *    exactly the classic "walk up and read the board" feel (empties on restart, like a board
 *    being wiped);
 *  - a `duels` MongoDB collection the community site reads (`/duels` page) — the durable
 *    record. This doubles as the RMT-pattern ledger (who duels whom, for how much, how often)
 *    that we keep as cheap insurance even though RMT isn't a live threat. Writes are
 *    best-effort ([runCatching]): a missing database (local dev) costs the site page, never
 *    the duel.
 *
 * EXHIBITION duels (the Automatic Tournament) are deliberately NOT recorded here: nothing is
 * staked (no pot, no RMT signal) and — like the classic boards, which omitted even forfeits —
 * the scoreboard is about stakes. The tournament owns its own presentation via `onResolved`.
 * Unlike the classic boards, forfeits ARE listed (marked as such): on a board without a
 * physical crowd watching, a hidden loss category would just invite "safe" ragequits.
 */
object DuelLog {

    /** How a recorded duel ended (draws carry [DRAW_TIMEOUT]/[DRAW_DOUBLE_KO]/[DRAW_VANISHED], no winner). */
    const val END_DEATH = "death"
    const val END_FORFEIT = "forfeit"
    const val END_LOGOUT = "logout"
    const val DRAW_TIMEOUT = "draw-timeout"
    const val DRAW_DOUBLE_KO = "draw-double-ko"
    /** A principal vanished without a logout hook (stale-registration reclaim) — see DuelArenaPlugin.tick. */
    const val DRAW_VANISHED = "draw-vanished"

    /** One scoreboard row. For draws [winner]/[loser] hold the two fighters (no meaning to order). */
    data class Entry(
        val winner: String,
        val loser: String,
        val end: String,
        val rules: String,
        val potValue: Long,
        val at: Long,
    )

    private const val RECENT_MAX = 50

    private val recentRing = ArrayDeque<Entry>()

    /** The last [n] recorded duels, newest first (the in-game board). */
    fun recent(n: Int): List<Entry> = synchronized(recentRing) { recentRing.take(n) }

    /** Record a decided staked duel: [winner] took the pot from [loser]. */
    fun record(winner: Player, loser: Player, end: String, rules: DuelRules, potValue: Long) =
        push(Entry(winner.username, loser.username, end, rules.summary(), potValue, System.currentTimeMillis()), winner, loser)

    /** Record a drawn staked duel — both stakes went home. */
    fun recordDraw(a: Player, b: Player, end: String, rules: DuelRules, potValue: Long) =
        push(Entry(a.username, b.username, end, rules.summary(), potValue, System.currentTimeMillis()), a, b)

    private fun push(e: Entry, a: Player, b: Player) {
        synchronized(recentRing) {
            recentRing.addFirst(e)
            while (recentRing.size > RECENT_MAX) recentRing.removeLast()
        }
        // Durable record for the community site — best-effort, never on the duel's critical path.
        runCatching {
            DatabaseManager.connect()
            DatabaseManager.getCollection("duels").insertOne(
                Document()
                    .append("winner", e.winner)
                    .append("winnerLogin", loginOf(a))
                    .append("loser", e.loser)
                    .append("loserLogin", loginOf(b))
                    .append("end", e.end)
                    .append("rules", e.rules)
                    .append("potValue", e.potValue)
                    .append("at", e.at),
            )
        }.onFailure { t -> logger.warn(t) { "duel log write failed (site scoreboard only — duel unaffected)" } }
    }

    private fun loginOf(p: Player): String = (p as? Client)?.loginUsername ?: p.username.lowercase()
}
