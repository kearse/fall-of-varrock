package org.alter.plugins.content.bosses.lairs

import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.DragonfireFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.mechanics.poison.Poison
import kotlin.math.max

/**
 * The lair bosses' fights — Kronos `KingBlackDragon.java`, `GiantMole.java`,
 * `KalphiteQueen.java`, the `dagannothkings` Prime/Rex/Supreme/Spinolyp scripts translated onto the
 * shared boss primitives. Structure, ids and damage ranges are the donor's.
 *
 *  - **KBD**: adjacent 1-in-4 melee (anim 80, max 25); otherwise 1-in-2 plain dragonfire
 *    (gfx 393, max 65) or one of three special breaths — freeze (396; 1-in-3 freeze), shock
 *    (395; 1-in-3 drains Att/Str/Def/Rng/Mag by 2), poison (394; 1-in-3 poisons 8). The
 *    specials keep a small floor through full dragonfire protection (10/12/10), as the donor.
 *  - **Giant Mole**: plain melee (max 21); below half health each hit taken has a 1-in-4
 *    chance to send it **burrowing** to one of eleven surfacing points around the lair
 *    (immune while under; anims 3314 down / 3315 up).
 *  - **Kalphite Queen**: adjacent 1-in-3 melee (max 31), else 1-in-3 **magic** (gfx 280,
 *    impact 281, max 31, chains to up to four players within 2 tiles of the last victim) or
 *    1-in-2 **ranged** (gfx 289, max 31, hits every player near the boss). Both forms share
 *    the rotation with their own anims/gfx. Form swap lives in [LairBossesPlugin].
 *  - **Dagannoth Kings**: Rex melee (max 26); Prime magic (gfx 162 → 477, max 50) splashing
 *    every player adjacent to the victim; Supreme ranged (gfx 475, max 30) at every player
 *    within 6 tiles. **Spinolyps**: 60% ranged (gfx 476; 1-in-3 poison 6) / 40% magic
 *    (gfx 124; drains 1 prayer), max 10, range 10, stationary.
 */
class LairBossesCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat("npc.king_black_dragon") { npc.queue { npc.kbdCombat(this) } }
        onNpcCombat("npc.giant_mole") { npc.queue { npc.moleCombat(this) } }
        onNpcCombat(LairBosses.KQ_FORM_1) { npc.queue { npc.kqCombat(this, form2 = false) } }
        onNpcCombat(LairBosses.KQ_FORM_2) { npc.queue { npc.kqCombat(this, form2 = true) } }
        onNpcCombat("npc.dagannoth_rex") { npc.queue { npc.rexCombat(this) } }
        onNpcCombat("npc.dagannoth_prime") { npc.queue { npc.primeCombat(this) } }
        onNpcCombat("npc.dagannoth_supreme") { npc.queue { npc.supremeCombat(this) } }
        onNpcCombat(LairBosses.SPINOLYP_KEY) { npc.queue { npc.spinolypCombat(this) } }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Players near this npc on its plane (PawnList only offers forEach). */
    private fun Npc.playersWithin(radius: Int, of: Tile = tile): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p ->
            if (p.tile.height == of.height && p.tile.isWithinRadius(of, radius) && !p.isDead() && !p.invisible) out += p
        }
        return out
    }

    private fun Npc.tickDelayTo(target: Pawn): Int =
        max(1, RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1)

    /** Projectile + hit that always lands (donor `ignoreDefence()`); overheads still apply. */
    private fun Npc.autoProjectile(target: Pawn, combatClass: CombatClass, maxHit: Int, gfx: Int, impact: Int = -1): Int {
        val style = if (combatClass == CombatClass.MAGIC) CombatStyle.MAGIC else CombatStyle.RANGED
        prepareAttack(combatClass, style, AttackStyle.ACCURATE)
        world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 20))
        val delay = tickDelayTo(target)
        dealHit(target = target, maxHit = maxHit, landHit = true, delay = delay)
        if (impact > 0) target.graphic(impact, 100, delay * 30)
        return delay
    }

    // ───────────────────────────── King Black Dragon ─────────────────────────────

    private suspend fun Npc.kbdCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1) && world.chance(1, 4)) {
                    animate(80)
                    bossMelee(target, maxHit = 25, style = CombatStyle.SLASH)
                } else if (world.chance(1, 2)) {
                    breath(target, gfx = 393, floor = 0)
                } else {
                    when (world.random(2)) {
                        0 -> {
                            breath(target, gfx = 396, floor = 10)
                            if (world.chance(1, 3)) target.freeze(cycles = 5) {
                                (target as? Player)?.message("<col=ff0000>The dragon's icy breath freezes you in place!</col>")
                            }
                        }
                        1 -> {
                            breath(target, gfx = 395, floor = 12)
                            if (world.chance(1, 3) && target is Player) {
                                SHOCK_STATS.forEach { target.getSkills().alterCurrentLevel(it, -2) }
                                target.message("<col=ff0000>The dragon's shocking breath drains your stats!</col>")
                            }
                        }
                        else -> {
                            breath(target, gfx = 394, floor = 10)
                            if (world.chance(1, 3)) Poison.poison(target, 8)
                        }
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

    /** Kronos `fire()`: anim 81, dragonfire 65 mitigated by shields/potion/prayer with a floor. */
    private fun Npc.breath(target: Pawn, gfx: Int, floor: Int) {
        animate(81)
        prepareAttack(CombatClass.MAGIC, CombatStyle.MAGIC, AttackStyle.ACCURATE)
        world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 51, angle = 15, steepness = 250))
        dealHit(target = target, formula = DragonfireFormula(maxHit = 65, minHit = floor), delay = tickDelayTo(target))
    }

    // ───────────────────────────── Giant Mole ─────────────────────────────

    private suspend fun Npc.moleCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (attr[MOLE_BURROWING] == true) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(3312)
                bossMelee(target, maxHit = 21, style = CombatStyle.CRUSH)
                postAttackLogic(target)
                // Donor: below half health, 1-in-4 per exchange to burrow away.
                if (getCurrentHp() < getMaxHp() / 2 && world.chance(1, 4)) {
                    burrow(target)
                    break
                }
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.burrow(target: Pawn) {
        val mole = this
        val spawn = attr[LairBossesPlugin.SPAWN_TILE] ?: tile
        val (dx, dz) = LairBosses.MOLE_BURROW_POINTS[world.random(LairBosses.MOLE_BURROW_POINTS.size - 1)]
        val dest = world.snapToWalkable(Tile(spawn.x + dx, spawn.z + dz, spawn.height), maxRadius = 6)
        attr[MOLE_BURROWING] = true
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        removeCombatTarget()
        (target as? Player)?.message("The mole burrows away underground!")
        world.queue {
            mole.animate(3314)
            wait(3)
            if (mole.isDead() || mole.index < 0) return@queue
            mole.moveTo(dest)
            mole.animate(3315)
            wait(2)
            mole.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            mole.attr[MOLE_BURROWING] = false
        }
    }

    // ───────────────────────────── Kalphite Queen ─────────────────────────────

    private suspend fun Npc.kqCombat(task: QueueTask, form2: Boolean) {
        val meleeAnim = if (form2) 1178 else 6241
        val magicAnim = if (form2) 6234 else 6240
        val rangedAnim = if (form2) 6235 else 6240
        val magicSelfGfx = if (form2) 279 else 278
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if ((attr[Combat.DAMAGE_TAKE_MULTIPLIER] ?: 1.0) == 0.0) { // mid-transform lock
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (moveToAttackRange(task, target, distance = 10, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1) && world.chance(1, 3)) {
                    animate(meleeAnim)
                    bossMelee(target, maxHit = 31, style = CombatStyle.STAB)
                } else if (world.chance(1, 3)) {
                    kqMagic(target, magicAnim, magicSelfGfx)
                } else {
                    animate(rangedAnim)
                    autoProjectile(target, CombatClass.RANGED, maxHit = 31, gfx = 289)
                    playersWithin(10).filter { it !== target }.forEach { p ->
                        autoProjectile(p, CombatClass.RANGED, maxHit = 31, gfx = 289)
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

    /** Donor `magicAttack()`: bolt to the target, then chain up to four hops between players ≤2 tiles apart. */
    private fun Npc.kqMagic(target: Pawn, anim: Int, selfGfx: Int) {
        animate(anim)
        graphic(selfGfx)
        val boss = this
        world.queue {
            var dest: Pawn = target
            var bounces = 0
            val hit = HashSet<Pawn>()
            while (bounces <= 4) {
                if (boss.isDead() || boss.index < 0) return@queue
                boss.autoProjectile(dest, CombatClass.MAGIC, maxHit = 31, gfx = 280, impact = 281)
                hit += dest
                wait(2)
                val last = dest
                val next = boss.playersWithin(2, of = last.tile).filter { it !in hit }
                if (next.isEmpty()) return@queue
                dest = next[world.random(next.size - 1)]
                bounces++
            }
        }
    }

    // ───────────────────────────── Dagannoth Kings ─────────────────────────────

    private suspend fun Npc.rexCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(2851)
                bossMelee(target, maxHit = 26, style = CombatStyle.CRUSH)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.primeCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 12, projectile = true) && isAttackDelayReady()) {
                animate(2854)
                bossProjectile(target, CombatClass.MAGIC, maxHit = 50, gfx = 162)
                target.graphic(477, 100, tickDelayTo(target) * 30)
                // Splash: everyone standing next to the victim eats the same bolt.
                playersWithin(1, of = target.tile).filter { it !== target }.forEach { p ->
                    bossProjectile(p, CombatClass.MAGIC, maxHit = 50, gfx = 162)
                    p.graphic(477, 100, tickDelayTo(p) * 30)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.supremeCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 12, projectile = true) && isAttackDelayReady()) {
                animate(2855)
                bossProjectile(target, CombatClass.RANGED, maxHit = 30, gfx = 475)
                playersWithin(6).filter { it !== target }.forEach { p ->
                    bossProjectile(p, CombatClass.RANGED, maxHit = 30, gfx = 475)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.spinolypCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (!tile.isWithinRadius(target.tile, 10)) break // stationary: out of reach → drop it
            if (isAttackDelayReady()) {
                animate(2868)
                if (world.chance(6, 10)) {
                    val landed = bossProjectile(target, CombatClass.RANGED, maxHit = 10, gfx = 476)
                    if (landed && world.chance(1, 3)) Poison.poison(target, 6)
                } else {
                    val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 10, gfx = 124)
                    if (landed && target is Player && target.getSkills().getCurrentLevel(Skills.PRAYER) > 0) {
                        target.getSkills().alterCurrentLevel(Skills.PRAYER, -1)
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

    companion object {
        val MOLE_BURROWING = AttributeKey<Boolean>()
        val SHOCK_STATS = intArrayOf(Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.RANGED, Skills.MAGIC)
    }
}
