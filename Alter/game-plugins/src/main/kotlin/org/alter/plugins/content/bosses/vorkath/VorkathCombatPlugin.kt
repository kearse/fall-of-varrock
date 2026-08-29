package org.alter.plugins.content.bosses.vorkath

import org.alter.api.ProjectileType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.AttackStyle
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
import org.alter.plugins.content.combat.formula.DragonfireFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.max

/**
 * The **Vorkath fight** — a mechanical translation of Kronos rev-184's
 * `Vorkath.java`/`ZombifiedSpawn.java` onto Alter's combat DSL. Structure, ids, damage
 * ranges and timings are the donor's; every engine call is ours.
 *
 * Rotation (Kronos `attack()`): six regular attacks, then a special — alternating the
 * ACID phase and the ZOMBIFIED-SPAWN phase. Regulars: melee 1-in-3 when adjacent, else
 * a d100 → fireball-dodge (>90), venom breath (>80), prayer-disabling purple breath
 * (>70), dragonfire (>60), magic shot (>30), ranged shot.
 *
 * Immunity states ride [Combat.DAMAGE_TAKE_MULTIPLIER] (×0 spawn phase, ×0.5 acid
 * phase), which all three player formulas honour.
 *
 * Adaptation: our spellbook has no Crumble Undead, so the spawn's one-shot hook is
 * dropped — it dies fast anyway (38 hp, −100 magic defence).
 */
class VorkathCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat("npc.vorkath_8061") {
            npc.queue { npc.combat(this) }
        }

        onNpcCombat("npc.zombified_spawn_8063") {
            npc.queue { npc.spawnCombat(this) }
        }
    }

    // ───────────────────────────── Vorkath ─────────────────────────────

    private suspend fun Npc.combat(task: QueueTask) {
        var target = getCombatTarget() ?: return

        while (canEngageCombat(target)) {
            facePawn(target)

            // A special's own event drives the fight while an immunity state is up.
            if ((attr[VORKATH_IMMUNITY] ?: IMMUNITY_NONE) != IMMUNITY_NONE) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }

            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                val count = attr[VORKATH_COUNTER] ?: 0
                if (count > 0 && count % 6 == 0) {
                    val acid = attr[VORKATH_ACID_NEXT] ?: world.chance(1, 2)
                    if (acid) acidSpecial(target) else spawnSpecial(target)
                    attr[VORKATH_ACID_NEXT] = !acid
                    attr[VORKATH_COUNTER] = 1
                } else {
                    regularAttack(task, target)
                    attr[VORKATH_COUNTER] = count + 1
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }

        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.regularAttack(task: QueueTask, target: Pawn) {
        if (world.chance(1, 3) && canAttackMelee(task, target, moveIfNeeded = false)) {
            animate(7951)
            bossMelee(target, maxHit = 32, style = CombatStyle.SLASH)
            return
        }
        val roll = world.random(100)
        when {
            roll > 90 -> fireballDodge(target)
            roll > 80 -> breath(target, gfx = 1470, impact = 1472, maxHit = 60) { landed ->
                // Kronos: 3-in-4 to envenom on the venom breath.
                if (landed && world.chance(3, 4)) Poison.venom(target)
            }
            roll > 70 -> breath(target, gfx = 1471, impact = 1473, maxHit = 70) { _ ->
                // The purple breath strips overheads whether or not damage got through.
                if (target is Player) {
                    Prayers.deactivateAll(target)
                    target.message("<col=ff0000>Your prayers have been disabled!</col>")
                }
            }
            roll > 60 -> breath(target, gfx = 393, impact = 157, maxHit = 80, onHit = null)
            roll > 30 -> {
                animate(BREATH_ANIM)
                bossProjectile(target, CombatClass.MAGIC, maxHit = 32, gfx = 1479)
                target.graphic(1480, 92, impactDelayClient(target))
            }
            else -> {
                animate(BREATH_ANIM)
                bossProjectile(target, CombatClass.RANGED, maxHit = 32, gfx = 1477)
                target.graphic(1478, 92, impactDelayClient(target))
            }
        }
    }

    /** The three dragonfire-class breaths — routed through [DragonfireFormula] so shields,
     *  antifire and Protect from Magic mitigate exactly like the KBD's. */
    private fun Npc.breath(target: Pawn, gfx: Int, impact: Int, maxHit: Int, onHit: ((Boolean) -> Unit)?) {
        animate(BREATH_ANIM)
        prepareAttack(CombatClass.MAGIC, CombatStyle.MAGIC, AttackStyle.ACCURATE)
        world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 127))
        val hit = dealHit(
            target = target,
            formula = DragonfireFormula(maxHit = maxHit),
            delay = tickDelayTo(target),
        ) { if (onHit != null) onHit(it.landed()) }
        target.graphic(impact, 92, hit.getClientHitDelay())
    }

    /** The skull fireball: lands on the tile you were standing on — move 2+ tiles or take
     *  20-60 typeless (Kronos `fireballAttack`). */
    private fun Npc.fireballDodge(target: Pawn) {
        animate(7960)
        val marked = Tile(target.tile.x, target.tile.z, target.tile.height)
        world.spawn(createProjectile(marked, gfx = 1481, type = ProjectileType.MAGIC))
        world.spawn(TileGraphic(marked, 157, 20, delay = 60))
        val boss = this
        boss.queue {
            wait(3)
            if (boss.isDead()) return@queue
            val victim = boss.getCombatTarget() ?: return@queue
            if (victim.tile.isWithinRadius(marked, 1)) {
                victim.hit(damage = 20 + world.random(40), delay = 0)
            }
        }
    }

    // ── Special: zombified spawn (ice) phase — Kronos `zombieSpawn()`.

    private fun Npc.spawnSpecial(target: Pawn) {
        attr[VORKATH_IMMUNITY] = IMMUNITY_FULL
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        animate(7960)
        world.spawn(createProjectile(target, gfx = 395, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 127))
        target.graphic(369, 0, 60)
        target.freeze(cycles = 25) {
            (target as? Player)?.message("<col=ff0000>Vorkath's icy breath freezes you in place!</col>")
        }

        // Drop the add a few tiles off the player, inside the arena.
        val addTile = world.snapToWalkable(
            Tile(target.tile.x + (world.random(6) - 3), target.tile.z + 3 + world.random(3), target.tile.height),
        )
        world.spawn(createProjectile(addTile, gfx = 1484, type = ProjectileType.MAGIC))

        val boss = this
        boss.queue {
            wait(3)
            if (boss.isDead()) { boss.clearImmunity(); return@queue }
            val add = Npc(getRSCM("npc.zombified_spawn_8063"), addTile, world)
            add.respawns = false
            world.spawn(add)
            add.setActive(true)
            add.attack(target)
            (target as? Player)?.message("<col=ff0000>Vorkath is immune while his spawn walks — kill it before it reaches you!</col>")
            while (!add.isDead() && add.index >= 0 && !boss.isDead()) {
                wait(1)
            }
            boss.clearImmunity()
        }
    }

    // ── Special: acid-pool (woox-walk) phase — Kronos `poisonAttack()`.

    private fun Npc.acidSpecial(target: Pawn) {
        attr[VORKATH_IMMUNITY] = IMMUNITY_PARTIAL
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.5
        animate(7957)

        val boss = this
        val acidPoolId = getRSCM("object.acid_pool_32000")
        val pools = mutableListOf<DynamicObject>()

        // One pool under the player, the rest scattered over the arena floor around the
        // boss's front (Kronos throws 40 across the whole arena).
        val tiles = mutableSetOf(Tile(target.tile.x, target.tile.z, target.tile.height))
        repeat(POOL_COUNT) {
            val t = world.snapToWalkable(
                Tile(
                    tile.x + 3 + world.random(POOL_SPREAD) - POOL_SPREAD / 2,
                    tile.z - 2 - world.random(POOL_SPREAD),
                    tile.height,
                ),
            )
            tiles += t
        }
        tiles.forEach { t -> world.spawn(createProjectile(t, gfx = 1483, type = ProjectileType.MAGIC)) }

        boss.queue {
            wait(2)
            tiles.forEach { t ->
                val pool = DynamicObject(acidPoolId, 10, world.random(3), t)
                pools += pool
                world.spawn(pool)
            }
            val poolTiles = tiles.toSet()

            // 25 rapid fireballs, one per tick: each locks onto the tile you're standing
            // on and misses entirely if you've stepped off it — the woox-walk.
            repeat(FIREBALL_COUNT) {
                if (boss.isDead()) return@repeat
                val victim = boss.getCombatTarget()
                if (victim != null) {
                    // Standing in acid: chip damage that FEEDS the boss (Kronos heals the hit).
                    if (victim.tile in poolTiles) {
                        val dmg = 1 + world.random(9)
                        victim.hit(damage = dmg, delay = 0)
                        boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + dmg))
                    }
                    val marked = Tile(victim.tile.x, victim.tile.z, victim.tile.height)
                    world.spawn(boss.createProjectile(marked, gfx = 1482, type = ProjectileType.MAGIC))
                    world.spawn(TileGraphic(marked, 131, 0, delay = 60))
                    boss.queue {
                        wait(2)
                        val v = boss.getCombatTarget() ?: return@queue
                        if (!boss.isDead() && v.tile.sameAs(marked)) {
                            v.hit(damage = world.random(25), delay = 0)
                        }
                    }
                }
                wait(1)
            }

            pools.forEach { world.remove(it) }
            boss.clearImmunity()
        }
    }

    private fun Npc.clearImmunity() {
        attr[VORKATH_IMMUNITY] = IMMUNITY_NONE
        attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
    }

    // ───────────────────────── Zombified spawn ─────────────────────────

    /** Kronos `ZombifiedSpawn`: shamble to the player, then self-destruct for up to 60. */
    private suspend fun Npc.spawnCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false)) {
                animate(7890)
                world.spawn(TileGraphic(Tile(tile.x, tile.z, tile.height), 305, 50))
                target.hit(damage = world.random(60), delay = 1)
                val self = this
                self.queue {
                    wait(2)
                    if (!self.isDead()) world.remove(self)
                }
                return
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** Server-tick hit delay for a boss projectile (the BossCombat convention). */
    private fun Npc.tickDelayTo(target: Pawn): Int =
        max(1, RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1)

    /** Client-cycle delay for an impact graphic timed to the projectile's landing. */
    private fun Npc.impactDelayClient(target: Pawn): Int = tickDelayTo(target) * 30

    companion object {
        const val BREATH_ANIM = 7952 // Kronos BREATH_ANIM

        const val IMMUNITY_NONE = 0
        const val IMMUNITY_PARTIAL = 1 // acid phase: half damage
        const val IMMUNITY_FULL = 2 // spawn phase: immune

        const val POOL_COUNT = 30
        const val POOL_SPREAD = 10
        const val FIREBALL_COUNT = 25

        val VORKATH_COUNTER = AttributeKey<Int>()
        val VORKATH_ACID_NEXT = AttributeKey<Boolean>()
        val VORKATH_IMMUNITY = AttributeKey<Int>()
    }
}
