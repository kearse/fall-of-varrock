package org.alter.plugins.content.areas.portsarim

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.LAST_HIT_BY_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.isBeingAttacked
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Siege of Port Sarim** — Lumbridge's lifeline to the rest of the land. If the port falls,
 * the realm is cut off; so the crown garrisons the docks, and the Rogue Knights raid them in
 * endless waves (the rogue-knight ladder's second camp — `bots/knights/RogueKnights.PORT_SARIM` —
 * stations its named knights here as the raids' leaders).
 *
 * **The tide mechanic** (the design ask): each raid wave is a discrete battle — [RAIDERS_PER_WAVE]
 * Rogue Raiders against [DEFENDER_POSTS.size] Knights of Lumbridge, tuned so the DEFENDERS LOSE by
 * a little on their own (slightly weaker per-unit and outnumbered). A player who kills **2–3
 * raiders** flips the numbers and the knights win the melee; when the last raider falls, everyone
 * who landed a raider kill that wave is paid a SMALL contribution (+[WAVE_WAR_EFFORT] War Effort —
 * march-tier, deliberately modest). Left alone, the docks are overrun, the raiders hold them for a
 * spell, a fresh squad of knights marches back in, and the see-saw continues — the port is
 * perpetually *almost* lost until somebody steps in.
 *
 * Mechanically this is [org.alter.plugins.content.areas.lumbridge.spawns.GoblinCampPlugin] grown
 * into a wave machine: one presence-gated world-timer loop that owns every lifecycle, explicit
 * [org.alter.game.model.entity.Pawn.attack] steering inside the guarded tick (never the engine
 * aggro path), NPCs auto-retaliating so both sides genuinely fight, stats applied AFTER
 * `world.spawn` (setNpcDefaults resets them — the general-Zo lesson). Raiders never auto-aggro
 * players (the port is a safe travel hub; they retaliate if attacked, and the knights pile onto
 * any raider that hits a player). All tiles + balance numbers are TUNE.
 */
class PortSiegePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Knights of Lumbridge — the goblin-camp garrison band (60/55/60/70). */
    private class DefenderSlot(val home: Tile, val face: Direction) {
        var npc: Npc? = null
        var respawnIn = 0
    }

    private enum class State { ASSAULT, OVERRUN, HELD }

    private val defenders = DEFENDER_POSTS.map { (t, d) -> DefenderSlot(t, d) }
    private val raiders = ArrayList<Npc>()
    private var state = State.HELD
    private var stateUntil = 0 // world cycle the current OVERRUN/HELD phase ends
    private var everActivated = false

    /** Players who landed a raider killing blow this wave — paid when the wave is won. */
    private val contributors = HashSet<PlayerUID>()

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            try {
                tick(world)
            } catch (e: Exception) {
                logger.error(e) { "Port Sarim siege tick failed (skipped)." }
            }
            world.timers[timer] = TICK
        }

        // Raider kill attribution (additive; cheap identity check against our live wave list).
        onAnyNpcDeath {
            val dead = npc
            if (raiders.any { it === dead }) {
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null && killer !is PkBot && killer.index >= 0) contributors += killer.uid
            }
        }
    }

    private fun tick(world: World) {
        if (!playerNear(world)) {
            standDown(world)
            return
        }
        if (!everActivated) {
            // First activation: the garrison is at its posts and the first raid is already inbound.
            everActivated = true
            state = State.HELD
            stateUntil = world.currentCycle + FIRST_WAVE_DELAY
        }
        pruneRaiders(world)
        maintainDefenders(world)
        when (state) {
            State.ASSAULT -> {
                steerBattle(world)
                defendPlayers(world)
                when {
                    raiders.isEmpty() -> waveWon(world)
                    defenders.none { it.npc.alive() } -> {
                        state = State.OVERRUN
                        stateUntil = world.currentCycle + OVERRUN_CYCLES
                        localMessage(world, "<col=801700>The dock garrison has fallen — Port Sarim's docks are overrun!</col> Reinforcements are mustering.")
                    }
                    else -> {}
                }
            }
            State.OVERRUN -> {
                // Raiders hold the docks; players can still cut them down and win the wave.
                steerRaidersToObjective(world)
                if (raiders.isEmpty()) {
                    waveWon(world)
                } else if (world.currentCycle >= stateUntil) {
                    // A fresh squad marches back in and the melee resumes.
                    for (slot in defenders) if (!slot.npc.alive()) { slot.npc = spawnDefender(world, slot); slot.respawnIn = 0 }
                    state = State.ASSAULT
                    localMessage(world, "<col=4f9b4f>Knights of Lumbridge storm back onto the docks!</col>")
                }
            }
            State.HELD -> {
                if (world.currentCycle >= stateUntil) launchWave(world)
            }
        }
    }

    // ------------------------------------------------------------------ wave lifecycle

    /** A new raid party pours in from the Falador road and pushes for the docks. */
    private fun launchWave(world: World) {
        contributors.clear()
        STAGING_TILES.take(RAIDERS_PER_WAVE).forEach { tile ->
            spawnRaider(world, tile)?.let { raiders += it }
        }
        if (raiders.isNotEmpty()) {
            state = State.ASSAULT
            localMessage(world, "<col=801700>Rogue raiders pour up the Falador road — Port Sarim is under attack!</col> Lose the port and Lumbridge is cut off.")
        } else {
            stateUntil = world.currentCycle + LULL_CYCLES // spawn failed (shouldn't happen) — try again later
        }
    }

    /** The last raider fell — the port holds. Pay everyone who helped turn the tide (small, per wave). */
    private fun waveWon(world: World) {
        if (contributors.isNotEmpty()) {
            world.players.forEach { p ->
                if (p !is PkBot && p.isOnline && p.uid in contributors) {
                    p.addPoints(PointKind.WAR_EFFORT, WAVE_WAR_EFFORT)
                    p.message("<col=4f9b4f>The port holds — Lumbridge keeps its lifeline.</col> (+$WAVE_WAR_EFFORT War Effort)")
                }
            }
        }
        contributors.clear()
        localMessage(world, "<col=4f9b4f>The raiders break — Port Sarim's docks are held, for now.</col>")
        state = State.HELD
        stateUntil = world.currentCycle + LULL_CYCLES
    }

    // ------------------------------------------------------------------ combat steering

    /** Drop dead/removed raiders from the wave list. */
    private fun pruneRaiders(world: World) {
        raiders.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
    }

    /**
     * ASSAULT steering: every unengaged combatant charges the nearest living enemy in range;
     * strays get recalled. Retaliation is automatic for NPCs, so one explicit [Npc.attack] per
     * pairing keeps the whole melee alive. The raiders' numbers (and slight per-unit edge) decide
     * the fight — which is exactly what a player's 2-3 kills overturn.
     */
    private fun steerBattle(world: World) {
        val liveDefenders = defenders.mapNotNull { it.npc?.takeIf { n -> n.alive() } }
        // Raiders: fight the nearest knight, else push for the dock objective.
        for (rdr in raiders) {
            if (recallIfStrayed(rdr, OBJECTIVE, RAIDER_LEASH)) continue
            if (rdr.lockedOnLiveTarget()) continue
            val foe = liveDefenders.nearestTo(rdr.tile, ENGAGE_RANGE)
            if (foe != null) rdr.attack(foe) else walkToward(rdr, OBJECTIVE)
        }
        // Defenders: hold the dock line, fight the nearest raider.
        for (slot in defenders) {
            val k = slot.npc?.takeIf { it.alive() } ?: continue
            if (recallIfStrayed(k, slot.home, DEFENDER_LEASH)) continue
            if (k.getCombatTarget() is Player) continue // busy defending a citizen — don't pull off
            if (k.lockedOnLiveTarget()) continue
            val foe = raiders.nearestTo(k.tile, ENGAGE_RANGE)
            if (foe != null) k.attack(foe) else if (k.tile.getDistance(slot.home) > 1) k.walkTo(slot.home)
        }
    }

    /** OVERRUN: the raiders loiter on the taken docks (still attackable — a win is still possible). */
    private fun steerRaidersToObjective(world: World) {
        for (rdr in raiders) {
            if (rdr.lockedOnLiveTarget()) continue
            if (rdr.tile.getDistance(OBJECTIVE) > 3) walkToward(rdr, OBJECTIVE)
        }
    }

    /** Knights pile onto any raider that strikes a nearby player (HostileZone's defendCitizens). */
    private fun defendPlayers(world: World) {
        world.players.forEach { p ->
            if (p is PkBot || !p.isOnline || !p.isBeingAttacked()) return@forEach
            if (!p.tile.isWithinRadius(PORT_CENTRE, DEFEND_RANGE)) return@forEach
            val attacker = p.attr[LAST_HIT_BY_ATTR]?.get() as? Npc ?: return@forEach
            if (raiders.none { it === attacker } || !attacker.alive()) return@forEach
            for (slot in defenders) {
                val k = slot.npc?.takeIf { it.alive() && it.tile.getDistance(p.tile) <= DEFEND_RANGE } ?: continue
                if (!(k.isAttacking() && k.getCombatTarget() === attacker)) k.attack(attacker)
            }
        }
    }

    // ------------------------------------------------------------------ population

    /** Defender respawns run only OUTSIDE an assault (a wave is a discrete battle — no mid-fight
     *  trickle, or the raiders could never actually take the docks). */
    private fun maintainDefenders(world: World) {
        for (slot in defenders) {
            val n = slot.npc
            if (n != null && !n.alive()) {
                slot.npc = null
                slot.respawnIn = DEFENDER_RESPAWN_TICKS
            }
            if (state == State.ASSAULT || state == State.OVERRUN) continue
            if (slot.npc == null && (slot.respawnIn <= 0 || --slot.respawnIn == 0)) {
                slot.npc = spawnDefender(world, slot)
            }
        }
    }

    private fun spawnDefender(world: World, slot: DefenderSlot): Npc {
        val npc = Npc(getRSCM(DEFENDER_NPC), slot.home, world)
        npc.walkRadius = 2
        npc.lastFacingDirection = slot.face
        npc.routeLogic = 1 // smarter pathing so the knight can close on a raider
        world.spawn(npc)
        // MUST be after world.spawn (setNpcDefaults resets combatDef + HP — the general-Zo lesson).
        // copy() on top of the live def keeps the model's own animations/sounds right.
        npc.combatDef = npc.combatDef.copy(
            attack = DEF_ATK, strength = DEF_STR, defence = DEF_DEF, hitpoints = DEF_HP, attackSpeed = 5,
        )
        applyStats(npc, DEF_ATK, DEF_STR, DEF_DEF, DEF_HP)
        npc.respawns = false
        npc.setActive(true)
        npc.aggroCheck = { _, _ -> false } // good guys never jump players; steering aims them at raiders
        WarNpcNames.apply(npc, DEFENDER_NPC) // "Knight of Lumbridge"
        return npc
    }

    private fun spawnRaider(world: World, tile: Tile): Npc? {
        val npc = runCatching { Npc(getRSCM(RAIDER_NPC), tile, world) }.getOrNull() ?: run {
            logger.error { "Port siege: raider npc '$RAIDER_NPC' unresolved — wave not spawned." }
            return null
        }
        npc.walkRadius = 3
        npc.routeLogic = 1
        world.spawn(npc)
        // BALANCE (TUNE): a shade above the knights' 60/55/60/70 so 6v4 grinds the garrison down,
        // 4v4 is a coin flip, and 4v3 (a player killed 2-3) flips to the defenders — numbers decide.
        npc.combatDef = npc.combatDef.copy(
            attack = RDR_ATK, strength = RDR_STR, defence = RDR_DEF, hitpoints = RDR_HP, attackSpeed = 5,
        )
        applyStats(npc, RDR_ATK, RDR_STR, RDR_DEF, RDR_HP)
        npc.respawns = false
        npc.setActive(true)
        npc.aggroCheck = { _, _ -> false } // raiders don't jump travellers at a safe hub; they DO retaliate
        WarNpcNames.rename(npc, RAIDER_NAME)
        return npc
    }

    private fun applyStats(npc: Npc, atk: Int, str: Int, def: Int, hp: Int) {
        npc.stats.setMaxLevel(NpcSkills.ATTACK, atk); npc.stats.setCurrentLevel(NpcSkills.ATTACK, atk)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, str); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, str)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, def); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def)
        npc.setCurrentHp(hp)
    }

    // ------------------------------------------------------------------ gating + teardown

    private fun playerNear(world: World): Boolean {
        var near = false
        world.players.forEach { p ->
            if (!near && p !is PkBot && p.isOnline && !p.invisible &&
                p.tile.isWithinRadius(PORT_CENTRE, ACTIVATION_RADIUS)
            ) {
                near = true
            }
        }
        return near
    }

    /** Nobody near: clear the whole scene (CPU/npc slots free; a fresh activation restarts clean). */
    private fun standDown(world: World) {
        if (!everActivated) return
        for (slot in defenders) {
            slot.npc?.let { if (it.index >= 0 && world.npcs.contains(it)) world.remove(it) }
            slot.npc = null
            slot.respawnIn = 0
        }
        raiders.forEach { if (it.index >= 0 && world.npcs.contains(it)) world.remove(it) }
        raiders.clear()
        contributors.clear()
        state = State.HELD
        everActivated = false
    }

    // ------------------------------------------------------------------ small helpers

    private fun Npc?.alive(): Boolean = this != null && index >= 0 && !isDead()

    private fun Npc.lockedOnLiveTarget(): Boolean {
        val cur = getCombatTarget() ?: return false
        val alive = when (cur) {
            is Npc -> cur.index >= 0 && !cur.isDead()
            is Player -> cur.index >= 0 && !cur.isDead()
            else -> false
        }
        return isAttacking() && alive
    }

    /** Recall a combatant that strayed too far from [anchor]; true if a recall happened. */
    private fun recallIfStrayed(npc: Npc, anchor: Tile, leash: Int): Boolean {
        if (npc.tile.getDistance(anchor) <= leash) return false
        if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }
        npc.walkTo(anchor)
        return true
    }

    private fun walkToward(npc: Npc, dest: Tile) {
        if (npc.tile.getDistance(dest) > 1) npc.walkTo(dest)
    }

    private fun <T : Npc> Collection<T>.nearestTo(tile: Tile, range: Int): T? =
        filter { it.alive() && it.tile.getDistance(tile) <= range }
            .minByOrNull { it.tile.getDistance(tile) }

    private fun localMessage(world: World, text: String) {
        world.players.forEach { p ->
            if (p !is PkBot && p.isOnline && p.tile.isWithinRadius(PORT_CENTRE, ACTIVATION_RADIUS)) {
                p.message(text)
            }
        }
    }

    private companion object {
        const val TICK = 5 // ~3s upkeep cadence (goblin-camp / world-spawn standard)

        // --- geography (TUNE in-game; keep everything z<=3255 — the red starts at z3258) ---
        val PORT_CENTRE = Tile(3041, 3202)
        val OBJECTIVE = Tile(3041, 3202) // the dock the raiders push for / loiter on when it falls
        const val ACTIVATION_RADIUS = 40

        /** The garrison's dock-side posts (tile + facing). */
        val DEFENDER_POSTS = listOf(
            Tile(3040, 3199) to Direction.NORTH,
            Tile(3043, 3202) to Direction.NORTH,
            Tile(3039, 3205) to Direction.EAST,
            Tile(3044, 3197) to Direction.NORTH,
        )

        /** Where raid parties appear — the Falador road north of the docks, pushing south. */
        val STAGING_TILES = listOf(
            Tile(3033, 3224), Tile(3035, 3226), Tile(3037, 3223),
            Tile(3034, 3228), Tile(3036, 3230), Tile(3038, 3227),
        )

        // --- the balance (TUNE): defenders lose 6v4, win 4v3 — a player's 2-3 kills turn the tide ---
        const val DEFENDER_NPC = "npc.knight_of_saradomin" // renamed "Knight of Lumbridge"
        const val DEF_ATK = 60; const val DEF_STR = 55; const val DEF_DEF = 60; const val DEF_HP = 70

        const val RAIDER_NPC = "npc.black_knight" // cache name ≠ rogue FAMILY → no rogue-tally credit
        const val RAIDER_NAME = "Rogue Raider"
        const val RAIDERS_PER_WAVE = 6
        const val RDR_ATK = 64; const val RDR_STR = 58; const val RDR_DEF = 60; const val RDR_HP = 75

        const val ENGAGE_RANGE = 12
        const val DEFENDER_LEASH = 14
        const val RAIDER_LEASH = 30 // generous — they cross the port pushing for the dock
        const val DEFEND_RANGE = 12

        // --- cadence (world cycles, 600ms each; all TUNE) ---
        const val FIRST_WAVE_DELAY = 25   // ~15s after someone first arrives, the raid hits
        const val LULL_CYCLES = 150       // ~90s of held docks between waves
        const val OVERRUN_CYCLES = 100    // ~60s of raider-held docks before knights reinforce
        const val DEFENDER_RESPAWN_TICKS = 4 // plugin-tick countdown (~12s) per fallen knight, between waves

        /** Small, repeatable — the march-participation tier (see CapturePayout: MARCH prestige = 5). */
        const val WAVE_WAR_EFFORT = 5
    }
}
