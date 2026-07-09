package org.alter.plugins.content.minigames.castlewars

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.SafeDeaths
import org.alter.plugins.content.minigames.castlewars.CastleWars.Team

private val logger = KotlinLogging.logger {}

/**
 * **Castle Wars** bindings — the engine lives in [CastleWars]; this plugin owns the tick timer and
 * every world hook: the lobby god portals, the flag standards ("Capture"), spawn-room barriers and
 * exit portals, bandage tables + the Bandages heal, the keep ladders/trapdoors, the scoreboard, and
 * the death/login/logout crash-safety hooks.
 *
 * Every cache bind is guarded (the LMS/WizardTower pattern) so a stale cache degrades gracefully
 * instead of silently dropping the whole plugin at boot.
 */
class CastleWarsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val timer = TimerKey()

    /** The arena's map regions + the underground waiting rooms (verified via mapDump): collision +
     *  objects must exist the moment a queuer teleports in, even if nobody has walked there since boot. */
    private val arenaRegions = intArrayOf(9520, 9776, 9620)

    init {
        SafeDeaths.register(CastleWars.ARENA) // nobody ever drops items in the war
        SafeDeaths.register(CastleWars.UNDERGROUND) // ...including down in the tunnels

        onWorldInit {
            CastleWars.world = world
            runCatching { world.definitions.loadRegions(world, world.chunks, arenaRegions) }
                .onFailure { logger.warn { "castlewars: region force-load failed: ${it.message}" } }
            // The cache roofs have no way back down — give each keep roof a permanent exit ladder.
            CastleWars.ROOF_LADDERS.forEach { tile ->
                runCatching { world.spawn(DynamicObject(CastleWars.LADDERS_DOWN[0], 10, 0, tile)) }
                    .onFailure { logger.warn { "castlewars: roof ladder spawn failed at $tile: ${it.message}" } }
            }
            world.timers[timer] = 1
        }
        onTimer(timer) {
            CastleWars.tickAll()
            world.timers[timer] = 1
        }

        bindLobby()
        bindArena()
        bindBandages()
        bindClimbs()

        onPlayerPreDeath { CastleWars.onPreDeath(player) }
        onPlayerDeath { CastleWars.onDeath(player) }
        onLogin { CastleWars.onLogin(player) }
        onLogout { CastleWars.onLogout(player) }

        onCommand("cw", description = "Teleport to the Castle Wars lobby") {
            player.moveTo(CastleWars.LOBBY_TILE)
            player.message("<col=801700>Castle Wars:</col> step through a god portal to join the next war. Guthix balances the sides.")
        }
        onCommand("cwstart", Privilege.ADMIN_POWER, description = "Force the queued Castle Wars game to start now") {
            CastleWars.join(player, null) // ensure the admin is in, then fire immediately
            CastleWars.forceStart()
            player.message("castlewars: countdown forced.")
        }
        onCommand("cwend", Privilege.ADMIN_POWER, description = "End the running Castle Wars game now") {
            CastleWars.endGame()
            player.message("castlewars: game ended.")
        }
        onCommand("cwbots", Privilege.ADMIN_POWER, description = "Set Castle Wars team size (bots fill)") {
            val n = player.getCommandArgs().getOrNull(0)?.toIntOrNull()
            if (n == null || n !in 1..25) { player.message("Usage: ::cwbots 1-25"); return@onCommand }
            CastleWars.teamSize = n
            player.message("castlewars: team size set to $n (applies to fills from now on).")
        }
    }

    // ───────────────────────────── lobby ─────────────────────────────

    private fun bindLobby() {
        bindObj(CastleWars.SARA_PORTAL, "enter") { CastleWars.join(player, Team.SARADOMIN) }
        bindObj(CastleWars.ZAM_PORTAL, "enter") { CastleWars.join(player, Team.ZAMORAK) }
        bindObj(CastleWars.GUTHIX_PORTAL, "enter") { CastleWars.join(player, null) }
        bindObj(CastleWars.SCOREBOARD, "view") {
            CastleWars.scoreboardLines(player).forEach { player.message(it) }
        }
    }

    // ───────────────────────────── arena ─────────────────────────────

    private fun bindArena() {
        bindObj(CastleWars.SARA_STANDARD, "capture") {
            CastleWars.onCapture(player, Team.SARADOMIN, player.getInteractingGameObj().tile)
        }
        bindObj(CastleWars.ZAM_STANDARD, "capture") {
            CastleWars.onCapture(player, Team.ZAMORAK, player.getInteractingGameObj().tile)
        }
        bindObj(CastleWars.SARA_EXIT_PORTAL, "leave") { leaveDialog(player) }
        bindObj(CastleWars.ZAM_EXIT_PORTAL, "leave") { leaveDialog(player) }
        // Waiting-room exit portals: out of the queue, back up to the lobby.
        bindObj(CastleWars.SARA_WAIT_PORTAL, "exit") { CastleWars.onExitWaitingRoom(player) }
        bindObj(CastleWars.ZAM_WAIT_PORTAL, "exit") { CastleWars.onExitWaitingRoom(player) }
        intArrayOf(CastleWars.SARA_BARRIER, CastleWars.ZAM_BARRIER).forEach { id ->
            bindObj(id, "pass") {
                val obj = player.getInteractingGameObj()
                CastleWars.onPassBarrier(player, id, obj.tile)
            }
        }
    }

    private fun leaveDialog(p: org.alter.game.model.entity.Player) {
        p.queue {
            when (options(p, "Leave the war.", "Keep fighting!")) {
                1 -> CastleWars.leave(p)
                else -> p.message("For ${CastleWars.teamOf(p)?.label ?: "glory"}!")
            }
        }
    }

    // ───────────────────────────── bandages ─────────────────────────────

    private fun bindBandages() {
        CastleWars.SUPPLY_TABLES.keys.forEach { id ->
            bindObj(id, "take-from") { CastleWars.onTakeSupply(player, id, 1) }
            bindObj(id, "take-5") { CastleWars.onTakeSupply(player, id, 5) }
        }
        runCatching {
            onItemOption(item = "item.bandages", option = "heal") {
                CastleWars.onUseBandage(player, player.getInteractingSlot())
            }
        }.onFailure { logger.warn { "castlewars: bandage heal bind skipped: ${it.message}" } }
        runCatching {
            onItemOption(item = "item.barricade", option = "set-up") {
                CastleWars.onSetUpBarricade(player, player.getInteractingSlot())
            }
        }.onFailure { logger.warn { "castlewars: barricade set-up bind skipped: ${it.message}" } }
        bindBarricadeNpcs()
    }

    /** The attackable barricade NPCs need a combat def (hp) or `canEngage` refuses them — and the
     *  DSL requires a death anim or registration throws and silently drops the plugin (boss lesson). */
    private fun bindBarricadeNpcs() {
        listOf("npc.barricade_5722", "npc.barricade_5724").forEach { key ->
            runCatching {
                setCombatDef(key) {
                    configs {
                        attackSpeed = 4
                        respawnDelay = 0 // never respawns — the game owns its lifecycle
                    }
                    stats {
                        hitpoints = 12
                        defence = 1
                    }
                    anims {
                        death = org.alter.plugins.content.bosses.deathAnimFor(key)
                    }
                }
            }.onFailure { logger.warn { "castlewars: barricade combat def '$key' skipped: ${it.message}" } }
        }
    }

    // ───────────────────────────── keep climbs ─────────────────────────────

    /**
     * The keep verticals the generic [LadderPlugin] doesn't cover. 16683 (ground -> level 1) is
     * already handled there — rebinding it would collide and drop this plugin at boot.
     */
    private fun bindClimbs() {
        // The castles' actual staircases (4415/4417-4420) carry a single ambiguous "Climb" option:
        // on the ground you can only go up, on the roof only down, in between we ask.
        CastleWars.STAIRCASES.forEach { id ->
            bindObj(id, "climb") {
                when (player.tile.height) {
                    0 -> climb(player, +1)
                    3 -> climb(player, -1)
                    else -> player.queue {
                        when (options(player, "Climb up the stairs.", "Climb down the stairs.")) {
                            1 -> doClimb(player, +1)
                            2 -> doClimb(player, -1)
                        }
                    }
                }
            }
        }
        CastleWars.SHAFT_LADDERS_UP.forEach { id ->
            bindObj(id, "climb-up") { climb(player, +1) }
        }
        CastleWars.LADDERS_DOWN.forEach { id ->
            bindObj(id, "climb-down") { climb(player, -1) }
        }
        CastleWars.TRAPDOORS.forEach { id ->
            bindObj(id, "open") {
                player.queue {
                    when (options(player, "Climb up to the roof.", "Climb down.")) {
                        1 -> doClimb(player, +1)
                        2 -> doClimb(player, -1)
                    }
                }
            }
        }
        CastleWars.CAVE_LADDERS.forEach { id ->
            bindObj(id, "climb-down") {
                if (CastleWars.ARENA.contains(player.tile.x, player.tile.z)) {
                    tunnelClimb(player, down = true)
                } else {
                    climb(player, -1)
                }
            }
        }
        // The under-the-front-line flank: mid-field trapdoors drop into the tunnel plaza; the
        // underground ladders come back up. Both ids exist elsewhere in the world, so every bind
        // is gated to the Castle Wars map and silently no-ops anywhere else (same as unbound).
        bindObj(CastleWars.SURFACE_TRAPDOOR, "open") {
            if (CastleWars.ARENA.contains(player.tile.x, player.tile.z)) {
                tunnelClimb(player, down = true)
            }
        }
        bindObj(CastleWars.TUNNEL_LADDER, "climb-up") {
            if (CastleWars.UNDERGROUND.contains(player.tile.x, player.tile.z)) {
                tunnelClimb(player, down = false)
            }
        }
        // The rubble sealing the outer tunnels — mine it through (cleared until the next restart).
        bindObj(CastleWars.TUNNEL_ROCKS, "mine") {
            if (!CastleWars.UNDERGROUND.contains(player.tile.x, player.tile.z)) return@bindObj
            val rocks = player.getInteractingGameObj()
            player.queue {
                player.animate(625) // pickaxe swing
                wait(3)
                runCatching { world.remove(rocks) }
                player.message("You clear a path through the rubble!")
            }
        }
    }

    /** Trapdoor/cave-ladder travel between the battlefield and the tunnels (the OSRS +6400 shift). */
    private fun tunnelClimb(p: org.alter.game.model.entity.Player, down: Boolean) {
        p.queue {
            p.animate(828)
            p.lock()
            wait(2)
            val dz = if (down) 6400 else -6400
            p.moveTo(CastleWars.snapWalkable(org.alter.game.model.Tile(p.tile.x, p.tile.z + dz, 0)))
            p.unlock()
            if (down) p.message("You climb down into the tunnels beneath the battlefield.")
        }
    }

    private fun climb(p: org.alter.game.model.entity.Player, dir: Int) {
        p.queue { doClimb(p, dir) }
    }

    private suspend fun QueueTask.doClimb(p: org.alter.game.model.entity.Player, dir: Int) {
        val destHeight = (p.tile.height + dir).coerceIn(0, 3)
        if (destHeight == p.tile.height) return
        p.animate(828)
        p.lock()
        wait(2)
        // Snap in case the tile directly above/below is inside a wall (the 1x3 staircases).
        val dest = CastleWars.snapWalkable(org.alter.game.model.Tile(p.tile.x, p.tile.z, destHeight))
        p.moveTo(dest)
        p.unlock()
    }

    // ───────────────────────────── guarded binding helper ─────────────────────────────

    /** Bind [option] on object [id] if the cache def actually carries it; log-and-skip otherwise
     *  (a bad bind must never drop the whole Castle Wars plugin at boot). */
    private fun bindObj(id: Int, option: String, logic: org.alter.game.plugin.Plugin.() -> Unit) {
        runCatching { onObjOption(id, option = option, logic = logic) }
            .onFailure { logger.warn { "castlewars: bind ($id,'$option') skipped: ${it.message}" } }
    }
}
