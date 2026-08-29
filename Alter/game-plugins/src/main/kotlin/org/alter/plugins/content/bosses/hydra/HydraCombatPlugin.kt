package org.alter.plugins.content.bosses.hydra

import org.alter.api.ProjectileType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.min

/**
 * The **Alchemical Hydra phase machine** — Kronos rev-184 `AlchemicalHydra.java`
 * translated onto Alter. Phase thresholds, the chemical-vent economy (neutralise its 4×
 * resistance / feed it power), attack cadence (special every 9th, style flip every 3rd —
 * every attack when enraged), the poison pools, the walking lightning and the fire
 * lockdown are the donor's; ids and offsets verbatim.
 *
 * Guide-documented adaptations:
 *  - `npc.transform` → remove-and-spawn with shared [HydraState] + HP carry-over; the
 *    lose-head transition swaps through the donor's headless npc (8616/8617/8618) so the
 *    decapitation anims play on the right rig.
 *  - The 4× resistance rides [Combat.DAMAGE_TAKE_MULTIPLIER] (×0.25) — honoured by all
 *    three player formulas.
 *  - Fire coverage is tracked as a burning-tile set (donor re-simulates the wave paths);
 *    vent spew windows keep the donor's 10-tick cycle but skip the object animation (no
 *    object-anim API — the vents' mechanics and chat feedback are all there).
 */
class HydraCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (form in HydraForm.entries) {
            onNpcCombat(form.npcKey) {
                npc.queue { npc.combat(this) }
            }
        }
    }

    // ───────────────────────────── the boss loop ─────────────────────────────

    private suspend fun Npc.combat(task: QueueTask) {
        val state = attr[HYDRA_STATE] ?: return
        var target = getCombatTarget() ?: return

        while (canEngageCombat(target)) {
            facePawn(target)

            // Phase thresholds (Kronos postDamage): green <75%, blue <50%, red <25%.
            val ratio = getCurrentHp().toDouble() / getMaxHp().toDouble()
            if (ratio > 0.0 && ratio < state.form.threshold) {
                transition(state, target as? Player)
                return // the next form's combat loop takes over
            }

            val range = if (state.firesActive) 16 else 6
            if (moveToAttackRange(task, target, distance = range, projectile = true) && isAttackDelayReady()) {
                if (state.attackCounter % 9 == 0 && state.attackCounter != state.lastSpecial) {
                    state.lastSpecial = state.attackCounter
                    when (state.form) {
                        HydraForm.GREEN, HydraForm.GREY -> poisonSpecial(state, target)
                        HydraForm.BLUE -> lightningSpecial(state, target)
                        HydraForm.RED -> fireSpecial(state, target)
                    }
                } else {
                    if (state.magicStyle) magicAttack(state, target) else rangedAttack(state, target)
                    state.attackCounter++
                    // Style flips every 3rd attack — every attack when enraged (Kronos switchStyle).
                    if (state.form == HydraForm.GREY || state.attackCounter % 3 == 0) {
                        state.magicStyle = !state.magicStyle
                    }
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }

        resetFacePawn()
        removeCombatTarget()
    }

    /** Max hit scaled by absorbed vent power: +6.25% per stack, capped +50% (Kronos). */
    private fun HydraState.scaledMax(): Int = (BASE_MAX * (1.0 + min(0.5, power * 0.0625))).toInt()

    private fun Npc.rangedAttack(state: HydraState, target: Pawn) {
        animate(state.form.rightHeadsAnim)
        val max = state.scaledMax()
        // Green and blue spit TWO half-power ranged bolts; red/grey one full-power (Kronos).
        if (state.form == HydraForm.GREEN || state.form == HydraForm.BLUE) {
            bossProjectile(target, CombatClass.RANGED, maxHit = max / 2, gfx = 1663)
            bossProjectile(target, CombatClass.RANGED, maxHit = max / 2, gfx = 1663)
        } else {
            bossProjectile(target, CombatClass.RANGED, maxHit = max, gfx = 1663)
        }
    }

    private fun Npc.magicAttack(state: HydraState, target: Pawn) {
        animate(state.form.leftHeadsAnim)
        val max = state.scaledMax()
        // Only green doubles its magic attack (Kronos).
        if (state.form == HydraForm.GREEN) {
            bossProjectile(target, CombatClass.MAGIC, maxHit = max / 2, gfx = 1662)
            bossProjectile(target, CombatClass.MAGIC, maxHit = max / 2, gfx = 1662)
        } else {
            bossProjectile(target, CombatClass.MAGIC, maxHit = max, gfx = 1662)
        }
    }

    // ── special: poison pools (green + enraged) ──

    private fun Npc.poisonSpecial(state: HydraState, target: Pawn) {
        animate(if (state.form == HydraForm.GREEN) state.form.middleHeadAnim else state.form.leftHeadsAnim)
        val tiles = mutableListOf(Tile(target.tile.x, target.tile.z, target.tile.height))
        // Green always splatters 4 extra pools around the player; enraged does 50% of the time.
        if (state.form != HydraForm.GREY || world.chance(1, 2)) {
            repeat(4) {
                tiles += world.snapToWalkable(
                    Tile(target.tile.x + world.random(6) - 3, target.tile.z + world.random(6) - 3, target.tile.height),
                )
            }
        }
        val boss = this
        tiles.forEach { t ->
            world.spawn(createProjectile(t, gfx = 1644, type = ProjectileType.MAGIC))
            world.queue {
                wait(3)
                world.spawn(TileGraphic(t, 1645, 0))
                world.spawn(TileGraphic(t, POOL_GFX, 0))
                // The pool burns for 15 ticks: 1-12 poison-chip while standing in it
                // (radius 1 on the first tick — the splash — then the tile itself).
                repeat(15) { i ->
                    val victim = boss.getCombatTarget()
                    if (victim != null && victim.tile.isWithinRadius(t, if (i == 0) 1 else 0)) {
                        victim.hit(damage = 1 + world.random(11), delay = 0)
                    }
                    wait(1)
                }
            }
        }
    }

    // ── special: walking lightning (blue) ──

    private fun Npc.lightningSpecial(state: HydraState, target: Pawn) {
        animate(state.form.middleHeadAnim)
        val src = Tile(state.anchor.x + 2, state.anchor.z + 2, state.anchor.height)
        world.spawn(createProjectile(src, gfx = 1665, type = ProjectileType.MAGIC))
        world.spawn(TileGraphic(src, 1664, 0, delay = 30))
        val boss = this
        for (offset in SHOCK_SPAWN_POINTS) {
            world.queue {
                wait(3)
                var shock = Tile(state.anchor.x + offset[0], state.anchor.z + offset[1], state.anchor.height)
                wait(2)
                // Each bolt walks one tile per tick toward the player; catching them
                // deals 20 and roots them for 4 ticks (Kronos shockAttack).
                repeat(12) {
                    val victim = boss.getCombatTarget() ?: return@queue
                    if (boss.isDead() || boss.index < 0) return@queue
                    val dx = Integer.signum(victim.tile.x - shock.x)
                    val dz = Integer.signum(victim.tile.z - shock.z)
                    if (dx != 0 || dz != 0) {
                        shock = Tile(shock.x + dx, shock.z + dz, shock.height)
                        world.spawn(TileGraphic(shock, 1666, 0))
                        if (victim.tile.sameAs(shock)) {
                            victim.hit(damage = world.random(20), delay = 0)
                            victim.freeze(cycles = 4) {
                                (victim as? Player)?.message("<col=ff0000>The electricity temporarily paralyzes you!</col>")
                            }
                            return@queue
                        }
                    }
                    wait(1)
                }
            }
        }
    }

    // ── special: fire lockdown (red) ──

    private fun Npc.fireSpecial(state: HydraState, target: Pawn) {
        val boss = this
        state.firesActive = true
        boss.moveTo(state.anchor) // the donor walks it home; we snap it (guide adaptation)
        facePawn(target)
        world.queue {
            wait(1)
            if (boss.isDead() || boss.index < 0 || state.form != HydraForm.RED) {
                state.firesActive = false
                return@queue
            }
            val victim = boss.getCombatTarget() ?: run { state.firesActive = false; return@queue }

            // Root the player where they stand, then wall off their octant's neighbours.
            victim.freeze(cycles = 6) {
                (victim as? Player)?.message("<col=ff0000>The Alchemical Hydra temporarily stuns you!</col>")
            }
            val area = FireArea.of(victim.tile, state.anchor) ?: FireArea.WEST
            for (adjacent in area.adjacents()) {
                boss.animate(state.form.middleHeadAnim)
                adjacent.coverInFire(world, state)
                wait(3)
                if (boss.isDead() || boss.index < 0 || state.form != HydraForm.RED) break
            }

            // The tracking fire: chases the player's tile one step per tick, igniting
            // its trail; standing still two ticks running gets you clipped for 20 + burns.
            var fire = Tile(state.anchor.x + area.waveStart[0][0], state.anchor.z + area.waveStart[0][1], state.anchor.height)
            world.spawn(createProjectile(fire, gfx = 1667, type = ProjectileType.MAGIC))
            var life = 18
            var buildUp = 0
            while (life > 0 && !boss.isDead() && boss.index >= 0 && state.form == HydraForm.RED) {
                val chased = boss.getCombatTarget() ?: break
                val dx = Integer.signum(chased.tile.x - fire.x)
                val dz = Integer.signum(chased.tile.z - fire.z)
                if (dx == 0 && dz == 0) {
                    if (++buildUp >= 2) {
                        chased.hit(damage = world.random(20), delay = 0)
                        buildUp = 0
                        life -= 3
                        wait(2)
                    }
                } else {
                    fire = Tile(fire.x + dx, fire.z + dz, fire.height)
                    world.spawn(TileGraphic(fire, 1668, 0))
                    state.burning += fire
                }
                life--
                wait(1)
            }

            // Burn-out: fire persists ~40 ticks from ignition; 5/tick while standing in it.
            repeat(40) {
                val v = boss.getCombatTarget()
                if (v != null && v.tile in state.burning) {
                    v.hit(damage = 5, delay = 0)
                }
                wait(1)
            }
            state.burning.clear()
            state.firesActive = false
        }
    }

    // ── phase transition: lose-head swap through the headless npc, then the next form ──

    private fun Npc.transition(state: HydraState, targetPlayer: Player?) {
        val current = state.form
        val next = HydraForm.entries[current.ordinal + 1]
        resetFacePawn()
        removeCombatTarget()
        val old = this
        world.queue {
            val hp = old.getCurrentHp()
            world.remove(old)
            // The decapitation plays on the donor's dedicated headless rig.
            val headless = if (current.headlessKey != null) {
                val h = Npc(getRSCM(current.headlessKey), Tile(old.tile.x, old.tile.z, old.tile.height), world)
                h.respawns = false
                world.spawn(h)
                h.setActive(true)
                h.animate(current.loseHeadAnim)
                h
            } else {
                null
            }
            wait(maxOf(1, current.loseHeadTicks))
            headless?.let { world.remove(it) }

            state.form = next
            if (next == HydraForm.GREY) {
                // Enraged: permanently empowered, never resistant, cadence resets (Kronos).
                state.power = 8
                state.resistant = false
                state.attackCounter = -3
                state.lastSpecial = -1
                state.magicStyle = !state.magicStyle
            } else {
                state.resistant = true
                state.power = 0
            }

            val boss = Npc(getRSCM(next.npcKey), Tile(state.anchor.x, state.anchor.z, state.anchor.height), world)
            boss.respawns = false
            boss.attr[HYDRA_STATE] = state
            state.applyResistance(boss)
            world.spawn(boss)
            boss.setActive(true)
            boss.setCurrentHp(hp)
            state.currentBoss = boss
            if (next.fadeInAnim != -1) boss.animate(next.fadeInAnim)
            wait(2)
            val victim = targetPlayer
            if (victim != null && victim.isOnline && !victim.isDead()) {
                boss.attack(victim)
            }
        }
    }

    // ─────────────────────────── encounter lifecycle ───────────────────────────

    companion object {
        private const val BASE_MAX = 35 // Kronos max_damage

        /** Kronos SHOCK_SPAWN_POINTS — lightning bolt origins, offsets from the anchor. */
        private val SHOCK_SPAWN_POINTS = arrayOf(
            intArrayOf(7, -1), intArrayOf(8, 7), intArrayOf(-2, 8), intArrayOf(-2, -1),
        )

        /** One directional pool sprite (the donor picks one of 8 by facing; we keep it simple). */
        private const val POOL_GFX = 1656

        val HYDRA_STATE = AttributeKey<HydraState>()

        /** Spawn the green form at [anchor] and start the vent cycles. */
        fun beginEncounter(world: World, anchor: Tile) {
            val state = HydraState(anchor, magicStyle = world.chance(1, 2))
            val boss = Npc(getRSCM(HydraForm.GREEN.npcKey), anchor, world)
            boss.respawns = false
            boss.attr[HYDRA_STATE] = state
            state.applyResistance(boss)
            world.spawn(boss)
            boss.setActive(true)
            state.currentBoss = boss
            startVents(world, state)
        }

        /** Boss died (any form): stop the vents and clear the burn state. */
        fun cleanupEncounter(boss: Npc) {
            val state = boss.attr[HYDRA_STATE] ?: return
            state.ended = true
            state.burning.clear()
        }

        fun anchorOf(boss: Npc): Tile? = boss.attr[HYDRA_STATE]?.anchor

        /**
         * The three chemical vents (they exist in the copied map): each spews on a 10-tick
         * cycle. Standing ON a spewing vent burns you for 20. If the HYDRA is over a
         * spewing vent: its own form's vent NEUTRALISES its 4× resistance; any other vent
         * EMPOWERS it (+6.25% damage, stacking to +50%). Kronos startVents, minus the
         * object animation (no object-anim API — feedback is damage + chat).
         */
        private fun startVents(world: World, state: HydraState) {
            for ((ventKey, offsets) in VENTS) {
                val ventTile = Tile(state.anchor.x + offsets[0], state.anchor.z + offsets[1], state.anchor.height)
                world.queue {
                    wait(6)
                    while (!state.ended) {
                        val boss = state.currentBoss
                        if (boss != null && !boss.isDead() && boss.index >= 0) {
                            val victim = boss.getCombatTarget()
                            if (victim != null && victim.tile.sameAs(ventTile)) {
                                victim.hit(damage = world.random(20), delay = 0)
                                (victim as? Player)?.message("The chemical burns you as it cascades over you.")
                            }
                            if (bossOverVent(boss, ventTile)) {
                                if (ventKey == state.form.ventKey) {
                                    if (state.resistant) {
                                        state.resistant = false
                                        state.applyResistance(boss)
                                        (victim as? Player)?.message("The chemicals neutralise the Alchemical Hydra's defences!")
                                    }
                                } else if (state.form != HydraForm.GREY) {
                                    state.power++
                                    (victim as? Player)?.message("The chemicals are absorbed by the Alchemical Hydra; empowering it further!")
                                }
                            }
                        }
                        wait(10)
                    }
                }
            }
        }

        /** Kronos: the hydra's INNER footprint (edges shaved) intersecting vent±2. */
        private fun bossOverVent(boss: Npc, vent: Tile): Boolean {
            val size = boss.getSize()
            for (x in (boss.tile.x + 1)..(boss.tile.x + size - 2)) {
                for (z in (boss.tile.z + 1)..(boss.tile.z + size - 2)) {
                    if (Math.abs(x - vent.x) <= 2 && Math.abs(z - vent.z) <= 2) return true
                }
            }
            return false
        }

        /** vent object key → offset from the anchor (Kronos init: 34568 @ +7,-2 · 34569 @ +7,+7 · 34570 @ -2,+7). */
        private val VENTS = listOf(
            "object.chemical_vent_red" to intArrayOf(7, -2),
            "object.chemical_vent_green" to intArrayOf(7, 7),
            "object.chemical_vent_blue" to intArrayOf(-2, 7),
        )
    }

    // ─────────────────────────── forms & state ───────────────────────────

    /**
     * The donor's Form table, verbatim: fighting npc, headless transition npc + its
     * decapitation anim/ticks, fade-in anim, the three head-group attack anims, the
     * weakness vent, and the HP fraction below which the NEXT form takes over.
     */
    enum class HydraForm(
        val npcKey: String,
        val headlessKey: String?,
        val loseHeadAnim: Int,
        val loseHeadTicks: Int,
        val fadeInAnim: Int,
        val middleHeadAnim: Int,
        val rightHeadsAnim: Int,
        val leftHeadsAnim: Int,
        val ventKey: String?,
        val threshold: Double,
    ) {
        GREEN("npc.alchemical_hydra", "npc.alchemical_hydra_8616", 8237, 3, -1, 8234, 8235, 8236, "object.chemical_vent_red", 0.75),
        BLUE("npc.alchemical_hydra_8619", "npc.alchemical_hydra_8617", 8244, 2, 8238, 8241, 8242, 8243, "object.chemical_vent_green", 0.50),
        RED("npc.alchemical_hydra_8620", "npc.alchemical_hydra_8618", 8251, 3, 8245, 8248, 8249, 8250, "object.chemical_vent_blue", 0.25),
        GREY("npc.alchemical_hydra_8621", null, -1, 0, 8252, 8256, 8256, 8255, null, 0.0),
    }

    /** Mutable encounter state; rides an attr across form swaps (the Zulrah pattern). */
    class HydraState(val anchor: Tile, var magicStyle: Boolean) {
        var form = HydraForm.GREEN
        var attackCounter = -3 // first special lands after three basic attacks (Kronos)
        var lastSpecial = -1
        var resistant = true
        var power = 0
        var firesActive = false
        var ended = false
        var currentBoss: Npc? = null
        val burning = mutableSetOf<Tile>()

        /** Mirror [resistant] into the take-multiplier every formula honours. */
        fun applyResistance(boss: Npc) {
            if (resistant && form != HydraForm.GREY) {
                boss.attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.25
            } else {
                boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            }
        }
    }

    // ─────────────────────────── the fire octants ───────────────────────────

    /**
     * Kronos FireArea, data verbatim: each octant of the room (bounds are offsets from
     * the anchor) with the wall line its fire wave ignites and the direction the wave
     * marches. Coverage is recorded into [HydraState.burning] instead of re-simulated.
     */
    enum class FireArea(
        val swX: Int, val swY: Int, val neX: Int, val neY: Int,
        val waveStart: Array<IntArray>, val waveStep: IntArray,
    ) {
        WEST(-8, 0, -1, 4, arrayOf(intArrayOf(-1, 4), intArrayOf(-1, 3), intArrayOf(-1, 2), intArrayOf(-1, 1)), intArrayOf(-1, 0)),
        NORTH_WEST(-8, 5, -1, 14, arrayOf(intArrayOf(-1, 4), intArrayOf(-1, 5), intArrayOf(-1, 6), intArrayOf(0, 6), intArrayOf(1, 6)), intArrayOf(-1, 1)),
        NORTH(0, 5, 5, 14, arrayOf(intArrayOf(1, 6), intArrayOf(2, 6), intArrayOf(3, 6), intArrayOf(4, 6)), intArrayOf(0, 1)),
        NORTH_EAST(6, 5, 13, 13, arrayOf(intArrayOf(6, 4), intArrayOf(6, 5), intArrayOf(6, 6), intArrayOf(5, 6), intArrayOf(4, 6)), intArrayOf(1, 1)),
        EAST(5, 0, 13, 5, arrayOf(intArrayOf(6, 1), intArrayOf(6, 2), intArrayOf(6, 3), intArrayOf(6, 4)), intArrayOf(1, 0)),
        SOUTH_EAST(5, -8, 13, -1, arrayOf(intArrayOf(6, 1), intArrayOf(6, 0), intArrayOf(6, -1), intArrayOf(5, -1), intArrayOf(4, -1)), intArrayOf(1, -1)),
        SOUTH(0, -9, 5, 0, arrayOf(intArrayOf(1, -1), intArrayOf(2, -1), intArrayOf(3, -1), intArrayOf(4, -1)), intArrayOf(0, -1)),
        SOUTH_WEST(-8, -8, 0, -1, arrayOf(intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1)), intArrayOf(-1, -1)),
        ;

        fun adjacents(): List<FireArea> {
            val all = entries
            val prev = (ordinal - 1 + all.size) % all.size
            val next = (ordinal + 1) % all.size
            return listOf(all[prev], all[next])
        }

        /** March this octant's wall line 8 steps, igniting every covered tile. */
        fun coverInFire(world: World, state: HydraState) {
            val wave = waveStart.map { intArrayOf(state.anchor.x + it[0], state.anchor.z + it[1]) }
            repeat(8) { step ->
                wave.forEach { pos ->
                    val t = Tile(pos[0], pos[1], state.anchor.height)
                    world.spawn(TileGraphic(t, 1668, 0, delay = 50 + step * 5))
                    state.burning += t
                    pos[0] += waveStep[0]
                    pos[1] += waveStep[1]
                }
            }
        }

        companion object {
            fun of(playerTile: Tile, anchor: Tile): FireArea? =
                entries.firstOrNull { a ->
                    playerTile.x >= anchor.x + a.swX && playerTile.x <= anchor.x + a.neX &&
                        playerTile.z >= anchor.z + a.swY && playerTile.z <= anchor.z + a.neY
                }
        }
    }
}
