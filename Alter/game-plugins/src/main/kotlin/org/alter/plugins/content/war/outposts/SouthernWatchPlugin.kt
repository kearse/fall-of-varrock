package org.alter.plugins.content.war.outposts

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.move.walkTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.teleport.TeleportService
import org.alter.plugins.content.teleport.TransportRoutes
import org.alter.plugins.content.war.Campaigns
import org.alter.plugins.content.war.Frontiers
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **Southern Watch** in the shared world (see [SouthernWatch]): the standard at the circle's
 * heart, a Field Quartermaster, and a small presence-gated garrison of Knights of Lumbridge who
 * skirmish with whatever the `varrock_outskirts` march target stages around the ring (the enemy
 * lines spawn right through the circle during a march — "enemy patrols will return; now we have
 * somewhere to meet them").
 *
 * Mirrors `GoblinCampPlugin`'s knight lifecycle: this plugin OWNS respawn (engine respawn off), the
 * "Knight of Lumbridge" display name and the allied stats are re-applied on every (re)spawn, and
 * nothing is maintained unless a real player is near — the post costs nothing while empty.
 *
 * The route lock lives here too ([TransportRoutes.register]) so the portal row / General Zo / the
 * `::southernwatch` command all refuse with the same line until First Reclamation is complete.
 */
class SouthernWatchPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class KnightSlot(val home: Tile, val face: Direction) {
        var npc: Npc? = null
        var respawnIn = 0
    }

    private val knights = SouthernWatch.KNIGHT_POSTS.map { (t, d) -> KnightSlot(t, d) }

    init {
        TransportRoutes.register(
            SouthernWatch.ROUTE,
            "The Southern Watch is Lumbridge's forward post - only those who helped raise it may travel there. Complete First Reclamation (General Zo).",
        )

        val timer = TimerKey()
        onWorldInit {
            spawnStandard(world)
            spawnQuartermaster(world)
            world.timers[timer] = TICK
        }
        onTimer(timer) {
            runCatching { tick(world) }.onFailure { logger.error(it) { "Southern Watch garrison tick failed (skipped)." } }
            world.timers[timer] = TICK
        }

        bindStandard()

        // Fast travel for those who earned it; TeleportService refuses with the route's own line otherwise.
        onCommand("southernwatch", description = "Travel to the Southern Watch, the forward post south of Varrock (First Reclamation)") {
            TeleportService.teleport(player, SouthernWatch.ROUTE)
        }
    }

    // ---------------------------------------------------------------- the standard

    /** The banner on its stand, north of the centre stone — one shared object for everyone. */
    private fun spawnStandard(world: World) {
        val id = runCatching { getRSCM(SouthernWatch.STANDARD_OBJ) }.getOrNull() ?: run {
            logger.warn { "[southern-watch] '${SouthernWatch.STANDARD_OBJ}' is not in the cache; the standard was not raised." }
            return
        }
        world.spawn(DynamicObject(id = id, type = OBJ_TYPE, rot = 0, tile = SouthernWatch.STANDARD_TILE))
        logger.info { "[southern-watch] standard raised at ${SouthernWatch.STANDARD_TILE}." }
    }

    /** Bind the standard's option DEFENSIVELY (a raw bind on a missing option drops the whole plugin). */
    private fun bindStandard() {
        val id = runCatching { getRSCM(SouthernWatch.STANDARD_OBJ) }.getOrNull() ?: return
        val actions = runCatching { getObject(id).actions.filterNotNull().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val option = actions.firstOrNull { it.equals(SouthernWatch.STANDARD_OPTION, ignoreCase = true) } ?: actions.firstOrNull()
        if (option == null) {
            logger.warn { "[southern-watch] standard loc $id has no click options ($actions); the quest's raise step cannot bind." }
            return
        }
        onObjOption(id, option) {
            val p = player
            if (SouthernWatch.onCapture(p)) return@onObjOption
            if (SouthernWatch.isUnlocked(p)) {
                p.message("The Lumbridge standard flies over ${SouthernWatch.NAME}. The road around it is still contested - but the post is ours.")
            } else {
                p.message("Lumbridge's standard, raised over the southern approach after the reclamation. General Zo could use another soldier for the next push.")
            }
        }
    }

    // ---------------------------------------------------------------- the Field Quartermaster

    /** A frontier Quartermaster at the post — same npc, same global hand-in bind, renamed at spawn. */
    private fun spawnQuartermaster(world: World) {
        val id = runCatching { getRSCM(SouthernWatch.QUARTERMASTER_NPC) }.getOrNull() ?: run {
            logger.warn { "[southern-watch] '${SouthernWatch.QUARTERMASTER_NPC}' not in cache; Field Quartermaster not posted." }
            return
        }
        runCatching {
            val npc = Npc(id, SouthernWatch.QUARTERMASTER_TILE, world)
            npc.walkRadius = 0
            npc.lastFacingDirection = Direction.WEST // set BEFORE spawn: the avatar takes its facing at alloc time
            world.spawn(npc)
            WarNpcNames.rename(npc, SouthernWatch.QUARTERMASTER_NAME) // AFTER spawn: the avatar is lateinit
            npc.respawns = false
            npc.setActive(true)
            logger.info { "[southern-watch] Field Quartermaster posted at ${SouthernWatch.QUARTERMASTER_TILE}." }
        }.onFailure { logger.error(it) { "[southern-watch] Field Quartermaster failed to spawn." } }
    }

    // ---------------------------------------------------------------- the garrison

    private fun tick(world: World) {
        if (!playerNear(world)) {
            standDown(world)
            return
        }
        maintainKnights(world)
        skirmish(world)
    }

    /** Any real (non-bot) player within [ACTIVATION_RADIUS] of the circle. */
    private fun playerNear(world: World): Boolean {
        var near = false
        world.players.forEach { p ->
            if (!near && p !is PkBot && p.isOnline && !p.invisible &&
                p.tile.isWithinRadius(SouthernWatch.CENTRE, ACTIVATION_RADIUS)
            ) {
                near = true
            }
        }
        return near
    }

    private fun maintainKnights(world: World) {
        for (slot in knights) {
            val n = slot.npc
            if (n != null) {
                if (n.index < 0 || !world.npcs.contains(n)) { // cut down since the last tick
                    slot.npc = null
                    slot.respawnIn = RESPAWN_TICKS
                }
            } else if (slot.respawnIn <= 0 || --slot.respawnIn == 0) {
                slot.npc = spawnKnight(world, slot)
            }
        }
    }

    private fun spawnKnight(world: World, slot: KnightSlot): Npc? = runCatching {
        val npc = Npc(getRSCM(SouthernWatch.KNIGHT_NPC), slot.home, world)
        npc.walkRadius = KNIGHT_WALK
        npc.lastFacingDirection = slot.face
        npc.routeLogic = 1
        world.spawn(npc)
        // AFTER world.spawn: setNpcDefaults() resets combatDef + HP to the cache default on spawn.
        val d = Campaigns.ALLIED_DEF
        npc.combatDef = d
        npc.stats.setMaxLevel(NpcSkills.ATTACK, d.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, d.attack)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, d.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, d.strength)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, d.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, d.defence)
        npc.setCurrentHp(d.hitpoints)
        npc.aggroCheck = { _, _ -> false } // the realm's knights never aggro players
        npc.respawns = false // we own respawn — a knight can't resurrect after the post stands down
        npc.setActive(true)
        WarNpcNames.apply(npc, SouthernWatch.KNIGHT_NPC) // "Knight of Lumbridge" without a cache edit
        npc
    }.onFailure { logger.error(it) { "[southern-watch] knight failed to spawn at ${slot.home}" } }.getOrNull()

    /**
     * The garrison holds the ring: each knight strikes the nearest living enemy of the outskirts'
     * march garrison within [KNIGHT_ENGAGE] (the lines stage through the circle during a march), and
     * is recalled to its post if it strays past [KNIGHT_LEASH]. Explicit `attack` only — never the
     * engine aggro path (an aggroCheck that throws kills the game loop).
     */
    private fun skirmish(world: World) {
        val enemies = Frontiers.zone(SouthernWatch.TARGET_KEY)?.livingEnemies(world) ?: emptyList()
        for (slot in knights) {
            val k = slot.npc ?: continue
            if (k.index < 0 || k.isDead()) continue

            if (k.tile.getDistance(slot.home) > KNIGHT_LEASH) {
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                k.walkTo(slot.home)
                continue
            }

            val cur = k.getCombatTarget() as? Npc
            if (k.isAttacking() && cur != null && cur.index >= 0 && !cur.isDead()) continue

            val foe = enemies
                .filter { it.index >= 0 && !it.isDead() && it.tile.getDistance(k.tile) <= KNIGHT_ENGAGE }
                .minByOrNull { it.tile.getDistance(k.tile) }
            if (foe != null) {
                k.attack(foe)
            } else if (k.isAttacking()) {
                k.removeCombatTarget(); k.resetFacePawn()
                if (k.tile.getDistance(slot.home) > 1) k.walkTo(slot.home)
            }
        }
    }

    /** Despawn the garrison when nobody's around (keeps npc slots / CPU free). */
    private fun standDown(world: World) {
        for (slot in knights) {
            val n = slot.npc ?: continue
            if (n.index >= 0 && world.npcs.contains(n)) world.remove(n)
            slot.npc = null
            slot.respawnIn = 0
        }
    }

    private companion object {
        const val OBJ_TYPE = 10
        const val TICK = 5 // ~3s upkeep cadence, like the goblin camp / frontier sweeps
        const val RESPAWN_TICKS = 6 // ~18s from a knight's death to its respawn
        const val ACTIVATION_RADIUS = 24
        const val KNIGHT_WALK = 3
        const val KNIGHT_ENGAGE = 10 // a knight charges an enemy within this many tiles of the post
        const val KNIGHT_LEASH = 12  // recalled if it strays this far chasing
    }
}
