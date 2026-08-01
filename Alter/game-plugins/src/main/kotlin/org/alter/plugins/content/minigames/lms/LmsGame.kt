package org.alter.plugins.content.minigames.lms

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.HitType
import org.alter.api.Spellbook
import org.alter.api.cfg.Varbit
import org.alter.api.ext.*
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.LMS_BOT_ATTR
import org.alter.game.model.attr.LMS_STASH_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bots.BotLoadout
import org.alter.plugins.content.bots.BotLoadouts
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Last Man Standing** — the authentic OSRS battle royale (distinct from the PK Training Arena, which
 * is a sparring ground). Players queue at the LMS host; a game drops the field onto an instanced copy of
 * the real LMS island ([LmsMaps]), strips everyone's own gear for a temporary starter kit, and lets them
 * scavenge chests for better gear while a **shrinking fog** herds them together. Free-for-all PvP; the
 * last competitor alive wins LMS points. Empty lobbies are filled with [PkBot] competitors so a game runs
 * even at low population.
 *
 * State-machine object (mirrors [org.alter.plugins.content.minigames.wizardtower.WizardTower]): the plugin
 * owns the timer/hooks and delegates here. All player gear handling is crash-safe via [LMS_STASH_ATTR]
 * (the PK-training stash pattern, but a SEPARATE key so the two minigames never collide).
 */
object LmsGame {

    /** TEMPORARY kill-switch: LMS is glitching out, so new games are blocked until it's fixed —
     *  flip to false to re-open. While closed, everything safety-critical stays live: the crash-recovery
     *  gear restore on login, Lisa (with her reward shop and the kit builder), and `::lms`. */
    const val TEMPORARILY_DISABLED = true

    lateinit var world: World

    /** The mainland lobby tile (set by the plugin from the host's location); the instance exit + where
     *  eliminated/finished competitors are returned. */
    var lobbyTile: Tile = Tile(3080, 3500, 0)

    // ───────────────────────────── tunables ─────────────────────────────
    private const val FIELD_SIZE = 8        // competitors per game (real players + bot fill)
    private const val MIN_REAL = 1          // a game starts with this many real queuers (rest are bots)
    private const val LOBBY_COUNTDOWN = 16  // ticks from first queuer to game start (~10s)
    private const val FOG_INTERVAL = 25     // ticks between fog contractions (~15s)
    private const val FOG_SHRINK = 2        // tiles the safe radius loses each contraction
    private const val FOG_MIN = 4           // smallest safe radius (final showdown ring)
    private const val FOG_DAMAGE = 3        // hp lost per tick while outside the fog
    private const val GAME_MAX_TICKS = 2500 // hard safety cap (~25 min) — force-ends a stuck game

    /** Bot loadouts a filler competitor can roll — a spread of NH/melee/range/mage archetypes. */
    private val BOT_POOL: List<BotLoadout> = listOf(
        BotLoadouts.CLASSIC_HYBRID, BotLoadouts.ELITE_NH, BotLoadouts.DHAROK_MID,
        BotLoadouts.RANGE_MID, BotLoadouts.MAGE_MID,
    )

    // ───────────────────────────── lobby ─────────────────────────────
    private val queue = mutableListOf<Player>()
    private var countdown = -1 // -1 = idle
    private var mapRotation = 0

    private val games = mutableListOf<Game>()

    private class Game(
        val map: LmsMap,
        val instance: RaidInstance,
        val fogCenter: Tile,
        val walkable: List<Tile>,
        val players: MutableList<Player>,          // living HUMAN competitors
        val bots: MutableList<PkBot> = mutableListOf(), // living BOT competitors
        /** Native island chests already looted this game (packed tile) — each pays out once. */
        val lootedCrates: MutableSet<Int> = mutableSetOf(),
        /** Eliminations per HUMAN competitor this round (drives the HUD overlay's Kills line). */
        val kills: MutableMap<Player, Int> = mutableMapOf(),
        val startCount: Int,
        var fogRadius: Int,
        var ticks: Int = 0,
        var over: Boolean = false,
    ) {
        /** Everyone still in play (for target-finding + fog + win checks). */
        fun participants(): List<Player> = players + bots
    }

    // ───────────────────────────── queue API ─────────────────────────────

    fun inQueue(p: Player): Boolean = p in queue
    fun inGame(p: Player): Boolean = games.any { p in it.players || p in it.bots }

    /** Join the matchmaking queue (competitive). Starts the lobby countdown on the first queuer. */
    fun join(p: Player) {
        if (TEMPORARILY_DISABLED) {
            p.message("<col=801700>Last Man Standing is temporarily closed for maintenance.</col> Your kit and points are safe.")
            return
        }
        if (inGame(p)) { p.message("You're already in a Last Man Standing game."); return }
        if (inQueue(p)) { p.message("You're already queued. The game starts soon..."); return }
        queue += p
        if (countdown < 0) countdown = LOBBY_COUNTDOWN
        val secs = max(1, countdown * 6 / 10)
        p.message("<col=8f00ff>You join the Last Man Standing lobby.</col> The next game starts in about ${secs}s. (${queue.size} queued)")
    }

    fun leaveQueue(p: Player) {
        if (queue.remove(p)) {
            p.message("You leave the Last Man Standing lobby.")
            if (queue.isEmpty()) countdown = -1
        }
    }

    // ───────────────────────────── tick (driven by the plugin) ─────────────────────────────

    fun tickAll() {
        lobbyTick()
        games.toList().forEach { runCatching { tick(it) }.onFailure { e -> logger.error(e) { "lms tick failed" } } }
    }

    private fun lobbyTick() {
        queue.removeAll { it.index < 0 } // drop disconnected queuers
        if (queue.isEmpty()) { countdown = -1; return }
        if (countdown > 0) { countdown--; return }
        if (countdown == 0) {
            countdown = -1
            val real = queue.filter { it.index >= 0 && !inGame(it) }
            queue.clear()
            if (real.size < MIN_REAL) return
            startGame(real)
        }
    }

    // ───────────────────────────── game start ─────────────────────────────

    private fun startGame(real: List<Player>) {
        val map = LmsMaps.ALL[mapRotation++ % LmsMaps.ALL.size]
        val owner = real.first().uid
        // autoDeallocate=false: the owner WILL die mid-battle-royale; we tear the instance down ourselves
        // by emptying it on game end (RaidInstance doc).
        val instance = RaidInstance.allocate(world, map.sourceArea, exitTile = lobbyTile, owner = owner, autoDeallocate = false)
        if (instance == null) {
            real.forEach { it.message("The Last Man Standing arena is full right now — try again shortly.") }
            return
        }

        val fogCenter = instance.translate(map.center)
        val walkable = reachableFrom(fogCenter, instance.map.area).ifEmpty { reachableFrom(snapWalkable(fogCenter), instance.map.area) }
        if (walkable.isEmpty()) {
            logger.warn { "lms: map '${map.name}' produced NO walkable tiles from center $fogCenter — aborting game" }
            real.forEach { it.message("The arena failed to load — try again shortly.") }
            return
        }

        val game = Game(
            map = map, instance = instance, fogCenter = fogCenter, walkable = walkable,
            players = real.toMutableList(), startCount = FIELD_SIZE, fogRadius = map.startFogRadius,
        )

        // Spread the humans across distance rings, then fill the field with bots on the remaining spots.
        val spots = spreadTiles(walkable, FIELD_SIZE)
        real.forEachIndexed { i, p -> enterHuman(game, p, spots[i % spots.size]) }
        val fill = (FIELD_SIZE - real.size).coerceAtLeast(0)
        for (i in 0 until fill) {
            val tile = spots[(real.size + i) % spots.size]
            spawnBot(game, tile)
        }

        games += game
        game.players.forEach { briefing(it, game) }
        logger.info { "lms: game started on '${map.name}' — ${real.size} players + ${game.bots.size} bots, ${walkable.size} walkable tiles" }
    }

    private fun briefing(p: Player, game: Game) {
        p.message("<col=8f00ff>Last Man Standing — ${game.map.name}!</col> Loot the crates for gear, food and runes, and be the last one alive.")
        p.message("The <col=801700>fog</col> closes in and burns anyone outside the safe zone — keep moving toward the middle. Good luck.")
    }

    // ───────────────────────────── competitors ─────────────────────────────

    /** Stash a human's real gear, deal out their BUILT kit ([LmsKits] — worn gear equipped), level
     *  the field with standardized combat stats (the OSRS LMS rule), and drop them on the island. */
    private fun enterHuman(game: Game, p: Player, tile: Tile) {
        stashIfNeeded(p)
        wipeContainers(p)
        val book = LmsKits.apply(p)
        p.setSpellbook(book)
        standardizeStats(p)
        p.calculateBonuses()
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, p.equipment[EquipmentType.WEAPON.id]?.getDef()?.weaponType ?: 0)
        PlayerInfo(p).syncAppearance()
        p.attr[RESPAWN_TILE_ATTR] = lobbyTile.coordinate // a death respawns them at the lobby, not home
        p.moveTo(tile)
    }

    /** OSRS LMS levels the playing field: every competitor fights the round at the SAME combat stats
     *  regardless of their real levels. Boosted CURRENT levels only — base levels (and therefore XP)
     *  are untouched, and [restoreGear] resets currents back to base on exit. */
    private fun standardizeStats(p: Player) {
        val s = p.getSkills()
        STANDARD_STATS.forEach { (skill, level) -> s.setCurrentLevel(skill, level) }
    }

    private fun spawnBot(game: Game, tile: Tile) {
        val loadout = BOT_POOL[game.bots.size % BOT_POOL.size]
        val bot = BotManager.spawn(world, loadout, tile) ?: run {
            logger.warn { "lms: bot spawn failed on '${game.map.name}'" }; return
        }
        bot.attr[LMS_BOT_ATTR] = true
        bot.ambushEverywhere = true      // relax the brain's wilderness-only guards (island isn't wild)
        bot.homeTile = game.fogCenter    // anchor near the middle
        bot.leashRadius = 64             // island-wide: never disengage / walk "home"
        bot.roamRadius = 0               // the LMS tick drives its targeting; brain roam must not pull it
        bot.username = "Competitor"
        PlayerInfo(bot).syncAppearance()
        game.bots += bot
    }

    // ───────────────────────────── per-game tick ─────────────────────────────

    private fun tick(game: Game) {
        if (game.over) return
        game.ticks++

        // Prune anyone who left the instance without dying (logout/teleport/glitch).
        game.players.removeAll { it.index < 0 || !game.instance.contains(it.tile) }

        // Fog contraction.
        if (game.ticks % FOG_INTERVAL == 0 && game.fogRadius > FOG_MIN) {
            game.fogRadius = (game.fogRadius - FOG_SHRINK).coerceAtLeast(FOG_MIN)
            game.players.forEach { it.message("<col=801700>The fog closes in...</col>") }
        }

        // Fog damage + keep bots fighting.
        game.participants().toList().forEach { pawn ->
            if (pawn.index < 0 || !pawn.isAlive()) return@forEach
            if (chebyshev(pawn.tile, game.fogCenter) > game.fogRadius) {
                pawn.hit(FOG_DAMAGE, type = HitType.HIT)
                if (pawn !is PkBot) pawn.message("<col=ff0000>You're taking damage from the fog!</col>")
            }
        }
        driveBots(game)
        publishHud(game)

        if (game.ticks >= GAME_MAX_TICKS) { endGame(game, winner = null, reason = "The fog consumes the island — no winner."); return }
        checkWinner(game)
    }

    /**
     * Publish the round state to each human's HUD varp — the client's LMS overlay (players remaining,
     * kills, fog countdown) renders from this. Packed: bit0 in-game | bits1-4 remaining | bits5-8 kills
     * | bits9-15 seconds until the next fog contraction (0 once the fog is fully closed).
     */
    private fun publishHud(game: Game) {
        val remaining = (game.players.count { it.index >= 0 && it.isAlive() } +
            game.bots.count { it.index >= 0 && it.isAlive() }).coerceIn(0, 15)
        val fogSecs = if (game.fogRadius <= FOG_MIN) 0
            else ((FOG_INTERVAL - (game.ticks % FOG_INTERVAL)) * 6 / 10).coerceIn(0, 127)
        game.players.forEach { p ->
            if (p.index < 0) return@forEach
            val kills = (game.kills[p] ?: 0).coerceIn(0, 15)
            p.setVarp(HUD_VARP, 1 or (remaining shl 1) or (kills shl 5) or (fogSecs shl 9))
        }
    }

    /** Keep every idle bot swinging at the nearest living competitor (bot or human) — the brain only
     *  aggros humans, so a bot-vs-bot royale needs the engine to assign targets or it never resolves. */
    private fun driveBots(game: Game) {
        val alive = game.participants().filter { it.index >= 0 && it.isAlive() }
        game.bots.toList().forEach { bot ->
            if (bot.index < 0 || !bot.isAlive()) return@forEach
            if (bot.getCombatTarget() != null) return@forEach
            val prey = alive.filter { it !== bot }.minByOrNull { it.tile.getDistance(bot.tile) } ?: return@forEach
            bot.attack(prey)
        }
    }

    private fun checkWinner(game: Game) {
        val humans = game.players.count { it.index >= 0 && it.isAlive() }
        val bots = game.bots.count { it.index >= 0 && it.isAlive() }
        // No humans left → nobody's watching a bot royale; end it (the humans all lost).
        if (humans == 0) { endGame(game, winner = null, reason = "The bots claim the island."); return }
        // Exactly one competitor and it's a human → winner.
        if (humans + bots == 1) {
            val winner = game.players.firstOrNull { it.index >= 0 && it.isAlive() }
            endGame(game, winner = winner, reason = null)
        }
    }

    // ───────────────────────────── crates ─────────────────────────────

    /** A native island chest was opened. Returns true if the opener is in an LMS game (so the plugin
     *  consumes the click); false for any same-id object outside a game. Each chest pays once per game. */
    fun onCrateOpen(p: Player, objTile: Tile): Boolean {
        val game = games.firstOrNull { p in it.players } ?: return false
        val key = pack(objTile)
        if (key in game.lootedCrates) { p.message("This chest has already been looted."); return true }
        game.lootedCrates += key
        deliver(p, LmsLoot.CRATE)
        p.message("<col=8f00ff>You crack open the chest</col> and grab what's inside!")
        return true
    }

    // ───────────────────────────── death / elimination ─────────────────────────────

    /** Runs at pre-death (killer + death tile still intact): pay the killer a scavenge pile + kill point.
     *  Returns true if [p] is an LMS competitor. */
    fun onPreDeath(p: Player): Boolean {
        val game = games.firstOrNull { p in it.players || p in it.bots } ?: return false
        val killer = p.attr[KILLER_ATTR]?.get() as? Player
        val humanKiller = killer?.takeIf { it !is PkBot && (it in game.players) }
        // Scavenge pile drops at the corpse to the (human) killer; bot kills drop nothing.
        if (humanKiller != null) {
            deliver(humanKiller, LmsLoot.SCAVENGE, at = p.tile)
            humanKiller.addPoints(PointKind.LMS, KILL_POINTS)
            game.kills[humanKiller] = (game.kills[humanKiller] ?: 0) + 1
            humanKiller.message("<col=00b000>You eliminated ${p.username}!</col> (+$KILL_POINTS LMS points)")
        }
        return true
    }

    /** Runs at death (after the engine respawn): eliminate [p] from their game, restore a human's real
     *  gear, and resolve the game if it's down to a winner. Returns true if [p] was an LMS competitor. */
    fun onDeath(p: Player): Boolean {
        val game = games.firstOrNull { p in it.players || p in it.bots } ?: return false
        val bot = p as? PkBot
        if (bot != null) {
            game.bots.remove(bot)
            BotManager.despawn(world, bot)
        } else {
            game.players.remove(p)
            // Your rank = everyone still alive, plus you. (Last one standing = #1.)
            val place = game.players.count { it.index >= 0 } + game.bots.count { it.index >= 0 && it.isAlive() } + 1
            restoreGear(p)
            p.moveTo(lobbyTile)
            p.message("<col=ff0000>You were eliminated!</col> You placed #$place of ${game.startCount}. Win to earn LMS points.")
        }
        checkWinner(game)
        return true
    }

    // ───────────────────────────── end / teardown ─────────────────────────────

    private fun endGame(game: Game, winner: Player?, reason: String?) {
        if (game.over) return
        game.over = true

        if (winner != null && winner.index >= 0) {
            winner.addPoints(PointKind.LMS, WIN_POINTS)
            restoreGear(winner)
            winner.moveTo(lobbyTile)
            winner.message("<col=ffae00>You are the Last Man Standing!</col> (+$WIN_POINTS LMS points)")
            broadcast("<col=8f00ff>[LMS]</col> ${winner.username} is the Last Man Standing on ${game.map.name}!")
        } else if (reason != null) {
            game.players.forEach { it.message("<col=801700>$reason</col>") }
        }

        // Return any stragglers and despawn bots. Emptying the instance lets the allocator's idle
        // scan deallocate it (we opted out of owner-death dealloc); its chests vanish with it.
        game.players.toList().forEach { if (it.index >= 0) { restoreGear(it); it.moveTo(lobbyTile) } }
        game.bots.toList().forEach { runCatching { BotManager.despawn(world, it) } }
        game.players.clear(); game.bots.clear()
        games.remove(game)
    }

    // ───────────────────────────── login / logout crash safety ─────────────────────────────

    /** A stash blob present at login means a JVM death left this player owing a real-gear restore. */
    fun onLogin(p: Player) {
        if (p.attr[LMS_STASH_ATTR] != null) {
            restoreGear(p)
            p.moveTo(lobbyTile)
            p.message("<col=801700>Your gear was returned from Last Man Standing.</col>")
        }
    }

    /** Clean logout mid-game: restore real gear (so the save writes it) and drop them from the game. */
    fun onLogout(p: Player) {
        leaveQueue(p)
        val game = games.firstOrNull { p in it.players } ?: run {
            if (p.attr[LMS_STASH_ATTR] != null) restoreGear(p)
            return
        }
        game.players.remove(p)
        restoreGear(p)
        checkWinner(game)
    }

    // ───────────────────────────── combat gate hook ─────────────────────────────

    /** Two players in the SAME game may fight anywhere on the island (used by Combat.canEngage). */
    fun sameGame(a: Player, b: Player): Boolean =
        games.any { (a in it.players || a in it.bots) && (b in it.players || b in it.bots) }

    // ───────────────────────────── loot delivery ─────────────────────────────

    private fun deliver(p: Player, table: DropTable, at: Tile = p.tile) {
        table.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            val added = p.inventory.add(item = id, amount = drop.amount, assureFullInsertion = false)
            val leftover = drop.amount - added.completed
            if (leftover > 0) world.spawn(GroundItem(id, leftover, at, p))
        }
    }

    // ─── real-gear stash / restore (crash-safe bson blob — the PK-training pattern, own key) ───

    private fun stashIfNeeded(p: Player) {
        if (p.attr[LMS_STASH_ATTR] != null) return
        val doc = Document()
        doc.append("inv", itemsDoc(p.inventory.rawItemsSnapshot()))
        doc.append("equip", itemsDoc(p.equipment.rawItemsSnapshot()))
        doc.append("book", p.getSpellbook().id)
        p.attr[RESPAWN_TILE_ATTR]?.let { doc.append("respawn", it) }
        p.attr[LMS_STASH_ATTR] = doc.toJson()
    }

    private fun restoreGear(p: Player) {
        val blob = p.attr[LMS_STASH_ATTR] ?: return
        wipeContainers(p)
        runCatching {
            val doc = Document.parse(blob)
            itemsFrom(doc, "inv").forEach { (idx, it) -> if (idx < p.inventory.capacity) p.inventory[idx] = it }
            itemsFrom(doc, "equip").forEach { (idx, it) -> if (idx < p.equipment.capacity) p.equipment[idx] = it }
            val book = Spellbook.values.firstOrNull { it.id == doc.getInteger("book", 0) } ?: Spellbook.NORMAL
            p.setSpellbook(book)
            if (doc.containsKey("respawn")) p.attr[RESPAWN_TILE_ATTR] = doc.getInteger("respawn")
            else p.attr.remove(RESPAWN_TILE_ATTR)
        }.onFailure { logger.error(it) { "lms: failed to restore stash for ${p.username} — blob kept for retry" }; return }
        // Undo the round's standardized stats: every boosted current level back to its real base.
        val s = p.getSkills()
        STANDARD_STATS.forEach { (skill, _) -> s.setCurrentLevel(skill, s.getBaseLevel(skill)) }
        p.calculateBonuses()
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, p.equipment[EquipmentType.WEAPON.id]?.getDef()?.weaponType ?: 0)
        PlayerInfo(p).syncAppearance()
        p.setVarp(HUD_VARP, 0) // hide the LMS overlay — restoreGear runs on EVERY way out of a game
        p.attr.remove(LMS_STASH_ATTR)
    }

    private fun wipeContainers(p: Player) {
        for (i in 0 until p.inventory.capacity) p.inventory[i] = null
        for (i in 0 until p.equipment.capacity) p.equipment[i] = null
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

    private fun org.alter.game.model.container.ItemContainer.rawItemsSnapshot(): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        for (i in 0 until capacity) this[i]?.let { out[i] = Item(it.id, it.amount) }
        return out
    }

    // ───────────────────────────── geometry helpers ─────────────────────────────

    private fun broadcast(msg: String) {
        world.players.forEach { if (it.index >= 0 && it.entityType.isHumanControlled) it.message(msg) }
    }

    private fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private fun pack(t: Tile): Int = (t.x shl 16) or t.z

    /** Pick [n] tiles spread across the BFS-ordered walkable list (rings from the centre → good spread). */
    private fun spreadTiles(tiles: List<Tile>, n: Int): List<Tile> {
        if (tiles.isEmpty()) return emptyList()
        if (tiles.size <= n) return tiles
        return List(n) { tiles[it * tiles.size / n] }
    }

    private fun snapWalkable(t: Tile): Tile = world.snapToWalkable(t)

    /** Walkable tiles reachable on foot from [seed], bounded to [bounds] — BFS via the step validator so
     *  it can never leak through walls or into void (the WizardTower flood-fill). BFS order = distance
     *  rings from the seed. */
    private fun reachableFrom(seed: Tile, bounds: Area, limit: Int = 400): List<Tile> {
        val dirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
        val start = if (world.collision.isClipped(seed)) snapWalkable(seed) else seed
        val visited = linkedSetOf(start)
        val q = ArrayDeque(listOf(start))
        while (q.isNotEmpty() && visited.size < limit) {
            val cur = q.removeFirst()
            for (dir in dirs) {
                val next = cur.step(dir)
                if (next in visited || !bounds.contains(next.x, next.z)) continue
                val ok = world.stepValidator.canTravel(
                    level = cur.height, x = cur.x, z = cur.z,
                    offsetX = dir.getDeltaX(), offsetZ = dir.getDeltaZ(), size = 1,
                )
                if (!ok) continue
                visited += next
                q += next
            }
        }
        return visited.toList()
    }

    // ───────────────────────────── constants: crate object + point payouts ─────────────────────────────

    /** The island's native loot chest (id 29069, scattered through the village houses — the authentic
     *  OSRS scavenge loop). We loot them where they stand; no extra copies are spawned. */
    const val CRATE_KEY = "object.chest_29069"
    const val CRATE_OBJ = 29069

    /** The standardized round stats (OSRS LMS levels the field): skill index -> current level. */
    private val STANDARD_STATS = listOf(
        org.alter.api.Skills.ATTACK to 99,
        org.alter.api.Skills.STRENGTH to 99,
        org.alter.api.Skills.DEFENCE to 99,
        org.alter.api.Skills.HITPOINTS to 99,
        org.alter.api.Skills.RANGED to 99,
        org.alter.api.Skills.MAGIC to 99,
        org.alter.api.Skills.PRAYER to 52, // protect prayers + Smite, no Piety-tier overheads
    )

    private const val KILL_POINTS = 15
    private const val WIN_POINTS = 100

    /** The client LMS-overlay varp (see the varp map in the roak-overlays notes: 4600-4607 taken). */
    const val HUD_VARP = 4608
}
