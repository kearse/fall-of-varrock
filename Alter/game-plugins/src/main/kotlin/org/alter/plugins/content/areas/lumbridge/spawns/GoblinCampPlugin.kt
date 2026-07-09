package org.alter.plugins.content.areas.lumbridge.spawns

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.move.walkTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotLoadouts
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The Lumbridge goblin camp east of the castle (~3254,3234, where [SpawnPlugin]'s goblins already
 * roam) gets a small garrison of **Knights of Lumbridge** holding the line, plus a lone **low-level
 * PKer** (an aggressive ambusher) prowling the edge — a hint of the war/PvP flavour reaching the
 * newbie fields.
 *
 * This plugin OWNS the full lifecycle of both, rather than leaning on the `spawnNpc` DSL, because:
 *  - the knights need the "Knight of Lumbridge" display-name override ([WarNpcNames]) and custom
 *    combat stats ([KNIGHT_DEF]) re-applied every (re)spawn — engine respawn resets both;
 *  - the PKer is a clientless [PkBot] (a fake-player, not an Npc) that must be re-spawned on death.
 *
 * Everything is **presence-gated** (mirrors [org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin]):
 * nothing is maintained unless a real player is near the camp, and it stands down when the area
 * empties — so a lone bot never idles at an empty newbie field burning CPU. The PKer's brain uses the
 * per-bot [PkBot.ambushEverywhere] flag so it will aggro here even though this is not the wilderness.
 */
class GoblinCampPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Knights of Lumbridge — a notch below the war knights, tuned to skirmish with camp goblins. */
    private val knightDef = NpcCombatDef.DEFAULT.copy(
        attack = 60, strength = 55, defence = 60, hitpoints = 70,
        attackSpeed = 5, attackAnimation = 407,
    )

    /** Live knight slots: home tile + facing, and the current npc (null while dead/awaiting respawn). */
    private class KnightSlot(val home: Tile, val face: Direction) {
        var npc: Npc? = null
        var respawnIn = 0
    }

    private val knights = KNIGHT_POSTS.map { (t, d) -> KnightSlot(t, d) }
    private var pker: PkBot? = null

    /** World cycle before which the PKer won't respawn — set when it's killed (60s cooldown). */
    private var pkerRespawnAt = 0

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            try {
                tick(world)
            } catch (e: Exception) {
                logger.error(e) { "Goblin-camp garrison tick failed (skipped)." }
            }
            world.timers[timer] = TICK
        }
    }

    private fun tick(world: World) {
        if (!playerNear(world)) {
            standDown(world)
            return
        }
        maintainKnights(world)
        skirmishKnights(world)
        maintainPker(world)
    }

    /**
     * NPC-vs-NPC skirmish: each knight hunts the nearest **camp goblin**, so the post is an ongoing
     * brawl rather than a stand-off (mirrors the war [org.alter.plugins.content.war.HostileZone]'s
     * friendly-vs-enemy loop, but scoped to this one spot and the ambient goblins that already roam
     * it). Goblins auto-retaliate, so both sides actually fight. Runs INSIDE the guarded tick — we
     * never touch the engine aggro path (an [Npc.aggroCheck] there throwing would kill the game loop),
     * only the safe explicit [org.alter.game.model.entity.Pawn.attack].
     */
    private fun skirmishKnights(world: World) {
        // Living goblins loose around the camp (ids vary — match by def name, not a hardcoded set).
        val goblins = ArrayList<Npc>()
        world.npcs.forEach { n ->
            if (n != null && n.index >= 0 && !n.isDead() &&
                n.tile.isWithinRadius(CAMP_CENTRE, GOBLIN_SCAN_RADIUS) &&
                n.def.name.contains("goblin", ignoreCase = true)
            ) {
                goblins += n
            }
        }
        for (slot in knights) {
            val k = slot.npc ?: continue
            if (k.index < 0 || k.isDead()) continue

            // Recall a knight that strayed too far chasing a fleer — keep the garrison on its post.
            if (k.tile.getDistance(slot.home) > KNIGHT_LEASH) {
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                k.walkTo(slot.home)
                continue
            }

            // Already locked onto a live goblin? Leave it be — the engine drives the fight.
            val cur = k.getCombatTarget() as? Npc
            if (k.isAttacking() && cur != null && cur.index >= 0 && !cur.isDead()) continue

            val foe = goblins
                .filter { it.tile.getDistance(k.tile) <= KNIGHT_ENGAGE }
                .minByOrNull { it.tile.getDistance(k.tile) }
            if (foe != null) {
                k.attack(foe)
            } else if (k.isAttacking()) {
                k.removeCombatTarget(); k.resetFacePawn()
                if (k.tile.getDistance(slot.home) > 1) k.walkTo(slot.home)
            }
        }
    }

    /** Any real (non-bot) player within [ACTIVATION_RADIUS] of the camp centre. */
    private fun playerNear(world: World): Boolean {
        var near = false
        world.players.forEach { p ->
            if (!near && p !is PkBot && p.isOnline && !p.invisible &&
                p.tile.isWithinRadius(CAMP_CENTRE, ACTIVATION_RADIUS)
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
                if (n.index < 0 || !world.npcs.contains(n)) { // killed since last tick
                    slot.npc = null
                    slot.respawnIn = RESPAWN_TICKS
                }
            } else if (slot.respawnIn <= 0 || --slot.respawnIn == 0) {
                slot.npc = spawnKnight(world, slot)
            }
        }
    }

    private fun spawnKnight(world: World, slot: KnightSlot): Npc {
        val npc = Npc(getRSCM(KNIGHT_NPC), slot.home, world)
        npc.walkRadius = KNIGHT_WALK
        npc.lastFacingDirection = slot.face
        npc.routeLogic = 1 // smarter pathing so the knight can close on a goblin to attack it
        world.spawn(npc)
        // MUST be after world.spawn: setNpcDefaults() resets combatDef + HP to the cache default on
        // spawn, so applying our stats earlier would be silently clobbered (the general-Zo lesson).
        npc.combatDef = knightDef
        npc.stats.setMaxLevel(NpcSkills.ATTACK, knightDef.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, knightDef.attack)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, knightDef.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, knightDef.strength)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, knightDef.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, knightDef.defence)
        npc.setCurrentHp(knightDef.hitpoints)
        // We own the lifecycle (respawn on death via the tick loop); engine respawn stays OFF so a
        // knight can't resurrect after we stand the garrison down.
        npc.respawns = false
        npc.setActive(true)
        WarNpcNames.apply(npc, KNIGHT_NPC) // display "Knight of Lumbridge" without a cache edit
        return npc
    }

    private fun maintainPker(world: World) {
        val b = pker
        if (b != null) {
            if (b.index >= 0 && !b.isDead()) return // still alive
            // It was just killed — start the respawn cooldown before it comes back.
            pker = null
            pkerRespawnAt = world.currentCycle + PKER_RESPAWN_CYCLES
            return
        }
        if (world.currentCycle < pkerRespawnAt) return // still on the post-death cooldown
        val loadout = BotLoadouts.get(PKER_LOADOUT) ?: run {
            logger.error { "Goblin-camp PKer: unknown loadout '$PKER_LOADOUT' — not spawning." }
            return
        }
        val tile = world.findRandomTileAround(CAMP_CENTRE, radius = 4) ?: CAMP_CENTRE
        val bot = BotManager.spawn(world, loadout, tile) ?: return // world/player limit reached
        bot.homeTile = CAMP_CENTRE
        bot.roamRadius = PKER_ROAM
        bot.leashRadius = PKER_LEASH
        bot.zoneKey = ZONE_KEY
        bot.ambushEverywhere = true // aggro players here even though it isn't the wilderness
        pker = bot
    }

    /** Despawn the garrison + PKer when nobody's around (keeps npc slots / CPU free). */
    private fun standDown(world: World) {
        for (slot in knights) {
            val n = slot.npc ?: continue
            if (n.index >= 0 && world.npcs.contains(n)) world.remove(n)
            slot.npc = null
            slot.respawnIn = 0
        }
        pker?.let { if (it.index >= 0) BotManager.despawn(world, it) }
        pker = null
    }

    private companion object {
        const val TICK = 5 // ~3s upkeep cadence, matching the world-spawn / frontier sweeps
        const val RESPAWN_TICKS = 6 // ~18s from a knight's death to its respawn

        const val KNIGHT_NPC = "npc.knight_of_saradomin"
        const val KNIGHT_WALK = 5

        val CAMP_CENTRE = Tile(3254, 3234)
        const val ACTIVATION_RADIUS = 24

        // --- knight-vs-goblin skirmish ---
        const val GOBLIN_SCAN_RADIUS = 16 // how far from the camp centre a goblin counts as "in the camp"
        const val KNIGHT_ENGAGE = 12      // a knight charges the nearest goblin within this many tiles
        const val KNIGHT_LEASH = 16       // recalled to its post if it strays this far chasing

        /** Knight posts around the camp (tile + the way each one faces). */
        val KNIGHT_POSTS = listOf(
            Tile(3254, 3234) to Direction.EAST,
            Tile(3251, 3237) to Direction.SOUTH,
            Tile(3257, 3231) to Direction.WEST,
        )

        const val PKER_LOADOUT = "bronze_pker" // lowest bracket — "Bandit", combat 52
        const val PKER_ROAM = 6
        const val PKER_LEASH = 14
        const val PKER_RESPAWN_CYCLES = 100 // 60s cooldown after a kill (world cycle = 600ms)
        const val ZONE_KEY = "lumbridge_goblin_camp"
    }
}
