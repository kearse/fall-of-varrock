package org.alter.plugins.content.bots.knights

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.info.PlayerInfo
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.attr.TITLED_NAME_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotLoadouts
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.hunt.TargetMarker
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Rogue Knight camp engine** — spawning, tracking and death wiring for the named knights of
 * [RogueKnights.LADDER] ([RogueKnightLadder] owns the progression state; the Recruiting Sergeant
 * speaks the briefs).
 *
 * **Per-hunter instances.** Every player hunting a knight gets their OWN instance of it (bound via
 * [PkBot.boundHunter], which locks its aggro to them alone): with many players on the same ladder
 * rung at once, a shared spawn would be a kill-steal queue, and the bound duplicate reads as "the
 * knight came out to meet *you*". Instances are presence-gated like every bot system — spawned when
 * the hunter nears the camp, despawned when they leave, respawned on a short cooldown after a kill
 * or a (very expected) hunter death, capped per camp.
 *
 * **Tracking arrow** (the quest-helper ask): while a knight is the player's active target, the
 * shared [TargetMarker] helper leads them — a TILE arrow toward the camp from any distance
 * (edge/minimap arrow), flipping to a PLAYER arrow locked onto the live knight once it's in scene.
 * This plugin only registers the ladder's claim ([TargetMarker.PRIORITY_LADDER]); the marker owns
 * the drawing, deduping and the guidance mute. Dying to a knight never clears the assignment —
 * walk back and the arrow leads you straight in.
 *
 * PVP-TRAINING SEAM: the camps and their per-hunter knight instances are the realm's PK
 * curriculum, reached through the OPTIONAL Rogue Problem assignment. A future PvP Training
 * Academy plugs in here (spawn a chosen ladder knight for a lesson) — see [RogueKnights.LADDER].
 */
class RogueKnightCampPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** One hunter's live pursuit of their active knight. */
    private class Hunt {
        var bot: PkBot? = null
        var respawnAt = 0
        var awayTicks = 0
    }

    private val hunts = HashMap<PlayerUID, Hunt>()

    /** Camp key each hunter was last gate-nudged at (session-only) — one nudge per camp visit. */
    private val nudgedCamp = HashMap<PlayerUID, String>()

    init {
        // The ladder's claim on the shared hunt marker. Camp gate still open: the arrow hunts the
        // camp's TIER rogues (nearest live one in scene, the camp from afar) — the knight won't
        // fight yet. Gate cleared: the live bound knight when it's up, the camp center otherwise.
        TargetMarker.register(TargetMarker.PRIORITY_LADDER) { p ->
            val def = RogueKnightLadder.activeDef(p) ?: return@register null
            if (!CampClearance.cleared(p, def.camp)) {
                TargetMarker.Mark(entity = CampClearance.nearestCampBot(p, def.camp), fallback = def.camp.center)
            } else {
                TargetMarker.Mark(entity = hunts[p.uid]?.bot, fallback = def.camp.center)
            }
        }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            try {
                tick(world)
            } catch (e: Exception) {
                logger.error(e) { "Rogue Knight camp tick failed (skipped)." }
            }
            world.timers[timer] = TICK
        }

        // Rank credit for the BOUND hunter — runs before the death sequence, while KILLER_ATTR is
        // intact. The kit + signature-rare drop is handled centrally by BotCombatPlugin/PkLootPools.
        onPlayerPreDeath {
            val bot = player as? PkBot ?: return@onPlayerPreDeath
            val key = bot.attr[KNIGHT_KEY_ATTR] ?: return@onPlayerPreDeath
            val def = RogueKnights.byKey(key) ?: return@onPlayerPreDeath
            val killer = bot.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerPreDeath
            if (killer is PkBot || killer.index < 0) return@onPlayerPreDeath
            if (killer.uid == bot.boundHunter) RogueKnightLadder.onKnightKilled(killer, def)
        }

        onCommand("knights", description = "Show the Rogue Knight ladder and your hunt (::knights challenge opens it without the quest)") {
            if (player.getCommandArgs().getOrNull(0).equals("challenge", ignoreCase = true)) {
                if (!RogueKnightLadder.optIn(player)) player.message("The Rogue Knight ladder is already open to you — ::knights shows your hunt.")
                return@onCommand
            }
            player.message(RogueKnightLadder.statusLine(player))
            if (!RogueKnightLadder.unlocked(player)) return@onCommand
            player.message("War Effort from farmed knights left today: <col=801700>${RogueRewards.repeatBudgetLeft(player)}</col> (first kills and camp clears always pay).")
            RogueKnightLadder.activeDef(player)?.let { active ->
                player.message(CampClearance.statusLine(player, active.camp))
            }
            val rank = RogueKnightLadder.rank(player)
            val activeIdx = RogueKnightLadder.targetIdx(player)
            RogueKnights.LADDER.forEach { def ->
                val state = when {
                    def.rank == activeIdx -> "<col=ffae00>HUNTING</col>"
                    def.rank < rank -> "<col=4f9b4f>beaten</col> — ::huntknight ${def.rank + 1} to farm"
                    def.rank == rank -> "<col=801700>assigned</col>"
                    else -> "locked"
                }
                player.message("  ${def.rank + 1}. ${def.name} — ${def.camp.display}: $state")
            }
            player.message("The marker leads to your hunt (::huntarrow turns the arrow off/on). Camps: safe ground until the deep wild — past Varrock, you risk what you carry.")
        }

        onCommand("huntknight", description = "Farm a beaten Rogue Knight: ::huntknight <number>") {
            val n = player.getCommandArgs().getOrNull(0)?.toIntOrNull()
            if (n == null) {
                player.message("Usage: ::huntknight <number> (see ::knights) — or ::huntknight next to return to your assignment.")
                return@onCommand
            }
            if (!RogueKnightLadder.setFarmTarget(player, n - 1)) {
                player.message("You can only farm knights you've already beaten — your assignment is number ${RogueKnightLadder.rank(player) + 1}.")
            }
        }

        onCommand("huntnext", description = "Return the hunt to your assigned Rogue Knight") {
            RogueKnightLadder.clearFarmTarget(player)
            player.message(RogueKnightLadder.statusLine(player))
        }
    }

    // ------------------------------------------------------------------ the world tick

    private fun tick(world: World) {
        // Online, real players only — bots never hunt knights.
        val online = HashMap<PlayerUID, Player>()
        world.players.forEach { p ->
            if (p !is PkBot && p.isOnline && !p.invisible) online[p.uid] = p
        }

        // Sweep dead hunts: owners who logged off / stopped hunting.
        val stale = hunts.filterKeys { uid ->
            val owner = online[uid]
            owner == null || RogueKnightLadder.activeDef(owner) == null
        }
        stale.forEach { (uid, hunt) ->
            hunt.bot?.let { if (it.index >= 0) BotManager.despawn(world, it) }
            hunts.remove(uid)
        }

        for ((uid, p) in online) {
            val def = RogueKnightLadder.activeDef(p)
            if (def == null) {
                if (!RogueKnightLadder.unlocked(p)) nudgeLocked(uid, p)
                continue
            }
            maintainHunt(world, p, def, hunts.getOrPut(uid) { Hunt() })
            nudgeGate(uid, p, def)
        }
        nudgedCamp.keys.retainAll(online.keys)
    }

    /** One heads-up per camp visit for a player who walks into a camp with the ladder still closed
     *  to them: the knights are optional, but they should know the door exists. */
    private fun nudgeLocked(uid: PlayerUID, p: Player) {
        val camp = RogueKnights.CAMPS.firstOrNull { p.tile.isWithinRadius(it.center, ACTIVATION_RADIUS) }
        if (camp == null) {
            nudgedCamp.remove(uid)
            return
        }
        if (nudgedCamp[uid] == camp.key) return
        nudgedCamp[uid] = camp.key
        val knight = RogueKnights.LADDER.firstOrNull { it.camp == camp }?.name ?: "its knights"
        p.message("<col=801700>${camp.display.replaceFirstChar { it.uppercase() }} answers to $knight.</col> Take the Recruiting Sergeant's assignment, or challenge the Rogue Knights directly (<col=0000ff>::knights challenge</col>) — thin the camp, then the knight will face you.")
    }

    /** One heads-up per camp visit for a hunter arriving at a camp whose gate they haven't cleared. */
    private fun nudgeGate(uid: PlayerUID, p: Player, def: RogueKnightDef) {
        if (!p.tile.isWithinRadius(def.camp.center, ACTIVATION_RADIUS)) {
            nudgedCamp.remove(uid) // left the camp — nudge again next visit
            return
        }
        if (CampClearance.cleared(p, def.camp) || nudgedCamp[uid] == def.camp.key) return
        nudgedCamp[uid] = def.camp.key
        val left = CampClearance.goal(def.camp) - CampClearance.kills(p, def.camp)
        p.message("<col=801700>${def.camp.display.replaceFirstChar { it.uppercase() }} bristles at your approach.</col> Cut down <col=ffae00>$left</col> more of its rogues before ${def.name} will face you.")
    }

    /** Keep one live, bound instance of [def] while [p] is at its camp; stand down when they leave. */
    private fun maintainHunt(world: World, p: Player, def: RogueKnightDef, hunt: Hunt) {
        // Camp gate ([CampClearance]): until the hunter has thinned this camp's tier rogues, its
        // knight doesn't take the field at all — the camp reads "clear the rogues first", and the
        // canEngage veto guards the edge cases (another hunter's instance, a goal raised later).
        if (!CampClearance.cleared(p, def.camp)) {
            hunt.bot?.let { if (it.index >= 0) BotManager.despawn(world, it) }
            hunt.bot = null
            return
        }

        val bot = hunt.bot

        // The hunter's live instance must match the CURRENT target — switching farm targets swaps it.
        if (bot != null && bot.attr[KNIGHT_KEY_ATTR] != def.key) {
            if (bot.index >= 0) BotManager.despawn(world, bot)
            hunt.bot = null
        }

        val live = hunt.bot
        if (live != null) {
            if (live.index < 0 || live.isDead()) { // fell since last tick (killed, or engine cleanup)
                hunt.bot = null
                hunt.respawnAt = world.currentCycle + RESPAWN_CYCLES
            } else if (!p.tile.isWithinRadius(def.camp.center, ACTIVATION_RADIUS)) {
                if (++hunt.awayTicks >= AWAY_GRACE_TICKS) { // hunter left — stand the knight down
                    BotManager.despawn(world, live)
                    hunt.bot = null
                    hunt.awayTicks = 0
                }
            } else {
                hunt.awayTicks = 0
            }
            return
        }

        // No live instance: spawn one when the hunter is at the camp, off cooldown, under the cap.
        if (!p.tile.isWithinRadius(def.camp.center, ACTIVATION_RADIUS)) return
        if (world.currentCycle < hunt.respawnAt) return
        if (campLiveCount(def.camp) >= CAMP_CAP) return
        hunt.bot = spawnKnight(world, p, def)
    }

    /** Live named-knight instances currently standing at [camp] (any hunter's). */
    private fun campLiveCount(camp: KnightCamp): Int =
        hunts.values.count { h ->
            val b = h.bot
            b != null && b.index >= 0 && !b.isDead() && RogueKnights.byKey(b.attr[KNIGHT_KEY_ATTR] ?: "")?.camp == camp
        }

    /**
     * Spawn [def]'s instance for [p]: near the hunter (a dramatic "he's come to meet you" entrance,
     * mirroring BotColony's spawn band) but always anchored back toward the camp center, dressed
     * with its name, boss overrides and hunter lock.
     */
    private fun spawnKnight(world: World, p: Player, def: RogueKnightDef): PkBot? {
        val loadout = BotLoadouts.get(def.loadoutKey) ?: run {
            logger.error { "Rogue Knight '${def.key}': unknown loadout '${def.loadoutKey}' — not spawning." }
            return null
        }
        // Route-verified from the hunter's tile: a plain findRandomTileAround can land in an
        // enclosed pocket (the bank-teller corridor report) where the knight is unreachable.
        val anchor = spawnAnchor(p.tile, def.camp.center)
        val tile = BotManager.reachableTileAround(world, from = p.tile, centre = anchor, radius = 4)
            ?: BotManager.reachableTileAround(world, from = p.tile, centre = def.camp.center, radius = 6)
            ?: def.camp.center
        val bot = BotManager.spawn(world, loadout, tile) ?: return null // world full

        bot.attr[KNIGHT_KEY_ATTR] = def.key
        bot.boundHunter = p.uid
        bot.homeTile = tile
        bot.roamRadius = 3
        bot.leashRadius = KNIGHT_LEASH
        bot.zoneKey = "knight_${def.key}"
        bot.ambushEverywhere = def.camp.safe // safe camps: the knight fights on reclaim ground

        // Boss overrides — maxHpOverride + unclamped CURRENT level, never setBaseLevel > 99 (the
        // 99-entry XP table). getMaxHp() reads the override, so eat ratios/head-bar stay correct.
        def.maxHp?.let { hp ->
            bot.maxHpOverride = hp
            bot.getSkills().setCurrentLevel(HITPOINTS, hp)
        }
        bot.reactionTicksRange = def.reactionTicks
        bot.specRegenPeriod = def.specRegen
        appendInventory(bot, def.extraInventory.mapNotNull { line ->
            runCatching { Item(getRSCM(line.name), line.amount) }.getOrNull() // unknown key — skip
        })

        // The knight's NAME over its head. The username stays "Rogue Knight" (kill-crediting and
        // loot-key labels key off it) — TITLED_NAME_ATTR overrides only the rendered name.
        bot.attr[TITLED_NAME_ATTR] = "<col=ff3030>${def.name}</col>"
        PlayerInfo(bot).syncAppearance()

        p.message("<col=801700>${def.name}</col> strides out to meet you. <col=ffae00>This fight is yours alone.</col>")
        return bot
    }

    /** Where the knight appears: [SPAWN_DIST] tiles from the hunter toward the camp center (or the
     *  center itself when the hunter is basically on top of it). */
    private fun spawnAnchor(hunter: Tile, center: Tile): Tile {
        val dx = center.x - hunter.x
        val dz = center.z - hunter.z
        val dist = hunter.getDistance(center)
        if (dist <= SPAWN_DIST) return center
        val fx = hunter.x + dx * SPAWN_DIST / dist
        val fz = hunter.z + dz * SPAWN_DIST / dist
        return Tile(fx, fz, hunter.height)
    }

    private fun appendInventory(bot: PkBot, items: List<Item>) {
        var slot = 0
        for (item in items) {
            while (slot < bot.inventory.capacity && bot.inventory[slot] != null) slot++
            if (slot >= bot.inventory.capacity) return
            bot.inventory[slot] = item
        }
    }

    private companion object {
        /** Upkeep cadence (game ticks) — matches the goblin-camp / world-spawn sweeps (~3s). */
        const val TICK = 5

        /** Skill index for hitpoints (mirrors BotManager). */
        const val HITPOINTS = 3

        /** How near the camp center a hunter must be for their knight to hold the field. */
        const val ACTIVATION_RADIUS = 40

        /** Camp-ticks a hunter may stray before their knight stands down (~30s at TICK=5). */
        const val AWAY_GRACE_TICKS = 10

        /** World cycles between a knight falling and its return for the same hunter (~45s). */
        const val RESPAWN_CYCLES = 75

        /** Live named knights allowed per camp at once (many hunters = many duplicates). */
        const val CAMP_CAP = 8

        /** The knight spawns this many tiles from its hunter, on the camp side. */
        const val SPAWN_DIST = 14

        /** Chase tether — generous, so a knight presses the fight but can't be dragged across the map. */
        const val KNIGHT_LEASH = 24
    }
}
