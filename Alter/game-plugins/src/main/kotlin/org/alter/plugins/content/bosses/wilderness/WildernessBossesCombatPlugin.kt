package org.alter.plugins.content.bosses.wilderness

import org.alter.api.EquipmentType
import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.collision.isClipped
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
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

/**
 * The Wilderness bosses' fights — the Kronos `activities/wilderness/bosses` scripts translated
 * onto the shared boss primitives. Ids, odds and damage ranges are the donor's.
 *
 *  - **Callisto**: adjacent 80% melee (max 60); otherwise 80% **shockwave** (magic, gfx 395,
 *    max 60, always lands) or a **roar** that throws you up to four tiles back (3 damage, stun,
 *    strips overheads). 1-in-12 self-heal 4-10 (gfx 157).
 *  - **Vet'ion**: adjacent 85% melee (max 45); 20% **earthquake** (unblockable 45 to everyone
 *    within 12, 10-tick cooldown); else three **lightning** tiles (gfx 280 → 281, 30 through
 *    prayer). Below half health he calls two **skeleton hellhounds** and is immune while any
 *    live. His first death makes him **reborn** at full health ("Now do it again!!") with
 *    greater hellhounds; the reborn form pays out.
 *  - **Venenatis**: within 2 tiles 1-in-3 melee (max 50) else **magic** (gfx 165, max 50) at
 *    every player within 8; 1-in-14 **web** (gfx 1254, 50 through prayer, stun); 1-in-8
 *    poison 8; every tick 1-in-30 **prayer drain** (10 + a fifth of your prayer).
 *  - **Scorpia**: melee (max 16), 1-in-6 poison 20. Below 100 hp she calls two **guardians**
 *    that follow and heal her 1-10 every 4 ticks (gfx 109) until killed; they die with her.
 *  - **Chaos Elemental**: 1-in-10 **disarm** (unequips a random worn item into a free
 *    inventory slot, gfx 551), 1-in-12 **teleport** (throws you 1-4 tiles, gfx 554), otherwise
 *    a tri-style bolt (gfx 557, max 28) — 60% magic / 20% ranged / 20% melee-style.
 *  - **Chaos Fanatic**: shouts every swing; 1-in-8 **green barrage** (three tiles, 30 through
 *    prayer), 1-in-10 disarm, else a magic bolt (gfx 554, max 31, impact 305). 2-tick attacks.
 *  - **Crazy Archaeologist**: 1-in-8 **"Rain of knowledge!"** (three books at your area, then
 *    two more from where you stood — 24 each through prayer), adjacent 1-in-2 melee (max 15),
 *    else ranged (gfx 1259, max 15). Shouts on every swing.
 */
class WildernessBossesCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat(WildernessBosses.CALLISTO_KEY) { npc.queue { npc.callistoCombat(this) } }
        onNpcCombat(WildernessBosses.VETION_KEY) { npc.queue { npc.vetionCombat(this, reborn = false) } }
        onNpcCombat(WildernessBosses.VETION_REBORN_KEY) { npc.queue { npc.vetionCombat(this, reborn = true) } }
        onNpcCombat(WildernessBosses.VENENATIS_KEY) { npc.queue { npc.venenatisCombat(this) } }
        onNpcCombat(WildernessBosses.SCORPIA_KEY) { npc.queue { npc.scorpiaCombat(this) } }
        onNpcCombat(WildernessBosses.CHAOS_ELEMENTAL_KEY) { npc.queue { npc.chaosElementalCombat(this) } }
        onNpcCombat(WildernessBosses.CHAOS_FANATIC_KEY) { npc.queue { npc.chaosFanaticCombat(this) } }
        onNpcCombat(WildernessBosses.CRAZY_ARCHAEOLOGIST_KEY) { npc.queue { npc.archaeologistCombat(this) } }
        // Hellhounds and guardians: hounds use the plain melee loop; guardians never attack.
        onNpcCombat(WildernessBosses.HELLHOUND_KEY, WildernessBosses.GREATER_HELLHOUND_KEY) { npc.queue { npc.houndCombat(this) } }
        onNpcCombat(WildernessBosses.GUARDIAN_KEY) { npc.removeCombatTarget() }
    }

    // ───────────────────────────── helpers ─────────────────────────────

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
    private fun Npc.autoProjectile(target: Pawn, combatClass: CombatClass, maxHit: Int, gfx: Int) {
        val style = if (combatClass == CombatClass.MAGIC) CombatStyle.MAGIC else CombatStyle.RANGED
        prepareAttack(combatClass, style, AttackStyle.ACCURATE)
        world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 20))
        dealHit(target = target, maxHit = maxHit, landHit = true, delay = tickDelayTo(target))
    }

    /**
     * The donor's ground barrage: the target's tile plus [extra] random tiles within [radius]
     * (never the boss's own footprint), a projectile to each, then after [delay] ticks an
     * impact graphic and an unblockable hit on every player standing exactly on one.
     */
    private fun Npc.groundBarrage(target: Pawn, projGfx: Int, impactGfx: Int, maxHit: Int, extra: Int = 2, radius: Int = 2, delay: Int = 3, firstImpactGfx: Int = impactGfx): List<Tile> {
        val anchor = target.tile
        val tiles = mutableListOf(Tile(anchor.x, anchor.z, anchor.height))
        val candidates = mutableListOf<Tile>()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (dx == 0 && dz == 0) continue
            val t = Tile(anchor.x + dx, anchor.z + dz, anchor.height)
            if (t.isWithinRadius(tile, getSize() - 1)) continue
            candidates += t
        }
        candidates.shuffle()
        tiles += candidates.take(extra)
        tiles.forEach { t -> world.spawn(createProjectile(t, gfx = projGfx, type = ProjectileType.MAGIC)) }
        val boss = this
        world.queue {
            wait(delay)
            tiles.forEachIndexed { i, t -> world.spawn(TileGraphic(t, if (i == 0) firstImpactGfx else impactGfx, 0)) }
            if (boss.isDead() || boss.index < 0) return@queue
            boss.playersWithin(radius + 2, of = anchor).forEach { p ->
                if (tiles.any { it.sameAs(p.tile) }) p.hit(damage = world.random(maxHit), delay = 0)
            }
        }
        return tiles
    }

    /** Walk [target] away from this npc up to [steps] tiles while the ground allows; null if it can't move at all. */
    private fun Npc.knockbackTile(target: Pawn, steps: Int): Tile? {
        val cx = tile.x + getSize() / 2
        val cz = tile.z + getSize() / 2
        var vx = target.tile.x - cx
        var vz = target.tile.z - cz
        if (vx != 0) vx /= abs(vx)
        if (vz != 0) vz /= abs(vz)
        if (vx == 0 && vz == 0) vz = -1
        var end = target.tile
        repeat(steps) {
            val next = Tile(end.x + vx, end.z + vz, end.height)
            if (world.collision.isClipped(next)) return@repeat
            end = next
        }
        return if (end.sameAs(target.tile)) null else end
    }

    private fun Npc.disarm(p: Player, gfx: Int, anim: Int, maxHitIfNothing: Int) {
        animate(anim)
        val worn = DISARM_SLOTS.filter { p.equipment[it.id] != null }
        val slot = if (worn.isEmpty()) null else worn[world.random(worn.size - 1)]
        if (slot != null) {
            EquipAction.unequip(p, slot.id)
            p.message("<col=ff0000>${getName()} unequips one of your items!</col>")
        }
        bossProjectile(p, CombatClass.MAGIC, maxHit = if (slot != null) 5 else maxHitIfNothing, gfx = gfx)
    }

    private fun Npc.getName(): String = runCatching { dev.openrune.cache.CacheManager.getNpc(id).name }.getOrDefault("The boss")

    // ───────────────────────────── Callisto ─────────────────────────────

    private suspend fun Npc.callistoCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1) && world.chance(4, 5)) {
                    animate(4925)
                    bossMelee(target, maxHit = 60, style = CombatStyle.CRUSH)
                } else if (world.chance(4, 5)) {
                    animate(4925)
                    autoProjectile(target, CombatClass.MAGIC, maxHit = 60, gfx = 395)
                    (target as? Player)?.message("Callisto's fury sends an almighty shockwave through you.")
                } else {
                    animate(4925)
                    roar(target)
                }
                if (world.chance(1, 12)) {
                    graphic(157)
                    setCurrentHp(minOf(getMaxHp(), getCurrentHp() + 4 + world.random(6)))
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.roar(target: Pawn) {
        val dest = knockbackTile(target, 4)
        target.animate(1157)
        target.graphic(245, 124, 5)
        target.hit(damage = 3, delay = 0)
        target.stun(2) {}
        if (target is Player) {
            Prayers.deactivateAll(target)
            target.message("<col=ff0000>Callisto's roar throws you backwards and shatters your prayers!</col>")
        }
        if (dest != null) target.moveTo(dest)
    }

    // ───────────────────────────── Vet'ion ─────────────────────────────

    private suspend fun Npc.vetionCombat(task: QueueTask, reborn: Boolean) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            callHounds(target, reborn)
            if (moveToAttackRange(task, target, distance = 10, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1) && world.chance(17, 20)) {
                    animate(5499)
                    bossMelee(target, maxHit = if (reborn) 46 else 45, style = if (reborn) CombatStyle.CRUSH else CombatStyle.STAB)
                } else if ((attr[VETION_QUAKE_READY] ?: 0) <= world.currentCycle && world.chance(1, 5)) {
                    attr[VETION_QUAKE_READY] = world.currentCycle + 10
                    animate(5507)
                    val centre = Tile(tile.x + 1, tile.z + 1, tile.height)
                    playersWithin(12, of = centre).forEach { p ->
                        p.hit(damage = world.random(45), delay = 0)
                        p.message("<col=ff0000>Vet'ion pummels the ground sending a shattering earthquake shockwave through you.</col>")
                    }
                } else {
                    animate(5499)
                    groundBarrage(target, projGfx = 280, impactGfx = 281, maxHit = 30)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Below half health, once per form: two hounds; Vet'ion is immune while any of them lives. */
    private fun Npc.callHounds(target: Pawn, reborn: Boolean) {
        if (attr[VETION_HOUNDS_CALLED] == true || getCurrentHp() >= getMaxHp() / 2) return
        attr[VETION_HOUNDS_CALLED] = true
        forceChat(if (reborn) "Bahh! Go, dogs!!" else "Kill, my pets!")
        val key = if (reborn) WildernessBosses.GREATER_HELLHOUND_KEY else WildernessBosses.HELLHOUND_KEY
        val hounds = mutableListOf<Npc>()
        repeat(2) {
            val t = world.snapToWalkable(Tile(tile.x + 1 + world.random(2) - 1, tile.z + 1 + world.random(2) - 1, tile.height), maxRadius = 3)
            val hound = Npc(getRSCM(key), t, world)
            hound.respawns = false
            world.spawn(hound)
            hound.setActive(true)
            hound.attack(target)
            hounds += hound
        }
        hounds.firstOrNull()?.forceChat("GRRRRRRRRRRRRRRRRRRR")
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        val boss = this
        world.queue {
            while (!boss.isDead() && boss.index >= 0) {
                if (hounds.none { !it.isDead() && it.index >= 0 }) {
                    boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
                    return@queue
                }
                wait(1)
            }
            hounds.forEach { if (!it.isDead() && it.index >= 0) world.remove(it) }
        }
    }

    private suspend fun Npc.houndCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(6559)
                bossMelee(target, maxHit = if (id == getRSCM(WildernessBosses.GREATER_HELLHOUND_KEY)) 32 else 26, style = CombatStyle.CRUSH)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Venenatis ─────────────────────────────

    private suspend fun Npc.venenatisCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            // Donor: an every-tick 1-in-30 prayer sap while she has a target.
            if (target is Player && world.chance(1, 30)) {
                val cur = target.getSkills().getCurrentLevel(Skills.PRAYER)
                target.graphic(172, 92)
                world.spawn(target.createProjectile(this, gfx = 171, type = ProjectileType.MAGIC))
                if (cur > 0) {
                    target.getSkills().alterCurrentLevel(Skills.PRAYER, -(10 + (cur + 1) / 5))
                    target.message("Your prayer was drained!")
                }
            }
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 2) && world.chance(1, 3)) {
                    animate(5319)
                    bossMelee(target, maxHit = 50, style = CombatStyle.STAB)
                } else {
                    graphic(164)
                    animate(5322)
                    val victims = playersWithin(8).let { if (target is Player && target !in it) it + target else it }
                    victims.forEach { p -> bossProjectile(p, CombatClass.MAGIC, maxHit = 50, gfx = 165) }
                    if (target !is Player) bossProjectile(target, CombatClass.MAGIC, maxHit = 50, gfx = 165)
                }
                if (world.chance(1, 14)) {
                    animate(5322)
                    world.spawn(createProjectile(target, gfx = 1254, startHeight = 45, endHeight = 0, delay = 75, angle = 10, steepness = 16))
                    target.hit(damage = world.random(50), delay = 2)
                    target.stun(2) {}
                    (target as? Player)?.message("<col=ff0000>Venenatis hurls her web at you, sticking you to the ground.</col>")
                }
                if (world.chance(1, 8)) Poison.poison(target, 8)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Scorpia ─────────────────────────────

    private suspend fun Npc.scorpiaCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            callGuardians()
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(6254)
                bossMelee(target, maxHit = 16, style = CombatStyle.STAB)
                if (world.chance(1, 6)) Poison.poison(target, 20)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Below 100 hp, once per life: two guardians that shadow her and heal 1-10 every 4 ticks. */
    private fun Npc.callGuardians() {
        if (attr[SCORPIA_GUARDIANS_CALLED] == true || getCurrentHp() >= 100) return
        attr[SCORPIA_GUARDIANS_CALLED] = true
        val boss = this
        val guardians = mutableListOf<Npc>()
        repeat(2) {
            val t = world.snapToWalkable(Tile(tile.x + world.random(getSize() - 1), tile.z + world.random(getSize() - 1), tile.height), maxRadius = 3)
            val g = Npc(getRSCM(WildernessBosses.GUARDIAN_KEY), t, world)
            g.respawns = false
            world.spawn(g)
            g.setActive(true)
            g.graphic(144, 20)
            guardians += g
            world.queue {
                var cooldown = 0
                while (!g.isDead() && g.index >= 0) {
                    if (boss.isDead() || boss.index < 0) { world.remove(g); return@queue }
                    if (!g.tile.isWithinRadius(boss.tile, 3)) {
                        g.moveTo(world.snapToWalkable(Tile(boss.tile.x + world.random(2) - 1, boss.tile.z - 1, boss.tile.height), maxRadius = 4))
                    } else if (cooldown <= 0) {
                        cooldown = 4
                        g.animate(6254)
                        world.spawn(g.createProjectile(boss, gfx = 109, startHeight = 43, endHeight = 31, delay = 51, angle = 10, steepness = 16))
                        boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + 1 + world.random(9)))
                    }
                    cooldown--
                    wait(1)
                }
            }
        }
        attr[SCORPIA_GUARDIANS] = guardians
    }

    // ───────────────────────────── Chaos Elemental ─────────────────────────────

    private suspend fun Npc.chaosElementalCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (target is Player && target.inventory.freeSlotCount > 0 && world.chance(1, 10)) {
                    disarm(target, gfx = 551, anim = 3146, maxHitIfNothing = 28)
                } else if (world.chance(1, 12)) {
                    animate(3146)
                    world.spawn(createProjectile(target, gfx = 554, startHeight = 80, endHeight = 32, delay = 40, angle = 6, steepness = 16))
                    val dest = knockbackTile(target, 1 + world.random(3))
                    val victim = target
                    world.queue {
                        wait(2)
                        if (dest != null && !victim.isDead()) {
                            victim.moveTo(dest)
                            (victim as? Player)?.message("The Chaos Elemental teleports you!")
                        }
                    }
                } else {
                    animate(3146)
                    val roll = world.random(9)
                    when {
                        roll < 6 -> bossProjectile(target, CombatClass.MAGIC, maxHit = 28, gfx = 557)
                        roll < 8 -> bossProjectile(target, CombatClass.RANGED, maxHit = 28, gfx = 557)
                        else -> {
                            // A melee-class bolt at range: Protect from Melee is the answer (donor CRUSH style).
                            prepareAttack(CombatClass.MELEE, CombatStyle.CRUSH, AttackStyle.AGGRESSIVE)
                            world.spawn(createProjectile(target, gfx = 557, startHeight = 80, endHeight = 32, delay = 40, angle = 6, steepness = 16))
                            val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
                            dealHit(target = target, maxHit = 28, landHit = landed, delay = tickDelayTo(target))
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

    // ───────────────────────────── Chaos Fanatic ─────────────────────────────

    private suspend fun Npc.chaosFanaticCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                forceChat(FANATIC_SHOUTS[world.random(FANATIC_SHOUTS.size - 1)])
                if (world.chance(1, 8)) {
                    animate(1979)
                    groundBarrage(target, projGfx = 551, impactGfx = 157, maxHit = 30, firstImpactGfx = 552)
                } else if (target is Player && target.inventory.freeSlotCount > 0 && world.chance(1, 10)) {
                    disarm(target, gfx = 554, anim = 1979, maxHitIfNothing = 31)
                } else {
                    animate(1979)
                    val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 31, gfx = 554)
                    if (landed) target.graphic(305, 0, tickDelayTo(target) * 30)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Crazy Archaeologist ─────────────────────────────

    private suspend fun Npc.archaeologistCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (world.chance(1, 8)) {
                    forceChat("Rain of knowledge!")
                    animate(3353)
                    val origin = Tile(target.tile.x, target.tile.z, target.tile.height)
                    groundBarrage(target, projGfx = 1260, impactGfx = 157, maxHit = 24)
                    // The second volley scatters from where you stood.
                    val boss = this
                    world.queue {
                        wait(3)
                        if (boss.isDead() || boss.index < 0) return@queue
                        val tiles = (1..2).map { Tile(origin.x + world.random(4) - 2, origin.z + world.random(4) - 2, origin.height) }
                        tiles.forEach { t -> world.spawn(boss.createProjectile(t, gfx = 1260, type = ProjectileType.MAGIC)) }
                        wait(3)
                        tiles.forEach { t -> world.spawn(TileGraphic(t, 157, 0)) }
                        if (boss.isDead() || boss.index < 0) return@queue
                        boss.playersWithin(4, of = origin).forEach { p ->
                            if (tiles.any { it.sameAs(p.tile) }) p.hit(damage = world.random(24), delay = 0)
                        }
                    }
                } else if (tile.isWithinRadius(target.tile, 1) && world.chance(1, 2)) {
                    animate(423)
                    bossMelee(target, maxHit = 15, style = CombatStyle.CRUSH)
                    forceChat(ARCHAEOLOGIST_SHOUTS[world.random(ARCHAEOLOGIST_SHOUTS.size - 1)])
                } else {
                    animate(3353)
                    bossProjectile(target, CombatClass.RANGED, maxHit = 15, gfx = 1259)
                    forceChat(ARCHAEOLOGIST_SHOUTS[world.random(ARCHAEOLOGIST_SHOUTS.size - 1)])
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
        val VETION_HOUNDS_CALLED = AttributeKey<Boolean>()
        val VETION_QUAKE_READY = AttributeKey<Int>()
        val SCORPIA_GUARDIANS_CALLED = AttributeKey<Boolean>()
        val SCORPIA_GUARDIANS = AttributeKey<MutableList<Npc>>()

        val DISARM_SLOTS = listOf(
            EquipmentType.WEAPON, EquipmentType.SHIELD, EquipmentType.CHEST, EquipmentType.HEAD,
            EquipmentType.LEGS, EquipmentType.GLOVES, EquipmentType.BOOTS,
        )

        val FANATIC_SHOUTS = listOf(
            "Burn!", "WEUGH!", "Develish Oxen Roll!", "All your wilderness are belong to them!",
            "AhehHeheuhHhahueHuUEehEahAH", "I shall call him squidgy and he shall be my squidgy!",
        )
        val ARCHAEOLOGIST_SHOUTS = listOf(
            "I'm Bellock - respect me!", "Get off my site!", "No-one messes with Bellock's dig!",
            "These ruins are mine!", "Taste my knowledge!", "You belong in a museum!",
        )
    }
}
