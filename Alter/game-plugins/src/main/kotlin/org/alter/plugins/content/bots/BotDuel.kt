package org.alter.plugins.content.bots

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.RESPAWN_TILE_ATTR

private val logger = KotlinLogging.logger {}

/**
 * **`::botduel` — the PvP combat regression harness.** Pits two [PkBot]s against each other with
 * the full NH brain ([BotBrain]) on a tile of your choosing, resolves the bout, logs a per-bout
 * line and — for `::botduel all` — a round-robin **win matrix** over every loadout. Because a bot
 * wears real items and fights through the same item-driven combat / special / formula code as a
 * human, this exercises the combat core headlessly: run it after any formula, strategy or brain
 * change and diff the matrix.
 *
 * Mechanics: each bot's [PkBot.duelPartner] locks its brain to the other (bypassing the
 * wilderness / no-bot-target / leash rules — bot-vs-bot is already allowed anywhere by
 * `Combat.canEngage`), so duels run on safe ground where no ambient colony musters. A dying duelist
 * drops nothing and credits nothing ([BotCombatPlugin] short-circuits on `duelPartner`); the
 * standard death sequence respawns it at the arena tile ([RESPAWN_TILE_ATTR]) for the tick before
 * `BotCombatPlugin.onPlayerDeath` despawns it, the survivor is despawned here. A bout that runs past
 * [DUEL_MAX_TICKS] is a draw. `::botduel all` runs [LANES] bouts at once, spaced [LANE_SPACING]
 * tiles apart along the arena's x axis.
 *
 * Expectations worth asserting when you read the matrix: the metal ladder is monotonic (bronze <
 * iron < … < dragon), `elite_nh` sits in the top three, and no loadout draws every bout (a draw
 * everywhere means neither bot could hit — a broken strategy, not a stalemate).
 */
object BotDuel {

    private class Lane(
        val a: PkBot, val b: PkBot, val keyA: String, val keyB: String,
        val start: Int, val round: Int, val rounds: Int,
    ) {
        var deadA = false
        var deadB = false
    }

    private data class Result(val a: String, val b: String, val winner: String?, val ticks: Int)

    private val lanes = ArrayList<Lane>()
    private val pending = ArrayDeque<Triple<String, String, Int>>()
    private val results = ArrayList<Result>()
    private var caller: PlayerUID? = null
    private var arena: Tile? = null
    private var lanesMax = 1
    private var matrix = false
    private var rounds = 1

    val busy: Boolean get() = lanes.isNotEmpty() || pending.isNotEmpty()

    /** One pairing, [rounds] bouts, one lane at a time. Returns false when a job is already running. */
    fun start(world: World, keyA: String, keyB: String, tile: Tile, rounds: Int, callerUid: PlayerUID?): Boolean {
        if (busy) return false
        begin(tile, callerUid, rounds, lanesMax = 1, matrix = false)
        for (r in 1..rounds) pending += Triple(keyA, keyB, r)
        pump(world)
        return true
    }

    /** Every unordered pair of loadouts, [rounds] bouts each, [LANES] lanes at once. Returns the
     *  number of bouts queued (0 when busy). */
    fun startAll(world: World, tile: Tile, callerUid: PlayerUID?, rounds: Int): Int {
        if (busy) return 0
        begin(tile, callerUid, rounds, lanesMax = LANES, matrix = true)
        val keys = BotLoadouts.keys().toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) for (r in 1..rounds) pending += Triple(keys[i], keys[j], r)
        val n = pending.size
        pump(world)
        return n
    }

    fun stop(world: World) {
        pending.clear()
        for (l in lanes) {
            if (l.a.index >= 0) BotManager.despawn(world, l.a)
            if (l.b.index >= 0) BotManager.despawn(world, l.b)
        }
        lanes.clear()
        matrix = false
    }

    fun status(): List<String> = listOf(
        "[botduel] ${lanes.size} live lane(s), ${pending.size} queued, ${results.size} resolved" +
            (if (matrix) " (matrix run)" else ""),
    ) + lanes.map { "  r${it.round}/${it.rounds} ${it.keyA} vs ${it.keyB}: ${it.a.getCurrentHp()}/${it.a.getMaxHp()} vs ${it.b.getCurrentHp()}/${it.b.getMaxHp()} hp" }

    /** [BotCombatPlugin]'s pre-death hook: mark the faller (the bout resolves on the next tick). */
    fun onDeath(bot: PkBot) {
        for (l in lanes) {
            if (l.a === bot) l.deadA = true
            if (l.b === bot) l.deadB = true
        }
    }

    /** Driven every game tick by [BotPlugin]. */
    fun tick(world: World) {
        if (lanes.isEmpty()) return
        val now = world.currentCycle
        val done = lanes.filter { l ->
            l.deadA || l.deadB || l.a.index < 0 || l.b.index < 0 || l.a.isDead() || l.b.isDead() || now - l.start > DUEL_MAX_TICKS
        }
        for (l in done) resolve(world, l, now)
        lanes.removeAll(done.toSet())
        pump(world)
        if (lanes.isEmpty() && pending.isEmpty()) finish(world)
    }

    // ---------------------------------------------------------------------------------------

    private fun begin(tile: Tile, callerUid: PlayerUID?, rounds: Int, lanesMax: Int, matrix: Boolean) {
        results.clear()
        this.arena = tile
        this.caller = callerUid
        this.rounds = rounds.coerceIn(1, 50)
        this.lanesMax = lanesMax
        this.matrix = matrix
    }

    private fun pump(world: World) {
        while (lanes.size < lanesMax && pending.isNotEmpty()) {
            val (a, b, r) = pending.removeFirst()
            launch(world, a, b, r)
        }
    }

    private fun launch(world: World, keyA: String, keyB: String, round: Int) {
        val base = arena ?: return
        val la = BotLoadouts.get(keyA) ?: return
        val lb = BotLoadouts.get(keyB) ?: return
        val slot = lanes.size
        val want = Tile(base.x + slot * LANE_SPACING, base.z, base.height)
        val tileA = BotManager.reachableTileAround(world, from = base, centre = want, radius = 2) ?: want
        val tileB = BotManager.reachableTileAround(world, from = base, centre = Tile(tileA.x + 2, tileA.z, tileA.height), radius = 2)
            ?: Tile(tileA.x + 1, tileA.z, tileA.height)
        val a = BotManager.spawn(world, la, tileA) ?: return
        val b = BotManager.spawn(world, lb, tileB) ?: run { BotManager.despawn(world, a); return }
        for (bot in listOf(a, b)) {
            bot.homeTile = bot.tile
            bot.roamRadius = 0
            bot.leashRadius = 0
            bot.ambushEverywhere = true
            bot.attr[RESPAWN_TILE_ATTR] = bot.tile.coordinate
        }
        a.duelPartner = b
        b.duelPartner = a
        a.attack(b)
        b.attack(a)
        lanes += Lane(a, b, keyA, keyB, world.currentCycle, round, rounds)
    }

    private fun resolve(world: World, l: Lane, now: Int) {
        val aDead = l.deadA || l.a.index < 0 || l.a.isDead()
        val bDead = l.deadB || l.b.index < 0 || l.b.isDead()
        val winner = when {
            aDead && !bDead -> l.keyB
            bDead && !aDead -> l.keyA
            else -> null
        }
        val ticks = now - l.start
        val dmgAB = runCatching { l.b.damageMap.getDamageFrom(l.a) }.getOrDefault(0)
        val dmgBA = runCatching { l.a.damageMap.getDamageFrom(l.b) }.getOrDefault(0)
        results += Result(l.keyA, l.keyB, winner, ticks)
        val line = "[BOTDUEL] r${l.round}/${l.rounds} ${l.keyA} vs ${l.keyB}: winner=${winner ?: "draw"} ticks=$ticks " +
            "dmg=$dmgAB/$dmgBA food=${l.a.statFood}/${l.b.statFood} specs=${l.a.statSpecs}/${l.b.statSpecs} " +
            "prayswaps=${l.a.statPraySwaps}/${l.b.statPraySwaps} swaps=${l.a.statGearSwaps}/${l.b.statGearSwaps} " +
            "baits=${l.a.statBaits}/${l.b.statBaits}"
        logger.info { line }
        tell(world, line)
        // Survivors go now; a faller is despawned by BotCombatPlugin.onPlayerDeath after its death sequence.
        if (!aDead && l.a.index >= 0) BotManager.despawn(world, l.a)
        if (!bDead && l.b.index >= 0) BotManager.despawn(world, l.b)
        if (l.a.index >= 0 && aDead && !l.a.isDead()) BotManager.despawn(world, l.a) // respawned faller (edge)
        if (l.b.index >= 0 && bDead && !l.b.isDead()) BotManager.despawn(world, l.b)
    }

    private fun finish(world: World) {
        if (matrix) dumpMatrix(world)
        tell(world, "[botduel] done: ${results.size} bout(s) resolved.")
        matrix = false
    }

    /** Per-loadout W/L/D + win rate, sorted best first — the regression artefact. */
    private fun dumpMatrix(world: World) {
        data class Tally(var w: Int = 0, var l: Int = 0, var d: Int = 0)
        val tally = LinkedHashMap<String, Tally>()
        for (r in results) {
            val ta = tally.getOrPut(r.a) { Tally() }
            val tb = tally.getOrPut(r.b) { Tally() }
            when (r.winner) {
                r.a -> { ta.w++; tb.l++ }
                r.b -> { tb.w++; ta.l++ }
                else -> { ta.d++; tb.d++ }
            }
        }
        val rows = tally.entries.sortedByDescending { (_, t) -> t.w.toDouble() / (t.w + t.l + t.d).coerceAtLeast(1) }
        logger.info { "[BOTDUEL-MATRIX] ${results.size} bouts over ${tally.size} loadouts" }
        for ((key, t) in rows) {
            val total = (t.w + t.l + t.d).coerceAtLeast(1)
            logger.info { "[BOTDUEL-MATRIX] ${key.padEnd(20)} W=${t.w} L=${t.l} D=${t.d} rate=${"%.0f".format(100.0 * t.w / total)}%" }
        }
        val top = rows.take(5).joinToString { (k, t) -> "$k ${t.w}-${t.l}-${t.d}" }
        tell(world, "[botduel] matrix in the server log ([BOTDUEL-MATRIX]); top: $top")
    }

    private fun tell(world: World, text: String) {
        val uid = caller ?: return
        world.getPlayerForUid(uid)?.message(text)
    }

    /** A bout that runs this long (~6 min) is a draw — neither bot could finish. TUNE. */
    private const val DUEL_MAX_TICKS = 600
    /** Concurrent bouts in `::botduel all`. TUNE. */
    private const val LANES = 6
    /** Tiles between lanes along x. TUNE. */
    private const val LANE_SPACING = 16
}
