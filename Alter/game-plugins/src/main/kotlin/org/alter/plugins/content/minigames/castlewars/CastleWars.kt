package org.alter.plugins.content.minigames.castlewars

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.cfg.Varbit
import org.alter.api.ext.*
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.CW_BOT_ATTR
import org.alter.game.model.attr.CW_CAPTURES_ATTR
import org.alter.game.model.attr.CW_STASH_ATTR
import org.alter.game.model.attr.CW_WINS_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bots.BotLoadout
import org.alter.plugins.content.bots.BotLoadouts
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.bson.Document
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Castle Wars** — the classic capture-the-flag team minigame, played on the REAL Castle Wars arena
 * shipped in the rev-228 cache (Saradomin's castle in the south-east, Zamorak's in the north-west, the
 * lobby with its three god portals at ~2440,3089).
 *
 * Design goals for this server (low-pop, pre-launch):
 *  - **Always a full war.** One real player is enough to start a game — both teams are topped up to
 *    [teamSize] with [PkBot] filler soldiers. Every human who joins mid-game simply displaces a bot,
 *    so a 50-slot battle runs whether 1 or 50 real players show up.
 *  - **Zero item risk.** The whole arena is a registered safe-death zone; humans respawn in their
 *    castle's spawn room, bots respawn on a short delay (and carry [CW_BOT_ATTR] so the bot-combat
 *    death faucet is skipped — endless respawns must never print PK kits).
 *  - **Authentic loop.** Take the enemy standard off their keep roof ("Capture"), run it home, touch
 *    your own standard to score. Carrier death drops the flag where they fell; the owning team clicks
 *    it to return it instantly, the enemy clicks it to pick it up and keep running.
 *
 * State-machine object driven by [CastleWarsPlugin] (the LmsGame pattern): the plugin owns the timer
 * and all click/death/login hooks and delegates here.
 */
object CastleWars {

    lateinit var world: World

    // ───────────────────────────── tunables ─────────────────────────────
    /** Soldiers per side (humans + bot fill). 10 keeps the front line breakable — 25v25 play-tested
     *  as a stalemate wall (and ticked at ~3ms). Tune live via ::cwbots. */
    var teamSize = 10
    private const val GAME_TICKS = 2000        // 20 minutes
    private const val LOBBY_COUNTDOWN = 100    // ~60s from first queuer to kickoff
    private const val BOT_RESPAWN_TICKS = 17   // ~10s dead-time for a felled bot
    private const val BOT_AGGRO_RANGE = 12     // how far a filler looks for someone to hit
    private const val COINS = 995
    private const val WIN_COINS = 50_000
    private const val DRAW_COINS = 20_000
    private const val LOSS_COINS = 10_000
    private const val CAPTURE_COINS = 25_000   // to the capturer, on top of the game payout

    /** Filler archetypes — the mid-tier spread (elites would melt a casual team fight). */
    private val BOT_POOL: List<BotLoadout> = listOf(
        BotLoadouts.CLASSIC_HYBRID, BotLoadouts.DHAROK_MID, BotLoadouts.RANGE_MID, BotLoadouts.MAGE_MID,
    )

    // ───────────────────────────── the map (verified via mapDump r9520/r9776) ─────────────────────────────
    /** The whole battlefield (castles + field). Deliberately EXCLUDES the lobby east of x2433. */
    val ARENA = Area(2368, 3072, 2433, 3135)
    val LOBBY_TILE = Tile(2440, 3092, 0)

    // Items
    const val SARA_CLOAK = 4041     // "Hooded cloak" (blue) — cape slot
    const val ZAM_CLOAK = 4042      // "Hooded cloak" (red)
    const val SARA_BANNER = 4037    // "Saradomin banner" — weapon slot, the Saradomin FLAG
    const val ZAM_BANNER = 4039     // "Zamorak banner" — the Zamorak FLAG
    const val BANDAGES = 4049
    const val EXPLOSIVE_POTION = 4045
    const val BARRICADE_ITEM = 4053
    const val ROCK_ITEM = 4043      // "Rock" — catapult ammo (catapults are v2; flavour for now)
    const val MAX_BARRICADES = 10   // per team, OSRS cap

    /** The attackable barricade NPCs (the real OSRS ones): normal variants per team. */
    const val SARA_BARRICADE_NPC = 5722
    const val ZAM_BARRICADE_NPC = 5724

    // Objects (all present in the shipped cache map)
    const val SARA_PORTAL = 4387    // lobby, "Enter"
    const val ZAM_PORTAL = 4388     // lobby, "Enter"
    const val GUTHIX_PORTAL = 4408  // lobby, "Enter" — auto-balance
    const val SARA_EXIT_PORTAL = 4406 // sara spawn room, "Leave"
    const val ZAM_EXIT_PORTAL = 4407  // zam spawn room, "Leave"
    const val SARA_STANDARD = 4902  // "Capture" — Saradomin's flag stand (their keep roof)
    const val ZAM_STANDARD = 4903   // Zamorak's
    const val SARA_BARRIER = 4469   // "Pass" — seals the sara spawn room
    const val ZAM_BARRIER = 4470
    const val SCOREBOARD = 4484     // lobby, "View"

    /**
     * The supply tables ("Take-from"/"Take-5") and what each hands out. The six ground-floor ids are
     * distinct table types in OSRS; the two mappings we can PROVE from the cache/wiki are 4458
     * (spawn-room bandage table) and 4463+40432 (they share tabletop model 4434, and 40432 is the
     * 2021 "potion table in the starting room" addition → explosive potions). The rest are assigned
     * so every castle has plentiful bandages plus the barricade/rock war kit.
     */
    val SUPPLY_TABLES: Map<Int, Pair<Int, String>> = mapOf(
        4458 to (BANDAGES to "bandages"),
        4459 to (BANDAGES to "bandages"),
        4460 to (BANDAGES to "bandages"),
        4461 to (BARRICADE_ITEM to "barricades"),
        4462 to (BARRICADE_ITEM to "barricades"),
        4463 to (EXPLOSIVE_POTION to "explosive potions"),
        40432 to (EXPLOSIVE_POTION to "explosive potions"),
        4464 to (ROCK_ITEM to "catapult rocks"),
    )
    val STAIRCASES = intArrayOf(4415, 4417, 4418, 4419, 4420) // castle stairs, single "Climb" option
    val SHAFT_LADDERS_UP = intArrayOf(6280, 6281)   // keep level 1 -> 2, "Climb-up"
    val LADDERS_DOWN = intArrayOf(4911)             // generic "Climb-down" (also our spawned roof exits)
    val CAVE_LADDERS = intArrayOf(4912)             // to the collapsed tunnels — blocked in v1
    val TRAPDOORS = intArrayOf(4471, 4472)          // keep level 2, "Open" — up to the roof / back down

    enum class Team(val label: String, val colour: String, val cloak: Int, val banner: Int) {
        SARADOMIN("Saradomin", "1c5ba8", SARA_CLOAK, SARA_BANNER),
        ZAMORAK("Zamorak", "a81c1c", ZAM_CLOAK, ZAM_BANNER);

        val other: Team get() = if (this == SARADOMIN) ZAMORAK else SARADOMIN
    }

    /** Where each team's dead (and fresh joiners) appear — the sealed spawn room on keep level 1. */
    private val RESPAWN = mapOf(
        Team.SARADOMIN to Tile(2427, 3078, 1),
        Team.ZAMORAK to Tile(2372, 3130, 1),
    )

    // The REAL underground waiting rooms (region 9620) — queuers wait here, OSRS-style.
    const val SARA_WAIT_PORTAL = 4389 // waiting-room portal, "Exit" (back to the lobby)
    const val ZAM_WAIT_PORTAL = 4390
    private val WAIT_ROOM = mapOf(
        Team.SARADOMIN to Tile(2379, 9488, 0),
        Team.ZAMORAK to Tile(2420, 9527, 0),
    )
    /** Rough bounds of the whole underground level (waiting rooms + tunnels). */
    val UNDERGROUND = Area(2368, 9472, 2433, 9535)

    // The tunnel network — the flank route under the front line. Surface trapdoors mid-field drop
    // into the central plaza; each castle also has a side cave ladder. y offset is the OSRS +6400.
    const val SURFACE_TRAPDOOR = 1579   // "Open" — at (2399,3099) and (2400,3108)
    const val TUNNEL_LADDER = 17387     // "Climb-up" — underground, back to the surface
    const val TUNNEL_ROCKS = 4437       // "Mine" — the rubble sealing the outer tunnels

    // Client countdown overlay (custom client plugin `lofcwtimer`): 4620/4621 = seconds + mode,
    // 4622/4623 = live scores. Mode 0 hides the box, 1 = waiting for a war, 2 = fighting in one.
    private const val VARP_CW_SECONDS = 4620
    private const val VARP_CW_MODE = 4621
    private const val VARP_CW_SARA = 4622
    private const val VARP_CW_ZAM = 4623

    /** Home tile of each team's standard (keep roof, level 3). A "Capture" click here is either a
     *  flag TAKE (enemy) or a SCORE (own team carrying the enemy banner). */
    private val FLAG_HOME = mapOf(
        Team.SARADOMIN to Tile(2429, 3074, 3),
        Team.ZAMORAK to Tile(2370, 3133, 3),
    )

    /** Roof exit ladders we spawn once at boot (the cache roofs have no way back down). */
    val ROOF_LADDERS = listOf(Tile(2428, 3075, 3), Tile(2371, 3132, 3))

    /** Field-level castle gates — the bot objectives (attackers push the enemy gate). */
    private val GATE = mapOf(
        Team.SARADOMIN to Tile(2426, 3090, 0),
        Team.ZAMORAK to Tile(2373, 3117, 0),
    )

    /** Where a team's fillers muster at game start, on the field in front of their own castle. */
    private val MUSTER = mapOf(
        Team.SARADOMIN to Tile(2420, 3095, 0),
        Team.ZAMORAK to Tile(2380, 3112, 0),
    )

    // Gate geometry for the bot breachers: the two door tiles + closed→open id mapping (mirrors
    // the double-doors.json registration, so a bot breach and a player click stay in sync).
    private val GATE_TILES = mapOf(
        Team.SARADOMIN to listOf(Tile(2426, 3088, 0), Tile(2427, 3088, 0)),
        Team.ZAMORAK to listOf(Tile(2372, 3119, 0), Tile(2373, 3119, 0)),
    )
    private val GATE_OPEN_ID = mapOf(4423 to 4425, 4424 to 4426, 4427 to 4429, 4428 to 4430)

    /** Just outside / just inside each castle's front gate (waypoints for the assault push). */
    private val GATE_OUTSIDE = mapOf(
        Team.SARADOMIN to Tile(2426, 3091, 0),
        Team.ZAMORAK to Tile(2373, 3116, 0),
    )
    private val GATE_INSIDE = mapOf(
        Team.SARADOMIN to Tile(2426, 3085, 0),
        Team.ZAMORAK to Tile(2373, 3122, 0),
    )

    /** The mid-field tunnel mouths (trapdoor down on your side, ladder up behind theirs). */
    private val TRAPDOOR_AT = mapOf(
        Team.SARADOMIN to Tile(2399, 3099, 0),
        Team.ZAMORAK to Tile(2400, 3108, 0),
    )

    // ───────────────────────────── state ─────────────────────────────
    private class Queuer(val player: Player, val team: Team)

    private val queue = mutableListOf<Queuer>()
    private var countdown = -1

    private sealed class FlagState {
        object Home : FlagState()
        class Carried(val carrier: Player) : FlagState()
        class Dropped(val obj: DynamicObject) : FlagState()
    }

    private class Game(
        val humans: MutableMap<Team, MutableList<Player>> = mutableMapOf(
            Team.SARADOMIN to mutableListOf(), Team.ZAMORAK to mutableListOf(),
        ),
        val bots: MutableMap<Team, MutableList<PkBot>> = mutableMapOf(
            Team.SARADOMIN to mutableListOf(), Team.ZAMORAK to mutableListOf(),
        ),
        val scores: MutableMap<Team, Int> = mutableMapOf(Team.SARADOMIN to 0, Team.ZAMORAK to 0),
        /** flags[T] = the state of TEAM T's OWN flag (home on their roof / carried by the enemy / dropped). */
        val flags: MutableMap<Team, FlagState> = mutableMapOf(
            Team.SARADOMIN to FlagState.Home, Team.ZAMORAK to FlagState.Home,
        ),
        val botRespawns: MutableList<Pair<Int, Team>> = mutableListOf(), // (dueTick, team)
        val barricades: MutableMap<Team, MutableList<org.alter.game.model.entity.Npc>> = mutableMapOf(
            Team.SARADOMIN to mutableListOf(), Team.ZAMORAK to mutableListOf(),
        ),
        val missions: MutableMap<PkBot, Mission> = mutableMapOf(),
        val spawnSeq: MutableMap<Team, Int> = mutableMapOf(Team.SARADOMIN to 0, Team.ZAMORAK to 0),
        var ticks: Int = 0,
        var over: Boolean = false,
    ) {
        fun teamOf(p: Player): Team? = Team.values().firstOrNull { p in humans[it]!! || p in bots[it]!! }
        fun allHumans(): List<Player> = humans.values.flatten()
        fun combatants(team: Team): List<Player> = humans[team]!! + bots[team]!!
    }

    private var game: Game? = null // the real arena hosts ONE game at a time
    var lastResult: String = "No games played since the last restart."
        private set

    // ───────────────────────────── public queries (Combat.canEngage + plugin) ─────────────────────────────

    fun inGame(p: Player): Boolean = game?.teamOf(p) != null
    fun teamOf(p: Player): Team? = game?.teamOf(p)

    fun sameGame(a: Player, b: Player): Boolean {
        val g = game ?: return false
        return g.teamOf(a) != null && g.teamOf(b) != null
    }

    fun sameTeam(a: Player, b: Player): Boolean {
        val g = game ?: return false
        val ta = g.teamOf(a) ?: return false
        return ta == g.teamOf(b)
    }

    // ───────────────────────────── joining ─────────────────────────────

    /** A lobby portal was entered. [pick] null = Guthix (balances you onto the emptier side). */
    fun join(p: Player, pick: Team?) {
        if (inGame(p)) { p.message("You're already fighting in this war!"); return }
        if (queue.any { it.player === p }) { p.message("You're already waiting for the next war."); return }
        val g = game
        if (g != null && !g.over) { joinRunning(g, p, pick); return }
        // Guthix balances by queue counts; team portals honour the pick.
        val team = pick ?: run {
            val sara = queue.count { it.team == Team.SARADOMIN }
            val zam = queue.count { it.team == Team.ZAMORAK }
            if (sara <= zam) Team.SARADOMIN else Team.ZAMORAK
        }
        queue += Queuer(p, team)
        if (countdown < 0) countdown = LOBBY_COUNTDOWN
        p.moveTo(WAIT_ROOM[team]!!)
        val secs = max(1, countdown * 6 / 10)
        p.message("<col=${team.colour}>You will fight for ${team.label}.</col> The war begins in about ${secs}s. (${queue.size} waiting)")
        p.message("Step through the portal to leave the waiting room.")
    }

    /** The waiting-room "Exit" portal: out of the queue and back up to the lobby. */
    fun onExitWaitingRoom(p: Player) {
        queue.removeAll { it.player === p }
        if (queue.isEmpty()) countdown = -1
        clearTimer(p)
        p.moveTo(LOBBY_TILE)
        p.message("You step back out to the lobby.")
    }

    /** Mid-game join: a human displaces a filler bot on their side, so the war never over-fills. */
    private fun joinRunning(g: Game, p: Player, pick: Team?) {
        // Guthix balances by HUMAN count (bots are interchangeable).
        val team = pick ?: if (g.humans[Team.SARADOMIN]!!.size <= g.humans[Team.ZAMORAK]!!.size) {
            Team.SARADOMIN
        } else {
            Team.ZAMORAK
        }
        if (g.humans[team]!!.size >= teamSize) {
            p.message("${team.label}'s ranks are full of real soldiers — try the other portal!")
            return
        }
        // One bot makes room (if the side is at capacity with fillers).
        g.bots[team]!!.removeFirstOrNull()?.let { despawnBot(it) }
        enterHuman(g, p, team)
        p.message("<col=801700>The battle is already raging — you charge in for ${team.label}!</col>")
        broadcast(g, "<col=${team.colour}>${p.username} joins ${team.label}'s ranks!</col>")
    }

    // ───────────────────────────── tick (driven by the plugin, every cycle) ─────────────────────────────

    fun tickAll() {
        lobbyTick()
        game?.let { g -> runCatching { tick(g) }.onFailure { e -> logger.error(e) { "castlewars tick failed" } } }
        runCatching { publishTimers() }.onFailure { e -> logger.error(e) { "castlewars timer publish failed" } }
    }

    /** Admin lever (::cwstart): fire the queued game on the next tick. */
    fun forceStart() {
        if (queue.isNotEmpty()) countdown = 0
    }

    private fun lobbyTick() {
        queue.removeAll { it.player.index < 0 }
        if (queue.isEmpty()) { countdown = -1; return }
        if (countdown > 0) {
            countdown--
            when (countdown) {
                50 -> waitersMessage("<col=801700>The war begins in 30 seconds...</col>")
                17 -> waitersMessage("<col=801700>The war begins in 10 seconds — to arms!</col>")
            }
            return
        }
        if (countdown == 0) {
            if (game != null) { countdown = 50; return } // arena busy — re-check in ~30s
            countdown = -1
            val ready = queue.toList()
            queue.clear()
            if (ready.isEmpty()) return
            startGame(ready)
        }
    }

    private fun waitersMessage(msg: String) {
        queue.forEach { if (it.player.index >= 0) it.player.message(msg) }
    }

    // ───────────────────────────── client countdown overlay ─────────────────────────────

    /** Feed the on-screen timer box (above the chatbox on the custom client) via varps. */
    private fun publishTimers() {
        // Queuers: time until the next war (if the arena is busy, until the current one ends).
        if (queue.isNotEmpty()) {
            val g = game
            val secs = when {
                g != null && !g.over -> ((GAME_TICKS - g.ticks) * 6 / 10).coerceAtLeast(0) + 6
                countdown >= 0 -> countdown * 6 / 10
                else -> 0
            }
            queue.forEach { q ->
                if (q.player.index < 0) return@forEach
                q.player.setVarpIfChanged(VARP_CW_MODE, 1)
                q.player.setVarpIfChanged(VARP_CW_SECONDS, secs)
            }
        }
        // Combatants: war time remaining + the live score.
        game?.let { g ->
            if (g.over) return@let
            val secs = ((GAME_TICKS - g.ticks) * 6 / 10).coerceAtLeast(0)
            g.allHumans().forEach { p ->
                if (p.index < 0) return@forEach
                p.setVarpIfChanged(VARP_CW_MODE, 2)
                p.setVarpIfChanged(VARP_CW_SECONDS, secs)
                p.setVarpIfChanged(VARP_CW_SARA, g.scores[Team.SARADOMIN]!!)
                p.setVarpIfChanged(VARP_CW_ZAM, g.scores[Team.ZAMORAK]!!)
            }
        }
    }

    private fun clearTimer(p: Player) {
        if (p.index < 0) return
        p.setVarpIfChanged(VARP_CW_MODE, 0)
        p.setVarpIfChanged(VARP_CW_SECONDS, 0)
    }

    private fun Player.setVarpIfChanged(varp: Int, value: Int) {
        if (getVarp(varp) != value) setVarp(varp, value)
    }

    // ───────────────────────────── game start ─────────────────────────────

    private fun startGame(ready: List<Queuer>) {
        val g = Game()

        // Teams were locked in at the waiting-room portal.
        ready.forEach { q -> enterHuman(g, q.player, q.team) }

        // Fill both rosters with soldiers.
        Team.values().forEach { team ->
            val fill = (teamSize - g.humans[team]!!.size).coerceAtLeast(0)
            repeat(fill) { i -> spawnBot(g, team, i) }
        }

        game = g
        val sara = g.combatants(Team.SARADOMIN).size
        val zam = g.combatants(Team.ZAMORAK).size
        g.allHumans().forEach { briefing(it) }
        Announce.broadcast(world, "[Castle Wars] The battle horns sound — Saradomin ($sara) vs Zamorak ($zam)! Join at the lobby portals.")
        logger.info { "castlewars: game started — ${g.humans[Team.SARADOMIN]!!.size}+${g.bots[Team.SARADOMIN]!!.size} vs ${g.humans[Team.ZAMORAK]!!.size}+${g.bots[Team.ZAMORAK]!!.size}" }
    }

    private fun briefing(p: Player) {
        p.message("<col=801700>Castle Wars has begun!</col> Steal the enemy standard from the top of their keep and carry it back to your own standard to score.")
        p.message("Bandage tables in the castles heal you. Deaths are safe — you'll respawn in your spawn room. The war lasts 20 minutes.")
    }

    /** Dress a human for war: team cloak on (real cape + respawn stashed crash-safe), off to the spawn room. */
    private fun enterHuman(g: Game, p: Player, team: Team) {
        stashIfNeeded(p)
        g.humans[team]!! += p
        val cape = p.equipment[EquipmentType.CAPE.id]
        if (cape != null) {
            val added = p.inventory.add(item = cape.id, amount = cape.amount, assureFullInsertion = false)
            if (added.completed < cape.amount) world.spawn(GroundItem(cape.id, cape.amount - added.completed, LOBBY_TILE, p))
        }
        p.equipment[EquipmentType.CAPE.id] = Item(team.cloak)
        p.calculateBonuses()
        PlayerInfo(p).syncAppearance()
        p.attr[RESPAWN_TILE_ATTR] = RESPAWN[team]!!.coordinate
        p.setCurrentHp(p.getMaxHp())
        p.moveTo(RESPAWN[team]!!)
    }

    private fun spawnBot(g: Game, team: Team, seed: Int) {
        val loadout = BOT_POOL[(g.bots[team]!!.size + seed) % BOT_POOL.size]
        val tile = snapWalkable(jitter(MUSTER[team]!!, 6, seed))
        val bot = BotManager.spawn(world, loadout, tile) ?: run {
            logger.warn { "castlewars: bot spawn failed for ${team.label}" }; return
        }
        bot.attr[CW_BOT_ATTR] = true
        bot.ambushEverywhere = true       // the arena is a safe zone — relax the brain's wild-only guards
        bot.homeTile = MUSTER[team]!!
        bot.leashRadius = 96              // arena-wide: never disengage and walk "home"
        bot.roamRadius = 0                // WE drive its movement; brain roam must not pull it around
        bot.username = if (team == Team.SARADOMIN) "Saradominist" else "Zamorakian"
        bot.equipment[EquipmentType.CAPE.id] = Item(team.cloak)
        PlayerInfo(bot).syncAppearance()
        g.bots[team]!! += bot

        // Every soldier gets a war job (stable rotation, so replacements keep the army balanced).
        val seq = g.spawnSeq[team]!!
        g.spawnSeq[team] = seq + 1
        val role = ROLE_TABLE[seq % ROLE_TABLE.size]
        if (role == BotRole.DEFEND) bot.homeTile = GATE_INSIDE[team]!!
        g.missions[bot] = buildMission(role, team)
    }

    private fun despawnBot(bot: PkBot) {
        runCatching { BotManager.despawn(world, bot) }
            .onFailure { logger.warn { "castlewars: bot despawn failed: ${it.message}" } }
    }

    // ───────────────────────────── per-game tick ─────────────────────────────

    private fun tick(g: Game) {
        if (g.over) return
        g.ticks++
        val t0 = System.nanoTime()

        // Humans who vanished (logout is hooked separately) or teleported out have left the war.
        // The underground tunnels are part of the battlefield — flanking is not desertion.
        g.allHumans().toList().forEach { p ->
            if (p.index < 0) { removeHuman(g, p, restore = false); return@forEach }
            val inBounds = ARENA.contains(p.tile.x, p.tile.z) || UNDERGROUND.contains(p.tile.x, p.tile.z)
            if (!inBounds && !p.isDead()) {
                p.message("<col=801700>You've deserted the battle!</col>")
                leave(p, toLobby = false)
            }
        }

        // Bot reinforcements.
        g.botRespawns.toList().forEach { (due, team) ->
            if (g.ticks >= due) {
                g.botRespawns.remove(due to team)
                if (g.combatants(team).size < teamSize) spawnBot(g, team, g.ticks)
            }
        }

        enforceKit(g)
        flagIntegrity(g)
        driveBots(g)

        // Prune destroyed barricades so the per-team cap frees up.
        Team.values().forEach { team ->
            g.barricades[team]!!.removeAll { it.index < 0 || it.isDead() }
        }

        // Periodic score call + time warnings.
        when {
            g.ticks % 200 == 0 -> broadcast(g, scoreLine(g))
            g.ticks == GAME_TICKS - 100 -> broadcast(g, "<col=801700>One minute remains!</col>")
        }
        if (g.ticks >= GAME_TICKS) { endGame(g); return }
        // No humans left → nobody is watching; call it off.
        if (g.allHumans().isEmpty()) { abandon(g); return }

        val ms = (System.nanoTime() - t0) / 1_000_000.0
        if (ms > 2.0 && g.ticks % 10 == 0) logger.warn { "castlewars: slow tick ${"%.1f".format(ms)}ms (${g.combatants(Team.SARADOMIN).size + g.combatants(Team.ZAMORAK).size} combatants)" }
    }

    /** Team cloak stays on; a flag carrier's banner stays wielded (or the flag drops where they stand). */
    private fun enforceKit(g: Game) {
        Team.values().forEach { team ->
            g.humans[team]!!.toList().forEach { p ->
                val cape = p.equipment[EquipmentType.CAPE.id]
                if (cape?.id != team.cloak) {
                    if (cape != null) {
                        val added = p.inventory.add(item = cape.id, amount = cape.amount, assureFullInsertion = false)
                        if (added.completed < cape.amount) world.spawn(GroundItem(cape.id, cape.amount - added.completed, p.tile, p))
                    }
                    p.equipment[EquipmentType.CAPE.id] = Item(team.cloak)
                    p.calculateBonuses()
                    PlayerInfo(p).syncAppearance()
                    p.message("Your team's colours stay on until the war is over.")
                }
            }
        }
        // Carrier integrity: the banner must stay in the weapon slot.
        Team.values().forEach { flagTeam ->
            val st = g.flags[flagTeam]
            if (st is FlagState.Carried) {
                val c = st.carrier
                if (c.index < 0 || g.teamOf(c) == null) { g.flags[flagTeam] = FlagState.Home; return@forEach }
                if (c.equipment[EquipmentType.WEAPON.id]?.id != flagTeam.banner) {
                    if (c is PkBot) {
                        // The NH brain swapped gear mid-run — the standard goes straight back in hand.
                        c.equipment[EquipmentType.WEAPON.id] = Item(flagTeam.banner)
                        c.equipment[EquipmentType.SHIELD.id] = null
                        PlayerInfo(c).syncAppearance()
                        return@forEach
                    }
                    // They stowed/lost it somehow — find it and re-wield, else the flag falls here.
                    val slot = (0 until c.inventory.capacity).firstOrNull { c.inventory[it]?.id == flagTeam.banner }
                    if (slot != null) {
                        c.inventory[slot] = null
                        wieldBanner(c, flagTeam)
                    } else {
                        dropFlag(g, flagTeam, c.tile)
                        c.message("<col=801700>You've dropped the ${flagTeam.label} standard!</col>")
                    }
                }
            }
        }
    }

    /** A dropped flag whose object vanished (shouldn't happen) snaps home so the game can't deadlock. */
    private fun flagIntegrity(g: Game) {
        Team.values().forEach { team ->
            val st = g.flags[team]
            if (st is FlagState.Carried && (st.carrier.index < 0 || g.teamOf(st.carrier) == null)) {
                g.flags[team] = FlagState.Home
            }
        }
    }

    // ───────────────────────────── the bot army (roles + missions) ─────────────────────────────

    /**
     * Every filler soldier fights a WHOLE war, not a tug-of-war at the gate:
     *  - [BotRole.DEFEND]   holds the courtyard, returns its team's dropped standard, and hunts
     *    whoever is carrying it.
     *  - [BotRole.ASSAULT]  pushes the enemy gate and BREACHES it (opens the doors), then brawls
     *    in their courtyard.
     *  - [BotRole.TUNNEL]   takes the mid-field trapdoor, crosses the tunnel plaza, and surfaces
     *    behind the enemy front line.
     *  - [BotRole.RAIDER]   runs the full flag mission: breach, climb the keep, STEAL the standard,
     *    carry it home and SCORE — then goes again.
     */
    private enum class BotRole { DEFEND, ASSAULT, TUNNEL, RAIDER }

    /** Job rotation per 10 soldiers: 4 hold, 3 push, 2 flank, 1 runs flags. */
    private val ROLE_TABLE = arrayOf(
        BotRole.DEFEND, BotRole.ASSAULT, BotRole.TUNNEL, BotRole.DEFEND, BotRole.RAIDER,
        BotRole.ASSAULT, BotRole.DEFEND, BotRole.TUNNEL, BotRole.ASSAULT, BotRole.DEFEND,
    )

    private sealed class Step {
        class Walk(val tile: Tile) : Step()
        class Hop(val dest: Tile) : Step()   // scripted climb/trapdoor teleport (stairs, tunnels)
        class Breach(val gateOf: Team) : Step()
        object TakeFlag : Step()
        object ScoreFlag : Step()
    }

    private class Mission(
        val role: BotRole,
        val steps: List<Step>,
        var idx: Int = 0,
        var stuck: Int = 0,
        var stashWeapon: Item? = null,
        var stashShield: Item? = null,
    )

    private fun buildMission(role: BotRole, team: Team): Mission = when (role) {
        BotRole.DEFEND -> Mission(role, listOf(Step.Walk(GATE_INSIDE[team]!!)))
        BotRole.ASSAULT -> Mission(role, fieldPath(team) + listOf(
            Step.Walk(GATE_OUTSIDE[team.other]!!),
            Step.Breach(team.other),
            Step.Walk(GATE_INSIDE[team.other]!!),
        ))
        BotRole.TUNNEL -> Mission(role, tunnelPath(team))
        BotRole.RAIDER -> Mission(role, fieldPath(team) + listOf(
            Step.Walk(GATE_OUTSIDE[team.other]!!),
            Step.Breach(team.other),
            Step.Walk(GATE_INSIDE[team.other]!!),
        ) + keepClimb(team.other) + listOf(Step.TakeFlag) + keepDescend(team.other) + listOf(
            Step.Walk(GATE_OUTSIDE[team.other]!!),
        ) + fieldPath(team.other) + listOf(
            Step.Walk(GATE_OUTSIDE[team]!!),
            Step.Breach(team), // own gate may be shut — let ourselves in
            Step.Walk(GATE_INSIDE[team]!!),
        ) + keepClimb(team) + listOf(Step.ScoreFlag))
    }

    /** The open-field crossing from [team]'s side toward the enemy castle. */
    private fun fieldPath(team: Team): List<Step> = when (team) {
        Team.SARADOMIN -> listOf(Step.Walk(Tile(2413, 3098, 0)), Step.Walk(Tile(2400, 3105, 0)), Step.Walk(Tile(2387, 3111, 0)))
        Team.ZAMORAK -> listOf(Step.Walk(Tile(2387, 3111, 0)), Step.Walk(Tile(2400, 3105, 0)), Step.Walk(Tile(2413, 3098, 0)))
    }

    /** Down the own-side trapdoor, across the tunnel plaza, up behind the enemy line, then loiter. */
    private fun tunnelPath(team: Team): List<Step> {
        val down = TRAPDOOR_AT[team]!!
        val up = TRAPDOOR_AT[team.other]!!
        return listOf(
            Step.Walk(down),
            Step.Hop(Tile(down.x, down.z + 6400, 0)),
            Step.Walk(Tile(up.x, up.z + 6400, 0)),
            Step.Hop(up),
            Step.Walk(GATE_OUTSIDE[team.other]!!),
        )
    }

    /** Courtyard → keep roof via the real staircases (scripted hops; tiles from the map dump). */
    private fun keepClimb(castle: Team): List<Step> = when (castle) {
        Team.SARADOMIN -> listOf(
            Step.Walk(Tile(2420, 3078, 0)), Step.Hop(Tile(2419, 3079, 1)),
            Step.Walk(Tile(2427, 3081, 1)), Step.Hop(Tile(2429, 3081, 2)),
            Step.Walk(Tile(2426, 3075, 2)), Step.Hop(Tile(2426, 3075, 3)),
            Step.Walk(Tile(2428, 3074, 3)),
        )
        Team.ZAMORAK -> listOf(
            Step.Walk(Tile(2381, 3128, 0)), Step.Hop(Tile(2381, 3128, 1)),
            Step.Walk(Tile(2380, 3127, 1)), Step.Hop(Tile(2380, 3127, 2)),
            Step.Walk(Tile(2375, 3131, 2)), Step.Hop(Tile(2374, 3132, 3)),
            Step.Walk(Tile(2371, 3133, 3)),
        )
    }

    private fun keepDescend(castle: Team): List<Step> = keepClimb(castle).reversed().mapNotNull { s ->
        when (s) {
            is Step.Walk -> Step.Walk(s.tile)
            is Step.Hop -> Step.Hop(Tile(s.dest.x, s.dest.z, (s.dest.height - 1).coerceAtLeast(0)))
            else -> null
        }
    } + listOf(Step.Walk(GATE_INSIDE[castle]!!))

    /**
     * The war tick for every soldier: fight what's in reach, otherwise work the mission. Movement
     * orders are staggered so a full field never lands its route-finder calls on one cycle, and a
     * soldier stuck on geometry for ~40s is nudged to its waypoint rather than jamming the war.
     */
    private fun driveBots(g: Game) {
        Team.values().forEach { team ->
            val enemies = g.combatants(team.other).filter { it.index >= 0 && it.isAlive() }
            val ourFlag = g.flags[team]!!
            g.bots[team]!!.toList().forEachIndexed { i, bot ->
                if (bot.index < 0 || !bot.isAlive()) return@forEachIndexed
                val m = g.missions[bot] ?: return@forEachIndexed
                val carryingFlag = carrying(g, bot)

                // A flag runner RUNS — never stops to fight.
                if (carryingFlag != null && bot.getCombatTarget() != null) {
                    org.alter.plugins.content.combat.Combat.reset(bot)
                    bot.resetFacePawn()
                }

                // Defender instincts outrank the standing post: hunt our flag's carrier, or
                // return our dropped standard.
                if (m.role == BotRole.DEFEND) {
                    if (ourFlag is FlagState.Carried && ourFlag.carrier.tile.height == bot.tile.height &&
                        ourFlag.carrier.tile.isWithinRadius(bot.tile, 24)
                    ) {
                        if (bot.getCombatTarget() !== ourFlag.carrier) bot.attack(ourFlag.carrier)
                        return@forEachIndexed
                    }
                    if (ourFlag is FlagState.Dropped && i == 0.coerceAtMost(g.bots[team]!!.size - 1)) {
                        // The first defender is the retriever.
                        if (bot.tile.isWithinRadius(ourFlag.obj.tile, 2)) {
                            runCatching { world.remove(ourFlag.obj) }
                            g.flags[team] = FlagState.Home
                            broadcast(g, "<col=${team.colour}>${bot.username} returns the ${team.label} standard!</col>")
                        } else if ((g.ticks + i) % 4 == 0 && !bot.hasMoveDestination()) {
                            bot.walkTo(snapWalkable(ourFlag.obj.tile))
                        }
                        return@forEachIndexed
                    }
                }

                // Ordinary soldiers stop to fight anything in reach; a raider only swats point-blank.
                if (carryingFlag == null && bot.getCombatTarget() == null) {
                    val range = if (m.role == BotRole.RAIDER) 2 else BOT_AGGRO_RANGE
                    val prey = enemies
                        .filter { it.tile.height == bot.tile.height && it.tile.isWithinRadius(bot.tile, range) }
                        .minByOrNull { it.tile.getDistance(bot.tile) }
                    if (prey != null) { bot.attack(prey); return@forEachIndexed }
                } else if (carryingFlag == null && bot.getCombatTarget() != null) {
                    return@forEachIndexed // busy brawling
                }

                advanceMission(g, team, bot, m, i)
            }
        }
    }

    private fun advanceMission(g: Game, team: Team, bot: PkBot, m: Mission, i: Int) {
        val step = m.steps.getOrNull(m.idx) ?: return // mission complete — hold position, brain fights
        when (step) {
            is Step.Walk -> {
                if (bot.tile.height == step.tile.height && bot.tile.isWithinRadius(step.tile, 2)) {
                    m.idx++; m.stuck = 0
                } else {
                    m.stuck++
                    if (m.stuck > 66) { // ~40s of no arrival — nudge through the geometry
                        bot.moveTo(snapWalkable(step.tile)); m.idx++; m.stuck = 0
                    } else if ((g.ticks + i) % 4 == 0 && !bot.hasMoveDestination()) {
                        bot.walkTo(snapWalkable(jitter(step.tile, 1, i)))
                    }
                }
            }
            is Step.Hop -> { bot.moveTo(snapWalkable(step.dest)); m.idx++; m.stuck = 0 }
            is Step.Breach -> { breachGate(step.gateOf); m.idx++; m.stuck = 0 }
            Step.TakeFlag -> {
                val flagTeam = team.other
                when (val st = g.flags[flagTeam]!!) {
                    is FlagState.Home -> {
                        if (bot.tile.isWithinRadius(FLAG_HOME[flagTeam]!!, 4)) { botTakeFlag(g, bot, team); m.idx++ }
                        else if ((g.ticks + i) % 4 == 0 && !bot.hasMoveDestination()) bot.walkTo(snapWalkable(FLAG_HOME[flagTeam]!!))
                    }
                    is FlagState.Dropped -> {
                        if (bot.tile.height == st.obj.tile.height && bot.tile.isWithinRadius(st.obj.tile, 2)) {
                            runCatching { world.remove(st.obj) }
                            botTakeFlag(g, bot, team); m.idx++
                        } else if ((g.ticks + i) % 4 == 0 && !bot.hasMoveDestination() && bot.tile.height == st.obj.tile.height) {
                            bot.walkTo(snapWalkable(st.obj.tile))
                        }
                    }
                    is FlagState.Carried -> { /* someone else has it — hold here until it resolves */ }
                }
            }
            Step.ScoreFlag -> {
                if (carrying(g, bot) == null) { g.missions[bot] = buildMission(BotRole.RAIDER, team); return }
                if (bot.tile.isWithinRadius(FLAG_HOME[team]!!, 4)) {
                    botScore(g, bot, team)
                    g.missions[bot] = buildMission(BotRole.RAIDER, team) // and off they go again
                } else if ((g.ticks + i) % 4 == 0 && !bot.hasMoveDestination()) {
                    bot.walkTo(snapWalkable(FLAG_HOME[team]!!))
                }
            }
        }
    }

    /** Open both halves of [gateOf]'s front gate if they're shut (same swap the player click does). */
    private fun breachGate(gateOf: Team) {
        GATE_TILES[gateOf]!!.forEach { t ->
            val obj = world.getObject(t, type = 0) ?: return@forEach
            val openId = GATE_OPEN_ID[obj.id] ?: return@forEach
            runCatching { world.openDoor(obj, opened = openId) }
                .onFailure { logger.warn { "castlewars: bot gate breach failed at $t: ${it.message}" } }
        }
    }

    /** A soldier seizes a standard: real weapons stashed on the mission, banner in hand. */
    private fun botTakeFlag(g: Game, bot: PkBot, team: Team) {
        val flagTeam = team.other
        if (g.flags[flagTeam] !is FlagState.Home && g.flags[flagTeam] !is FlagState.Dropped) return
        val m = g.missions[bot] ?: return
        m.stashWeapon = bot.equipment[EquipmentType.WEAPON.id]
        m.stashShield = bot.equipment[EquipmentType.SHIELD.id]
        bot.equipment[EquipmentType.WEAPON.id] = Item(flagTeam.banner)
        bot.equipment[EquipmentType.SHIELD.id] = null
        PlayerInfo(bot).syncAppearance()
        g.flags[flagTeam] = FlagState.Carried(bot)
        broadcast(g, "<col=${team.colour}>${bot.username} has taken the ${flagTeam.label} standard!</col>")
    }

    private fun botRestoreArms(bot: PkBot, m: Mission) {
        m.stashWeapon?.let { bot.equipment[EquipmentType.WEAPON.id] = it }
        m.stashShield?.let { bot.equipment[EquipmentType.SHIELD.id] = it }
        m.stashWeapon = null; m.stashShield = null
        bot.calculateBonuses()
        PlayerInfo(bot).syncAppearance()
    }

    private fun botScore(g: Game, bot: PkBot, team: Team) {
        val flagTeam = team.other
        unwieldBanner(bot, flagTeam)
        g.missions[bot]?.let { botRestoreArms(bot, it) }
        g.flags[flagTeam] = FlagState.Home
        g.scores[team] = g.scores[team]!! + 1
        broadcast(g, "<col=${team.colour}>${bot.username} CAPTURES the ${flagTeam.label} standard for ${team.label}!</col> ${scoreLine(g)}")
    }

    // ───────────────────────────── flags ─────────────────────────────

    /**
     * A standard was clicked ("Capture"). [standardTeam] is the team the STANDARD belongs to; the click
     * may be on the home stand (their roof) or on a dropped copy anywhere on the field.
     */
    fun onCapture(p: Player, standardTeam: Team, objTile: Tile): Boolean {
        val g = game ?: return false
        val team = g.teamOf(p) ?: run { p.message("You're not part of this war."); return true }

        val home = objTile == FLAG_HOME[standardTeam]
        val flagState = g.flags[standardTeam]!!

        if (home && standardTeam == team) {
            // Touching your OWN standard: score if you carry the enemy banner.
            val enemyFlag = g.flags[team.other]!!
            if (enemyFlag is FlagState.Carried && enemyFlag.carrier === p) {
                score(g, p, team)
            } else {
                p.message("Steal ${team.other.label}'s standard first, then bring it here to score!")
            }
            return true
        }

        if (home && standardTeam != team) {
            // Taking the enemy flag off its stand.
            if (flagState !is FlagState.Home) { p.message("The ${standardTeam.label} standard isn't here right now."); return true }
            if (carrying(g, p) != null) { p.message("You're already carrying a standard!"); return true }
            takeFlag(g, p, standardTeam)
            return true
        }

        // A DROPPED flag on the field.
        if (flagState is FlagState.Dropped && objTile == flagState.obj.tile) {
            if (standardTeam == team) {
                runCatching { world.remove(flagState.obj) }
                g.flags[standardTeam] = FlagState.Home
                broadcast(g, "<col=${team.colour}>${p.username} returns the ${team.label} standard to safety!</col>")
            } else {
                if (carrying(g, p) != null) { p.message("You're already carrying a standard!"); return true }
                runCatching { world.remove(flagState.obj) }
                becomeCarrier(g, p, standardTeam)
            }
            return true
        }

        p.message("The standard has already been moved.")
        return true
    }

    /** Which flag (if any) [p] is currently carrying — returns the flag's owning team. */
    private fun carrying(g: Game, p: Player): Team? =
        Team.values().firstOrNull { (g.flags[it] as? FlagState.Carried)?.carrier === p }

    private fun takeFlag(g: Game, p: Player, flagTeam: Team) {
        if (!freeWeaponHand(p)) return
        becomeCarrier(g, p, flagTeam)
    }

    private fun becomeCarrier(g: Game, p: Player, flagTeam: Team) {
        if (!freeWeaponHand(p)) { dropFlag(g, flagTeam, p.tile); return }
        wieldBanner(p, flagTeam)
        g.flags[flagTeam] = FlagState.Carried(p)
        val team = g.teamOf(p)!!
        p.message("<col=801700>You seize the ${flagTeam.label} standard — run it back to your own!</col>")
        broadcast(g, "<col=${team.colour}>${p.username} has taken the ${flagTeam.label} standard!</col>")
    }

    /** Empties the weapon + shield hands into the inventory (a banner is carried two-handed). */
    private fun freeWeaponHand(p: Player): Boolean {
        val toMove = listOfNotNull(
            p.equipment[EquipmentType.WEAPON.id]?.let { EquipmentType.WEAPON.id to it },
            p.equipment[EquipmentType.SHIELD.id]?.let { EquipmentType.SHIELD.id to it },
        )
        if (toMove.isNotEmpty() && p.inventory.freeSlotCount < toMove.size) {
            p.message("You need ${toMove.size} free inventory space to carry a standard!")
            return false
        }
        toMove.forEach { (slot, item) ->
            p.equipment[slot] = null
            p.inventory.add(item = item.id, amount = item.amount, assureFullInsertion = false)
        }
        return true
    }

    private fun wieldBanner(p: Player, flagTeam: Team) {
        p.equipment[EquipmentType.WEAPON.id] = Item(flagTeam.banner)
        p.calculateBonuses()
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, p.equipment[EquipmentType.WEAPON.id]?.getDef()?.weaponType ?: 0)
        PlayerInfo(p).syncAppearance()
    }

    private fun unwieldBanner(p: Player, flagTeam: Team) {
        if (p.equipment[EquipmentType.WEAPON.id]?.id == flagTeam.banner) {
            p.equipment[EquipmentType.WEAPON.id] = null
            p.calculateBonuses()
            p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, 0)
            PlayerInfo(p).syncAppearance()
        }
        // Sweep stray banners out of the inventory too (never leave one usable outside the game).
        for (i in 0 until p.inventory.capacity) {
            if (p.inventory[i]?.id == flagTeam.banner) p.inventory[i] = null
        }
    }

    private fun dropFlag(g: Game, flagTeam: Team, at: Tile) {
        val carrier = (g.flags[flagTeam] as? FlagState.Carried)?.carrier
        carrier?.let { unwieldBanner(it, flagTeam) }
        // A felled soldier gets its real weapons back for the respawn.
        (carrier as? PkBot)?.let { bot -> g.missions[bot]?.let { botRestoreArms(bot, it) } }
        val tile = snapWalkable(at)
        val obj = DynamicObject(if (flagTeam == Team.SARADOMIN) SARA_STANDARD else ZAM_STANDARD, 10, 0, tile)
        world.spawn(obj)
        g.flags[flagTeam] = FlagState.Dropped(obj)
        broadcast(g, "<col=801700>The ${flagTeam.label} standard has fallen at (${tile.x}, ${tile.z})!</col>")
    }

    private fun score(g: Game, p: Player, team: Team) {
        val flagTeam = team.other
        unwieldBanner(p, flagTeam)
        g.flags[flagTeam] = FlagState.Home
        g.scores[team] = g.scores[team]!! + 1
        p.attr[CW_CAPTURES_ATTR] = (p.attr[CW_CAPTURES_ATTR] ?: 0) + 1
        payCoins(p, CAPTURE_COINS)
        p.message("<col=00b000>CAPTURE!</col> You earn ${CAPTURE_COINS / 1000}k coins.")
        broadcast(g, "<col=${team.colour}>${p.username} CAPTURES the ${flagTeam.label} standard for ${team.label}!</col> ${scoreLine(g)}")
    }

    // ───────────────────────────── deaths / leaving ─────────────────────────────

    /** Pre-death: a carrier drops the flag where they fell (bot or human). Returns true if ours. */
    fun onPreDeath(p: Player): Boolean {
        val g = game ?: return false
        if (g.teamOf(p) == null) return false
        carrying(g, p)?.let { flagTeam -> dropFlag(g, flagTeam, p.tile) }
        return true
    }

    /** Post-death: bots go on the respawn queue; humans were respawned into their spawn room by core. */
    fun onDeath(p: Player): Boolean {
        val g = game ?: return false
        val team = g.teamOf(p) ?: return false
        val bot = p as? PkBot
        if (bot != null) {
            g.bots[team]!!.remove(bot)
            g.missions.remove(bot)
            despawnBot(bot)
            g.botRespawns += (g.ticks + BOT_RESPAWN_TICKS) to team
        } else {
            p.message("<col=801700>You've fallen — back to the front with you!</col>")
        }
        return true
    }

    /** Voluntary exit (spawn-room portal), desertion, or logout cleanup. */
    fun leave(p: Player, toLobby: Boolean = true) {
        val g = game ?: run { if (p.attr[CW_STASH_ATTR] != null) restore(p); return }
        val team = g.teamOf(p) ?: run { if (p.attr[CW_STASH_ATTR] != null) restore(p); return }
        carrying(g, p)?.let { flagTeam -> dropFlag(g, flagTeam, p.tile) }
        removeHuman(g, p, restore = true)
        clearTimer(p)
        if (toLobby && p.index >= 0) p.moveTo(LOBBY_TILE)
        broadcast(g, "<col=${team.colour}>${p.username} has left ${team.label}'s ranks.</col>")
        // Backfill their slot so the sides stay full.
        if (g.combatants(team).size < teamSize) g.botRespawns += (g.ticks + BOT_RESPAWN_TICKS) to team
    }

    private fun removeHuman(g: Game, p: Player, restore: Boolean) {
        Team.values().forEach { g.humans[it]!!.remove(p) }
        Team.values().forEach { unwieldBanner(p, it) }
        stripCloaks(p)
        if (restore) restore(p)
    }

    // ───────────────────────────── end / teardown ─────────────────────────────

    /** End the current game (natural time-out, or the ::cwend admin lever). */
    fun endGame() {
        game?.let { endGame(it) }
    }

    private fun endGame(g: Game) {
        if (g.over) return
        g.over = true
        val sara = g.scores[Team.SARADOMIN]!!
        val zam = g.scores[Team.ZAMORAK]!!
        val winner = when {
            sara > zam -> Team.SARADOMIN
            zam > sara -> Team.ZAMORAK
            else -> null
        }
        lastResult = if (winner != null) {
            "${winner.label} won the last war, $sara - $zam."
        } else {
            "The last war ended in a $sara - $zam draw."
        }

        g.allHumans().toList().forEach { p ->
            if (p.index < 0) return@forEach
            val team = g.teamOf(p)!!
            val amount = when (winner) {
                null -> DRAW_COINS
                team -> WIN_COINS
                else -> LOSS_COINS
            }
            payCoins(p, amount)
            if (winner == team) p.attr[CW_WINS_ATTR] = (p.attr[CW_WINS_ATTR] ?: 0) + 1
            p.message(
                when (winner) {
                    null -> "<col=801700>A draw!</col> Both sides limp home with ${amount / 1000}k coins."
                    team -> "<col=00b000>VICTORY!</col> ${team.label} wins the war — ${amount / 1000}k coins."
                    else -> "<col=ff0000>Defeat.</col> ${winner.label} takes the day. ${amount / 1000}k coins for your trouble."
                },
            )
        }
        Announce.broadcast(world, "[Castle Wars] $lastResult")
        teardown(g)
    }

    private fun abandon(g: Game) {
        if (g.over) return
        g.over = true
        lastResult = "The last war was abandoned — every real soldier deserted."
        logger.info { "castlewars: game abandoned (no humans left)" }
        teardown(g)
    }

    private fun teardown(g: Game) {
        g.allHumans().toList().forEach { p ->
            if (p.index < 0) return@forEach
            removeHuman(g, p, restore = true)
            clearTimer(p)
            p.moveTo(LOBBY_TILE)
        }
        Team.values().forEach { team ->
            g.bots[team]!!.toList().forEach { despawnBot(it) }
            g.bots[team]!!.clear()
            g.barricades[team]!!.toList().forEach { runCatching { world.remove(it) } }
            g.barricades[team]!!.clear()
            (g.flags[team] as? FlagState.Dropped)?.let { runCatching { world.remove(it.obj) } }
            g.flags[team] = FlagState.Home
        }
        g.botRespawns.clear()
        g.missions.clear()
        if (game === g) game = null
    }

    // ───────────────────────────── crash safety (LMS stash pattern, cape-only) ─────────────────────────────

    private fun stashIfNeeded(p: Player) {
        if (p.attr[CW_STASH_ATTR] != null) return
        val doc = Document()
        p.equipment[EquipmentType.CAPE.id]?.let { doc.append("cape", Document("id", it.id).append("amount", it.amount)) }
        p.attr[RESPAWN_TILE_ATTR]?.let { doc.append("respawn", it) }
        p.attr[CW_STASH_ATTR] = doc.toJson()
    }

    fun restore(p: Player) {
        val blob = p.attr[CW_STASH_ATTR] ?: return
        runCatching {
            val doc = Document.parse(blob)
            stripCloaks(p)
            Team.values().forEach { unwieldBanner(p, it) }
            doc.get("cape", Document::class.java)?.let { c ->
                p.equipment[EquipmentType.CAPE.id] = Item(c.getInteger("id"), c.getInteger("amount", 1))
            }
            if (doc.containsKey("respawn")) p.attr[RESPAWN_TILE_ATTR] = doc.getInteger("respawn")
            else p.attr.remove(RESPAWN_TILE_ATTR)
        }.onFailure { logger.error(it) { "castlewars: stash restore failed for ${p.username} — blob kept" }; return }
        p.calculateBonuses()
        PlayerInfo(p).syncAppearance()
        p.attr.remove(CW_STASH_ATTR)
    }

    /** Removes any team cloak from the cape slot (their own cape comes back via [restore]). */
    private fun stripCloaks(p: Player) {
        val cape = p.equipment[EquipmentType.CAPE.id]
        if (cape?.id == SARA_CLOAK || cape?.id == ZAM_CLOAK) {
            p.equipment[EquipmentType.CAPE.id] = null
            p.calculateBonuses()
            PlayerInfo(p).syncAppearance()
        }
    }

    /** A stash blob at login = a JVM death mid-war; make the player whole and put them in the lobby. */
    fun onLogin(p: Player) {
        if (p.attr[CW_STASH_ATTR] != null && !inGame(p)) {
            restore(p)
            p.moveTo(LOBBY_TILE)
            p.message("<col=801700>The war ended while you were away — your cape has been returned.</col>")
        } else if (ARENA.contains(p.tile.x, p.tile.z) && !inGame(p)) {
            p.moveTo(LOBBY_TILE) // logged out mid-battle in a war that's now over
        } else if (UNDERGROUND.contains(p.tile.x, p.tile.z) && queue.none { it.player === p }) {
            p.moveTo(LOBBY_TILE) // logged out in a waiting room — the war left without them
        }
    }

    fun onLogout(p: Player) {
        queue.removeAll { it.player === p }
        if (inGame(p)) leave(p, toLobby = false)
    }

    // ───────────────────────────── support: bandages, barriers, scoreboard ─────────────────────────────

    /** A supply-table grab: [amount] of whatever this table stocks (game participants only). */
    fun onTakeSupply(p: Player, tableId: Int, amount: Int): Boolean {
        if (!inGame(p)) { p.message("The supplies are for soldiers of the war."); return true }
        val (item, label) = SUPPLY_TABLES[tableId] ?: return false
        val added = p.inventory.add(item = item, amount = amount, assureFullInsertion = false)
        if (added.completed == 0) p.message("Your pack is full.") else p.message("You take some $label.")
        return true
    }

    /** Barricade "Set-up": plants one of the team's attackable barricades on the player's tile. */
    fun onSetUpBarricade(p: Player, slot: Int) {
        val g = game ?: run { p.message("Save it for the war."); return }
        val team = g.teamOf(p) ?: run { p.message("Save it for the war."); return }
        if (p.tile.height != 0 || !ARENA.contains(p.tile.x, p.tile.z)) {
            p.message("You can only set up barricades on the battlefield.")
            return
        }
        if (g.barricades[team]!!.size >= MAX_BARRICADES) {
            p.message("${team.label} already has the maximum of $MAX_BARRICADES barricades standing.")
            return
        }
        if (!p.inventory.remove(item = BARRICADE_ITEM, beginSlot = slot).hasSucceeded()) return
        val npcId = if (team == Team.SARADOMIN) SARA_BARRICADE_NPC else ZAM_BARRICADE_NPC
        val cade = org.alter.game.model.entity.Npc(npcId, Tile(p.tile.x, p.tile.z, p.tile.height), world)
        cade.walkRadius = 0
        world.spawn(cade)
        g.barricades[team]!! += cade
        p.message("You set up a barricade. (${g.barricades[team]!!.size}/$MAX_BARRICADES)")
    }

    /** Bandage "Heal": ~15% max HP + a burst of run energy, consumed from the clicked slot. */
    fun onUseBandage(p: Player, slot: Int) {
        if (!inGame(p)) { p.message("You shouldn't waste war supplies out here."); return }
        if (p.inventory.remove(item = BANDAGES, beginSlot = slot).hasSucceeded()) {
            val heal = max(2, p.getMaxHp() * 15 / 100)
            p.setCurrentHp(minOf(p.getMaxHp(), p.getCurrentHp() + heal))
            p.runEnergy = minOf(10000.0, p.runEnergy + 3000.0)
            p.sendRunEnergy(p.runEnergy.toInt())
            p.animate(829)
            p.message("You bind your wounds with the bandages.")
        }
    }

    /** An energy barrier "Pass": own-team soldiers step through; everyone else bounces off. */
    fun onPassBarrier(p: Player, barrierId: Int, barrierTile: Tile): Boolean {
        val g = game ?: run { p.message("The barrier hums — there's no war on right now."); return true }
        val team = g.teamOf(p) ?: run { p.message("The barrier repels you."); return true }
        val owner = if (barrierId == SARA_BARRIER) Team.SARADOMIN else Team.ZAMORAK
        if (team != owner) { p.message("${owner.label}'s barrier repels you!"); return true }
        // Step through: mirror the player across the barrier tile.
        val dx = (barrierTile.x - p.tile.x).coerceIn(-1, 1)
        val dz = (barrierTile.z - p.tile.z).coerceIn(-1, 1)
        val dest = snapWalkable(Tile(barrierTile.x + dx, barrierTile.z + dz, barrierTile.height))
        p.moveTo(dest)
        return true
    }

    fun scoreboardLines(p: Player): List<String> {
        val g = game
        val live = if (g != null && !g.over) {
            "War in progress: ${scoreLinePlain(g)} (${(GAME_TICKS - g.ticks) * 6 / 1000} min left)"
        } else {
            "No war is being fought right now."
        }
        return listOf(
            live,
            lastResult,
            "Your record: ${p.attr[CW_WINS_ATTR] ?: 0} wins, ${p.attr[CW_CAPTURES_ATTR] ?: 0} flag captures.",
        )
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun payCoins(p: Player, amount: Int) {
        val added = p.inventory.add(item = COINS, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(COINS, leftover, p.tile, p))
    }

    private fun scoreLine(g: Game): String =
        "<col=1c5ba8>Saradomin ${g.scores[Team.SARADOMIN]}</col> - <col=a81c1c>${g.scores[Team.ZAMORAK]} Zamorak</col>"

    private fun scoreLinePlain(g: Game): String =
        "Saradomin ${g.scores[Team.SARADOMIN]} - ${g.scores[Team.ZAMORAK]} Zamorak"

    private fun broadcast(g: Game, msg: String) {
        g.allHumans().forEach { if (it.index >= 0) it.message(msg) }
    }

    private fun jitter(t: Tile, radius: Int, seed: Int): Tile {
        val dx = (seed * 31 % (radius * 2 + 1)) - radius
        val dz = (seed * 17 % (radius * 2 + 1)) - radius
        return Tile(t.x + dx, t.z + dz, t.height)
    }

    fun snapWalkable(t: Tile): Tile = world.snapToWalkable(t)
}
