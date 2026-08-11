package org.alter.plugins.content.minigames.tournament

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Spellbook
import org.alter.api.cfg.Varbit
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.TOURNEY_STASH_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.kits.KitArmoury
import org.alter.plugins.content.kits.KitEditor
import org.alter.plugins.content.kits.KitSetup
import org.alter.plugins.content.mechanics.trading.getTradeSession
import org.alter.plugins.content.minigames.duel.Duel
import org.alter.plugins.content.minigames.duel.DuelArena
import org.alter.plugins.content.minigames.duel.DuelArenaPlugin
import org.alter.plugins.content.minigames.duel.DuelRules
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * **Automatic Tournament** — the roadmap's scheduled 1v1 bracket, built entirely on the systems
 * that already exist:
 *
 *  - Every match is an **exhibition [Duel]** fought in a **private instanced copy of the duel pit**
 *    ([DuelArenaPlugin.ARENA_SOURCE]) — the full duel machinery applies unchanged: 3-2-1 countdown,
 *    sealed bubble (nobody can interfere), teleport lock, death/logout = loss. A whole round's
 *    matches run simultaneously, one instance each.
 *  - Everyone fights in the **same standardized loaner kit** ([KitArmoury.NH], the tribrid set) at
 *    their real stats — skill wins, not bank value. Real gear is stashed to [TOURNEY_STASH_ATTR]
 *    (persistent, crash-restores on login) and returned the moment the match ends: between rounds
 *    you stand in your own gear.
 *  - Nothing is risked: no stakes, instance deaths are safe, and the kit can't leave a match.
 *
 * Flow: signup opens (broadcast, [SIGNUP_TICKS] window, auto every [AUTO_INTERVAL_TICKS] or via
 * `::tourneystart`) → `::tournament` to enter (teleports you to the arena lobby) → bracket rounds
 * with byes for odd counts → champion takes a coin prize scaled by entrants. Matches that stall
 * past [MATCH_TIMEOUT_TICKS] resolve to whoever has more health left.
 */
class TournamentPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private enum class State { IDLE, SIGNUP, RUNNING }

    /** One signed-up player and where they came from (returned there on elimination). */
    private class Entrant(val player: Player, val homeTile: Tile)

    private class Match(val a: Entrant, val b: Entrant, val duel: Duel) {
        var ticks = 0
        var done = false
    }

    private var state = State.IDLE
    private var signupTicksLeft = 0
    private var ticksToAuto = AUTO_INTERVAL_TICKS
    private var round = 0
    private var startingRound = false // guards maybeFinishRound re-entering startRound mid-pairing
    private var idleTicks = 0         // RUNNING with no live matches (allocation failed) → retry
    private val entrants = mutableListOf<Entrant>()
    private val alive = mutableListOf<Entrant>()
    private val matches = mutableListOf<Match>()

    private val tourneyTimer = TimerKey()

    init {
        onWorldInit { world.timers[tourneyTimer] = 1 }
        onTimer(tourneyTimer) {
            runCatching { tick() }.onFailure { e -> logger.error(e) { "tournament tick failed" } }
            world.timers[tourneyTimer] = 1
        }

        // Crash recovery: a leftover stash blob means a match never finished — restore the real gear.
        onLogin {
            if (player.attr[TOURNEY_STASH_ATTR] != null) {
                restoreKit(player)
                player.message("<col=801700>Your gear was returned from the tournament.</col>")
            }
        }

        onLogout {
            // Signed up but gone before the bracket needed them — free the slot. Mid-match logouts
            // are the duel machinery's forfeit path; elimination happens via onResolved.
            entrants.removeAll { it.player === player && DuelArena.duelOf(player) == null }
            alive.removeAll { it.player === player && DuelArena.duelOf(player) == null }
        }

        onCommand("tournament", description = "Join the tournament (during signup) or see its status") {
            when (state) {
                State.SIGNUP -> join(player)
                State.RUNNING -> player.message("A tournament is running — round $round, ${alive.size} fighters left.")
                State.IDLE -> player.message("No tournament right now. The next one opens automatically — watch for the broadcast.")
            }
        }
        onCommand("tourneystart", Privilege.ADMIN_POWER, description = "Open tournament signup now") {
            if (state == State.IDLE) { openSignup() } else player.message("A tournament is already ${if (state == State.SIGNUP) "in signup" else "running"}.")
        }
        onCommand("tourneystop", Privilege.ADMIN_POWER, description = "Cancel the current tournament") {
            if (state == State.IDLE) player.message("No tournament to stop.")
            else { cancel("The tournament was cancelled."); player.message("Tournament cancelled.") }
        }
    }

    // ───────────────────────────── scheduling / signup ─────────────────────────────

    private fun tick() {
        when (state) {
            State.IDLE -> {
                if (--ticksToAuto <= 0) openSignup()
            }
            State.SIGNUP -> {
                signupTicksLeft--
                if (signupTicksLeft == SIGNUP_TICKS / 2) {
                    broadcast("$TAG Signup closes soon — ${entrants.size} entered. ::tournament to fight!")
                }
                if (signupTicksLeft <= 0) beginBracket()
            }
            State.RUNNING -> {
                matches.toList().forEach { m ->
                    if (!m.done && ++m.ticks >= MATCH_TIMEOUT_TICKS) timeout(m)
                }
                // Safety net: RUNNING with nothing live (every pairing failed to get an arena, or
                // an edge path missed maybeFinishRound) — re-drive the bracket after a beat.
                if (matches.none { !it.done }) {
                    if (++idleTicks >= ROUND_RETRY_TICKS) { idleTicks = 0; maybeFinishRound(force = true) }
                } else {
                    idleTicks = 0
                }
            }
        }
    }

    private fun openSignup() {
        state = State.SIGNUP
        signupTicksLeft = SIGNUP_TICKS
        round = 0
        entrants.clear(); alive.clear(); matches.clear()
        broadcast("$TAG An Automatic Tournament is forming! Type <col=ffffff>::tournament</col> to enter — standardized kits, nothing risked, champion takes the coin purse.")
    }

    private fun join(p: Player) {
        if (entrants.any { it.player === p }) { p.message("You're already entered."); return }
        if (entrants.size >= MAX_ENTRANTS) { p.message("The bracket is full ($MAX_ENTRANTS)."); return }
        if (!eligible(p) || p.getTradeSession() != null || p.isLocked() || KitEditor.isOpen(p)) {
            p.message("You can't enter right now — settle down somewhere safe first.")
            return
        }
        entrants += Entrant(p, p.tile)
        p.moveTo(LOBBY)
        p.message("<col=007f00>You've entered the tournament.</col> Fights start when signup closes — matches are fought in the standard kit, your own gear is safe.")
        broadcast("$TAG ${p.username} entered (${entrants.size}).")
    }

    private fun beginBracket() {
        entrants.retainAll { eligible(it.player) }
        if (entrants.size < MIN_ENTRANTS) {
            broadcast("$TAG Not enough fighters entered — tournament called off.")
            entrants.forEach { if (it.player.index >= 0) it.player.moveTo(it.homeTile) }
            reset()
            return
        }
        state = State.RUNNING
        alive.clear(); alive.addAll(entrants)
        broadcast("$TAG The tournament begins — ${alive.size} fighters. Good luck!")
        startRound()
    }

    // ───────────────────────────── bracket rounds ─────────────────────────────

    private fun startRound() {
        // Drop anyone who vanished or wandered somewhere a teleport-in would be an escape hatch
        // (wilderness, another instance, an unrelated duel).
        alive.retainAll {
            val ok = eligible(it.player)
            if (!ok && it.player.index >= 0) it.player.message("<col=ff0000>You were withdrawn from the tournament.</col>")
            ok
        }
        if (alive.size <= 1) { crown(alive.firstOrNull()); return }

        round++
        matches.clear()
        startingRound = true
        val order = alive.shuffled().toMutableList()
        if (order.size % 2 == 1) {
            val bye = order.removeAt(order.size - 1)
            bye.player.message("<col=007f00>Round $round: you drew a bye — you advance automatically.</col>")
        }
        broadcast("$TAG Round $round — ${order.size / 2} match${if (order.size / 2 == 1) "" else "es"}, ${alive.size} fighters left.")
        while (order.size >= 2) {
            startMatch(order.removeAt(0), order.removeAt(0))
        }
        startingRound = false
        // A round of pure walkovers (or failed allocations) never fires matchEnded — settle it now.
        maybeFinishRound()
    }

    /** One match: stash + standard kit for both, a private pit, an exhibition duel. */
    private fun startMatch(ea: Entrant, eb: Entrant) {
        val a = ea.player
        val b = eb.player
        // A missing fighter forfeits the pairing on the spot.
        if (!eligible(a)) { advanceWalkover(winner = eb, gone = ea); return }
        if (!eligible(b)) { advanceWalkover(winner = ea, gone = eb); return }

        val instance = RaidInstance.allocate(
            world, DuelArenaPlugin.ARENA_SOURCE, exitTile = LOBBY, owner = a.uid, autoDeallocate = false,
        )
        if (instance == null) {
            // No arena free — leave both in `alive`; startRound's retry path picks them up.
            logger.warn { "tournament: instance allocation failed for ${a.username} vs ${b.username}" }
            return
        }

        applyKit(a)
        applyKit(b)

        val duel = Duel(
            a, b, stakeA = emptyList(), stakeB = emptyList(), rules = DuelRules(),
            instance = instance, returnA = LOBBY, returnB = LOBBY, exhibition = true,
        )
        duel.countdown = DuelArenaPlugin.COUNTDOWN_TICKS
        val match = Match(ea, eb, duel)
        duel.onResolved = { winner, loser -> matchEnded(match, winner, loser) }
        DuelArena.active += duel
        matches += match

        a.moveTo(instance.translate(DuelArenaPlugin.RED_SPAWN))
        b.moveTo(instance.translate(DuelArenaPlugin.BLUE_SPAWN))
        listOf(a, b).forEach {
            // The full duel-start reset (stats/prayers/spec/poison/Vengeance), shared with staked
            // duels — previously only HP + spec energy, which let lobby prayers ride into round 1.
            DuelArenaPlugin.normalizeCombatState(it)
            it.message("<col=801700>Round $round:</col> ${a.username} vs ${b.username}. Winner advances!")
        }
    }

    private fun advanceWalkover(winner: Entrant, gone: Entrant) {
        alive.remove(gone)
        if (winner.player.index >= 0) winner.player.message("<col=007f00>Your opponent didn't show — you advance.</col>")
        maybeFinishRound()
    }

    /** An exhibition duel resolved (death/logout) — kits back, loser out, winner on to the next round. */
    private fun matchEnded(match: Match, winner: Player, loser: Player) {
        if (match.done) return
        match.done = true
        restoreKit(winner)
        restoreKit(loser)
        val loserEntrant = if (match.a.player === loser) match.a else match.b
        alive.remove(loserEntrant)
        if (loser.index >= 0) {
            loser.moveTo(loserEntrant.homeTile)
            loser.message("<col=ff0000>You're out of the tournament.</col> Better luck next time.")
        }
        if (winner.index >= 0) winner.message("<col=007f00>Match won — you advance!</col>")
        maybeFinishRound()
    }

    /** A stalled match (nobody died in time) goes to whoever has more health left. */
    private fun timeout(m: Match) {
        if (m.done || !DuelArena.active.remove(m.duel)) return
        val a = m.a.player
        val b = m.b.player
        val loser = if (a.getCurrentHp() >= b.getCurrentHp()) b else a
        val winner = m.duel.opponentOf(loser)
        listOf(a, b).forEach {
            if (it.index >= 0) {
                it.setCurrentHp(it.getMaxHp())
                it.moveTo(LOBBY)
                it.message("<col=801700>Time! The round goes to whoever held more health.</col>")
            }
        }
        matchEnded(m, winner, loser)
    }

    private fun maybeFinishRound(force: Boolean = false) {
        if (state != State.RUNNING) return
        if (startingRound && !force) return // pairing in progress — startRound settles up at its end
        if (matches.any { !it.done && DuelArena.active.contains(it.duel) }) return
        startRound()
    }

    private fun crown(champion: Entrant?) {
        if (champion == null) {
            broadcast("$TAG The tournament ends with no champion.")
            reset()
            return
        }
        val p = champion.player
        val prize = PRIZE_PER_ENTRANT.toLong() * entrants.size
        if (p.index >= 0) {
            runCatching {
                val coins = getRSCM("item.coins")
                val amount = prize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val added = p.inventory.add(item = coins, amount = amount, assureFullInsertion = false).completed
                if (added < amount) p.bank.add(coins, amount - added)
            }.onFailure { logger.error(it) { "tournament: prize payout failed for ${p.username}" } }
            p.message("<col=ffae00>You are the tournament champion!</col> The purse is yours.")
        }
        broadcast("$TAG <col=ffae00>${p.username}</col> wins the tournament of ${entrants.size} and takes ${prize / 1000}K coins!")
        logger.info { "TOURNAMENT champion=${p.username} entrants=${entrants.size} prize=$prize" }
        reset()
    }

    private fun cancel(reason: String) {
        // Resolve any live matches as no-contests: kits back, everyone to the lobby.
        matches.toList().forEach { m ->
            if (DuelArena.active.remove(m.duel)) {
                m.done = true
                listOf(m.a.player, m.b.player).forEach { f ->
                    restoreKit(f)
                    if (f.index >= 0) { f.setCurrentHp(f.getMaxHp()); f.moveTo(LOBBY) }
                }
            }
        }
        broadcast("$TAG $reason")
        reset()
    }

    private fun reset() {
        state = State.IDLE
        ticksToAuto = AUTO_INTERVAL_TICKS
        signupTicksLeft = 0
        round = 0
        entrants.clear(); alive.clear(); matches.clear()
    }

    /** Fit to fight: online, somewhere safe, not in an instance, not in some other duel. */
    private fun eligible(p: Player): Boolean =
        p.index >= 0 && PvpZones.isSafe(p.tile) &&
            world.instanceAllocator.getMap(p.tile) == null &&
            DuelArena.duelOf(p) == null

    // ───────────────────────────── standardized kit (stash / apply / restore) ─────────────────────────────

    private fun applyKit(p: Player) {
        KitEditor.close(p) // a kit screen left open at a bank must not overlay the fight
        stashIfNeeded(p)
        wipeContainers(p)
        val kit: KitSetup = KitArmoury.NH
        kit.gear.forEach { (slot, item) -> if (slot < p.equipment.capacity) p.equipment[slot] = Item(item.id, item.amount) }
        kit.inv.forEach { (slot, item) -> if (slot < p.inventory.capacity) p.inventory[slot] = Item(item.id, item.amount) }
        p.setSpellbook(Spellbook.values.firstOrNull { it.id == kit.book } ?: Spellbook.NORMAL)
        syncGear(p)
    }

    private fun stashIfNeeded(p: Player) {
        if (p.attr[TOURNEY_STASH_ATTR] != null) return
        val doc = Document()
        doc.append("inv", itemsDoc(snapshot(p.inventory)))
        doc.append("equip", itemsDoc(snapshot(p.equipment)))
        doc.append("book", p.getSpellbook().id)
        p.attr[TOURNEY_STASH_ATTR] = doc.toJson()
    }

    private fun restoreKit(p: Player) {
        val blob = p.attr[TOURNEY_STASH_ATTR] ?: return
        wipeContainers(p)
        runCatching {
            val doc = Document.parse(blob)
            itemsFrom(doc, "inv").forEach { (idx, it) -> if (idx < p.inventory.capacity) p.inventory[idx] = it }
            itemsFrom(doc, "equip").forEach { (idx, it) -> if (idx < p.equipment.capacity) p.equipment[idx] = it }
            p.setSpellbook(Spellbook.values.firstOrNull { it.id == doc.getInteger("book", 0) } ?: Spellbook.NORMAL)
        }.onFailure { logger.error(it) { "tournament: failed to restore stash for ${p.username} — blob kept for retry" }; return }
        syncGear(p)
        p.attr.remove(TOURNEY_STASH_ATTR)
    }

    private fun syncGear(p: Player) {
        p.calculateBonuses()
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(p).syncAppearance()
    }

    private fun wipeContainers(p: Player) {
        for (i in 0 until p.inventory.capacity) p.inventory[i] = null
        for (i in 0 until p.equipment.capacity) p.equipment[i] = null
    }

    private fun snapshot(c: org.alter.game.model.container.ItemContainer): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        for (i in 0 until c.capacity) c[i]?.let { out[i] = Item(it.id, it.amount) }
        return out
    }

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

    private fun broadcast(msg: String) {
        world.players.forEach { if (it.index >= 0 && it.entityType.isHumanControlled) it.message(msg) }
    }

    private companion object {
        const val TAG = "<col=8f00ff>[Tournament]</col>"

        /** The waiting area between rounds: the old Duel-Arena lobby, by the trainer. */
        val LOBBY = Tile(3367, 3274, 0)

        const val MIN_ENTRANTS = 2
        const val MAX_ENTRANTS = 32

        // Cadence (game ticks, ~0.6s each): signup window ~3 min; auto-run every ~4 hours;
        // a match that stalls ~5 min goes to whoever holds more health.
        const val SIGNUP_TICKS = 300
        const val AUTO_INTERVAL_TICKS = 24_000
        const val MATCH_TIMEOUT_TICKS = 500
        const val ROUND_RETRY_TICKS = 50

        /** Champion's coin purse per entrant — 8 fighters = 2M. TUNE freely. */
        const val PRIZE_PER_ENTRANT = 250_000
    }
}
