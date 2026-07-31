package org.alter.plugins.content.bots.knights

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setPlayerHintArrow
import org.alter.api.ext.setTileHintArrow
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
import org.alter.plugins.content.quests.QuestJournal
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
 * **Tracking arrow** (the quest-helper ask): while a knight is the player's active target and
 * guidance isn't muted, a native hint arrow leads them — a TILE arrow toward the camp from any
 * distance (edge/minimap arrow), flipping to a PLAYER arrow locked onto the live knight once it's
 * in scene (~15 tiles). Refreshed on the world tick, deduped so packets only go out on change.
 * Dying to a knight never clears the assignment — walk back and the arrow leads you straight in.
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

    /** Last arrow state sent per player, so the poll only writes on change. */
    private sealed class Arrow {
        data object None : Arrow()
        data class Camp(val x: Int, val z: Int) : Arrow()
        data class Knight(val index: Int) : Arrow()
    }
    private val lastArrow = HashMap<PlayerUID, Arrow>()

    init {
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

        onCommand("knights", description = "Show the Rogue Knight ladder and your hunt") {
            player.message(RogueKnightLadder.statusLine(player))
            if (!RogueKnightLadder.unlocked(player)) return@onCommand
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
            player.message("The marker leads to your hunt. Camps: safe ground until the deep wild — past Varrock, you risk what you carry.")
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
            val def = RogueKnightLadder.activeDef(p) ?: continue
            maintainHunt(world, p, def, hunts.getOrPut(uid) { Hunt() })
            updateArrow(p, def)
        }

        // Clear arrows for players who no longer have an active hunt (or logged off).
        lastArrow.keys.toList().forEach { uid ->
            val owner = online[uid]
            if (owner == null) {
                lastArrow.remove(uid)
            } else if (RogueKnightLadder.activeDef(owner) == null && lastArrow[uid] != Arrow.None) {
                owner.clearHintArrow()
                lastArrow.remove(uid)
            }
        }
    }

    /** Keep one live, bound instance of [def] while [p] is at its camp; stand down when they leave. */
    private fun maintainHunt(world: World, p: Player, def: RogueKnightDef, hunt: Hunt) {
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
        val anchor = spawnAnchor(p.tile, def.camp.center)
        val tile = world.findRandomTileAround(anchor, radius = 4)
            ?: world.findRandomTileAround(def.camp.center, radius = 6)
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

    // ------------------------------------------------------------------ the tracking arrow

    /** Hybrid arrow: PLAYER arrow on the live knight in scene, TILE arrow toward the camp from
     *  anywhere else. Deduped per player; respects the Quest Journal guidance mute. */
    private fun updateArrow(p: Player, def: RogueKnightDef) {
        val desired: Arrow = when {
            QuestJournal.muted(p) -> Arrow.None
            else -> {
                val bot = hunts[p.uid]?.bot
                if (bot != null && bot.index >= 0 && !bot.isDead() &&
                    bot.tile.isWithinRadius(p.tile, IN_SCENE_RADIUS)
                ) {
                    Arrow.Knight(bot.index)
                } else {
                    Arrow.Camp(def.camp.center.x, def.camp.center.z)
                }
            }
        }
        if (lastArrow[p.uid] == desired) return
        lastArrow[p.uid] = desired
        when (desired) {
            is Arrow.None -> p.clearHintArrow()
            is Arrow.Camp -> p.setTileHintArrow(desired.x, desired.z)
            is Arrow.Knight -> p.setPlayerHintArrow(desired.index)
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

        /** Within this range the arrow locks onto the knight itself (entity arrows render in scene). */
        const val IN_SCENE_RADIUS = 15
    }
}
