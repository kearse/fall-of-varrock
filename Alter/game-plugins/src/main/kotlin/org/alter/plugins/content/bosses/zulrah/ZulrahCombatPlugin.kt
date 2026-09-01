package org.alter.plugins.content.bosses.zulrah

import org.alter.api.ProjectileType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

/**
 * The **Zulrah rotation machine** — Kronos rev-184 `Zulrah.java` translated onto Alter.
 * The four fixed rotations, the U-shaped toxic-fume grid, snakeling slots, the jad
 * phase's alternating styles and the magma lunge are all the donor's, ids and offsets
 * verbatim (anims 5068-5073/5804/5806, gfx 1044-1047, fume object 11700).
 *
 * Engine adaptation (guide-documented): Kronos `npc.transform(id)` becomes
 * remove-and-spawn — the shared [ZulrahState] rides an attr onto each new form and the
 * CURRENT HP CARRIES OVER, while per-form combat defs supply each form's weaknesses and
 * attack speed. Transitions are driven from `world.queue` so removing the old npc can't
 * kill the driver.
 */
class ZulrahCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (key in listOf("npc.zulrah", "npc.zulrah_2043", "npc.zulrah_2044")) {
            onNpcCombat(key) {
                npc.queue { npc.combat(this) }
            }
        }

        onNpcCombat("npc.snakeling") {
            npc.queue { npc.snakelingCombat(this) }
        }
    }

    // ───────────────────────────── the boss loop ─────────────────────────────

    private suspend fun Npc.combat(task: QueueTask) {
        val state = attr[ZULRAH_STATE] ?: return
        var target = getCombatTarget() ?: return

        while (canEngageCombat(target)) {
            facePawn(target)
            if (isAttackDelayReady()) {
                val phase = state.phase()
                state.attacks++
                if (state.attacks >= (if (phase.form == Form.JAD) 12 else 8)) {
                    transition(state, target as? Player)
                    return // the next form's combat loop takes over
                }
                // Fume clouds and snakelings interleave with attacks on every form EXCEPT
                // the jad phase (Kronos gates checkSnakelings/checkFumes the same way).
                if (phase.form != Form.JAD && (checkSnakelings(state, target) || checkFumes(state))) {
                    postAttackLogic(target)
                } else {
                    when (phase.form) {
                        Form.SERPENTINE -> rangedShot(target)
                        Form.TANZANITE -> if (world.chance(1, 2)) rangedShot(target) else magicShot(target)
                        Form.MAGMA -> {
                            meleeLunge(state, target)
                            postAttackLogic(target)
                            task.wait(6) // the lunge is a 6-tick commitment (Kronos transitioning.delay(6))
                            target = getCombatTarget() ?: break
                            continue
                        }
                        Form.JAD -> {
                            if (state.jadUseRange) rangedShot(target) else magicShot(target)
                            state.jadUseRange = !state.jadUseRange
                        }
                    }
                    postAttackLogic(target)
                }
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }

        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.rangedShot(target: Pawn) {
        animate(5069)
        if (bossProjectile(target, CombatClass.RANGED, maxHit = 41, gfx = 1044)) {
            maybeEnvenom(target)
        }
    }

    private fun Npc.magicShot(target: Pawn) {
        animate(5069)
        if (bossProjectile(target, CombatClass.MAGIC, maxHit = 41, gfx = 1046)) {
            maybeEnvenom(target)
        }
    }

    /** Kronos: every landed hit has a 20% chance to envenom. */
    private fun Npc.maybeEnvenom(target: Pawn) {
        if (world.chance(1, 5)) Poison.venom(target)
    }

    /**
     * The magma lunge (Kronos `meleeAttack`): Zulrah rears toward the tile you're on;
     * five ticks later, anyone still within 1 of it takes 20-41 THROUGH prayer and
     * defence and is stunned. Costs an extra attack from the phase counter.
     */
    private fun Npc.meleeLunge(state: ZulrahState, target: Pawn) {
        animate(5806)
        state.attacks++
        val marked = Tile(target.tile.x, target.tile.z, target.tile.height)
        val boss = this
        world.queue {
            wait(5)
            if (boss.isDead() || boss.index < 0) return@queue
            val victim = boss.getCombatTarget() ?: return@queue
            if (victim.tile.isWithinRadius(marked, 1)) {
                victim.hit(damage = 20 + world.random(21), delay = 0)
                victim.stun(cycles = 3)
                maybeEnvenom(victim)
            }
        }
    }

    // ── phase transition: submerge, move, swap form, emerge (hp + state carry over) ──

    private fun Npc.transition(state: ZulrahState, targetPlayer: Player?) {
        val next = state.rotation[(state.phaseIndex + 1) % state.rotation.size]
        animate(5072) // submerge
        resetFacePawn()
        removeCombatTarget()
        val old = this
        world.queue {
            wait(2)
            // Also bail if the old form was REMOVED (owner logged out/died during submerge —
            // the allocator's world.removeAll fires index < 0 without isDead). Spawning the
            // next form then would strand a live Zulrah in the freed instance space.
            if (old.isDead() || old.index < 0) return@queue
            val hp = old.getCurrentHp()
            world.remove(old)
            val dest = Tile(state.anchor.x + next.loc.dx, state.anchor.z + next.loc.dz, state.anchor.height)
            val boss = Npc(getRSCM(next.form.npcKey), dest, world)
            boss.respawns = false
            boss.attr[ZULRAH_STATE] = state
            world.spawn(boss)
            boss.setActive(true)
            boss.setCurrentHp(hp)
            boss.animate(5073) // emerge
            state.phaseIndex++
            state.attacks = 0
            if (next.form == Form.JAD) state.jadUseRange = JAD_RANGE_FIRST[state.rotationId]
            wait(4) // 6-tick transition total before it acts again
            val victim = targetPlayer
            if (victim != null && victim.isOnline && !victim.isDead()) {
                boss.attack(victim)
            }
        }
    }

    // ─────────────────────────── toxic fume clouds ───────────────────────────

    private fun Npc.checkFumes(state: ZulrahState): Boolean {
        val empty = emptyFumeSpots(state)
        if (!state.spawningFumes && !(state.phaseIndex == 0 || world.chance(35, 100)) ) return false
        if (empty.isEmpty()) {
            state.spawningFumes = false
            return false
        }
        state.spawningFumes = true
        val spot = empty[world.random(empty.size - 1)]
        spawnFume(state, spot)
        // Kronos pairs the neighbouring slot when it's also free.
        if ((spot + 1) in empty) spawnFume(state, spot + 1)
        if (emptyFumeSpots(state).isEmpty()) state.spawningFumes = false
        return true
    }

    private fun emptyFumeSpots(state: ZulrahState): List<Int> =
        (0 until 9).filter { !state.phase().safeFumes[it] && state.fumes[it] == null }

    private fun Npc.spawnFume(state: ZulrahState, spot: Int) {
        val t = Tile(state.anchor.x + FUME_OFFSETS[spot][0], state.anchor.z + FUME_OFFSETS[spot][1], state.anchor.height)
        animate(5069)
        world.spawn(createProjectile(t, gfx = 1045, type = ProjectileType.MAGIC))
        val boss = this
        val fumeId = getRSCM("object.null_11700")
        world.queue {
            wait(3)
            if (boss.isDead() || boss.index < 0) return@queue
            val fume = DynamicObject(fumeId, 10, 0, t)
            state.fumes[spot] = fume
            world.spawn(fume)
            // The cloud is a 3x3 (the object's footprint): venom-chip anyone inside for 25 ticks.
            repeat(25) {
                if (state.fumes[spot] !== fume) return@queue // cleaned up (boss died)
                val victim = boss.getCombatTarget()
                if (victim != null &&
                    victim.tile.x >= t.x && victim.tile.x <= t.x + 2 &&
                    victim.tile.z >= t.z && victim.tile.z <= t.z + 2
                ) {
                    victim.hit(damage = 1 + world.random(3), delay = 0)
                }
                wait(1)
            }
            if (state.fumes[spot] === fume) {
                world.remove(fume)
                state.fumes[spot] = null
            }
        }
    }

    // ───────────────────────────── snakelings ─────────────────────────────

    private fun Npc.checkSnakelings(state: ZulrahState, target: Pawn): Boolean {
        for (i in state.snakelings.indices) {
            val cur = state.snakelings[i]
            if ((cur == null || cur.isDead() || cur.index < 0) && world.chance(1, 5)) {
                launchSnakeling(state, i, target)
                return true
            }
        }
        return false
    }

    private fun Npc.launchSnakeling(state: ZulrahState, slot: Int, target: Pawn) {
        val t = world.snapToWalkable(
            Tile(target.tile.x + world.random(4) - 2, target.tile.z + world.random(4) - 2, target.tile.height),
        )
        animate(5068)
        world.spawn(createProjectile(t, gfx = 1047, type = ProjectileType.MAGIC))
        val boss = this
        world.queue {
            wait(4)
            if (boss.isDead() || boss.index < 0) return@queue
            val victim = boss.getCombatTarget() ?: return@queue
            val snakeling = Npc(getRSCM("npc.snakeling"), t, world)
            snakeling.respawns = false
            // Coin-flip fighting style for its lifetime (Kronos ZulrahSnakeling.init).
            snakeling.attr[SNAKELING_MAGIC] = world.chance(1, 2)
            state.snakelings[slot] = snakeling
            world.spawn(snakeling)
            snakeling.setActive(true)
            snakeling.animate(2413) // burrow up
            snakeling.attack(victim)
        }
    }

    private suspend fun Npc.snakelingCombat(task: QueueTask) {
        val magic = attr[SNAKELING_MAGIC] ?: false
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = if (magic) 8 else 1, projectile = magic) && isAttackDelayReady()) {
                animate(1741)
                val landed =
                    if (magic) {
                        bossProjectile(target, CombatClass.MAGIC, maxHit = 15, gfx = 1044)
                    } else {
                        bossMelee(target, maxHit = 15, style = CombatStyle.STAB)
                    }
                // Kronos: 15% envenom per snakeling attack.
                if (landed && world.chance(3, 20)) Poison.venom(target)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ─────────────────────────── encounter lifecycle ───────────────────────────

    companion object {
        /** Kronos fumeOffsets — the U-shaped cloud grid, offsets from the CENTER anchor:
         *  west column, south row, east column. */
        private val FUME_OFFSETS = arrayOf(
            intArrayOf(-4, 3), intArrayOf(-4, 0), intArrayOf(-4, -3),
            intArrayOf(-1, -4), intArrayOf(2, -4), intArrayOf(5, -4),
            intArrayOf(6, -1), intArrayOf(6, 2), intArrayOf(6, 5),
        )

        private val JAD_RANGE_FIRST = booleanArrayOf(true, true, false, false)

        val ZULRAH_STATE = AttributeKey<ZulrahState>()
        val SNAKELING_MAGIC = AttributeKey<Boolean>()

        /** Spawn the serpentine form at [anchor] with a freshly rolled rotation. */
        fun beginEncounter(world: World, anchor: Tile) {
            val state = ZulrahState(anchor, world.random(3))
            val boss = Npc(getRSCM("npc.zulrah"), anchor, world)
            boss.respawns = false
            boss.attr[ZULRAH_STATE] = state
            world.spawn(boss)
            boss.setActive(true)
            boss.animate(5071) // slow emerge
        }

        /** Boss died: clear its snakelings and fume clouds, spawn the exit portal. */
        fun cleanupEncounter(world: World, boss: Npc) {
            val state = boss.attr[ZULRAH_STATE] ?: return
            state.snakelings.forEachIndexed { i, s ->
                if (s != null && !s.isDead() && s.index >= 0) world.remove(s)
                state.snakelings[i] = null
            }
            state.fumes.forEachIndexed { i, f ->
                if (f != null) world.remove(f)
                state.fumes[i] = null
            }
            world.spawn(
                DynamicObject(
                    getRSCM("object.zulandra_teleport_11701"), 10, 0,
                    Tile(state.anchor.x + 2, state.anchor.z - 3, state.anchor.height),
                ),
            )
        }
    }

    // ─────────────────────────── rotation data ───────────────────────────

    enum class Form(val npcKey: String) {
        SERPENTINE("npc.zulrah"),
        MAGMA("npc.zulrah_2043"),
        TANZANITE("npc.zulrah_2044"),
        JAD("npc.zulrah"),
    }

    enum class Loc(val dx: Int, val dz: Int) {
        CENTER(0, 0),
        SOUTH(0, -9),
        EAST(10, -1),
        WEST(-10, -1),
    }

    /** One phase of a rotation: a form, a stand location, and which fume slots stay clear. */
    class RotationPhase(val form: Form, val loc: Loc, vararg safe: Int) {
        val safeFumes = BooleanArray(9).also { arr -> safe.forEach { arr[it] = true } }
    }

    /** The mutable, form-independent encounter state; rides an attr across form swaps. */
    class ZulrahState(val anchor: Tile, val rotationId: Int) {
        val rotation: Array<RotationPhase> = ROTATIONS[rotationId]
        var phaseIndex = 0
        var attacks = 0
        var jadUseRange = false
        var spawningFumes = false
        val snakelings = arrayOfNulls<Npc>(3)
        val fumes = arrayOfNulls<DynamicObject>(9)

        fun phase(): RotationPhase = rotation[phaseIndex % rotation.size]
    }
}

/** Kronos ROTATIONS, verbatim (safe-slot helpers: west = 0,1,2 · centre = 3,4,5 · east = 6,7,8). */
private val ROTATIONS: Array<Array<ZulrahCombatPlugin.RotationPhase>> = run {
    val n = ZulrahCombatPlugin.Form.SERPENTINE
    val m = ZulrahCombatPlugin.Form.MAGMA
    val t = ZulrahCombatPlugin.Form.TANZANITE
    val j = ZulrahCombatPlugin.Form.JAD
    val c = ZulrahCombatPlugin.Loc.CENTER
    val s = ZulrahCombatPlugin.Loc.SOUTH
    val e = ZulrahCombatPlugin.Loc.EAST
    val w = ZulrahCombatPlugin.Loc.WEST
    fun p(form: ZulrahCombatPlugin.Form, loc: ZulrahCombatPlugin.Loc, vararg safe: Int) =
        ZulrahCombatPlugin.RotationPhase(form, loc, *safe)
    arrayOf(
        arrayOf(
            p(n, c, 8), p(m, c, 8), p(t, c, 8), p(n, s, 0, 1, 2), p(m, c, 1),
            p(t, w, 1), p(n, s, 6, 7, 8), p(t, s, 0, 1, 2), p(j, w, 0), p(m, c, 0, 1),
        ),
        arrayOf(
            p(n, c, 8), p(m, c, 8), p(t, c, 8), p(n, w, 0, 1, 2), p(t, s, 1),
            p(m, c, 1), p(n, e, 4), p(t, s, 0, 1, 2), p(j, w, 0), p(m, c, 0, 1),
        ),
        arrayOf(
            p(n, c, 8), p(n, e, 8), p(m, c, 6, 7, 8), p(t, w, 3, 4, 5), p(n, s, 3, 4, 5),
            p(t, e, 4, 5), p(n, c, 0, 1, 2), p(n, w, 0, 1, 2), p(t, c, 6, 7, 8),
            p(j, e, 6, 7, 8), p(t, c, 6, 7, 8),
        ),
        arrayOf(
            p(n, c, 8), p(t, e, 8), p(n, s, 1), p(t, w, 1), p(m, c, 6, 7, 8),
            p(n, e, 6, 7, 8), p(n, s, 3, 4, 5), p(t, w, 0, 1, 2), p(n, c, 6, 7, 8),
            p(t, c, 6, 7, 8), p(j, e, 6, 7, 8), p(t, c, 6, 7, 8),
        ),
    )
}
