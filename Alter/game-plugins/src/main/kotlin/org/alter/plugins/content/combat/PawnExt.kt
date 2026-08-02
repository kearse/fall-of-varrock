package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.HitType
import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.WeaponType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.hasWeaponType
import org.alter.api.ext.heal
import org.alter.api.ext.hit
import org.alter.game.model.Tile
import org.alter.game.model.attr.COMBAT_TARGET_FOCUS_ATTR
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.Projectile
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.ACTIVE_COMBAT_TIMER
import org.alter.plugins.content.combat.formula.CombatFormula
import org.alter.plugins.content.combat.strategy.ranged.RangedProjectile
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers
import java.lang.ref.WeakReference

/** OSRS redemption heal graphic. */
private const val REDEMPTION_GFX = 436

/**
 * Read a combat stat's current level from any pawn type (players via SkillSet,
 * NPCs via their NpcSkills stats).
 */
fun Pawn.currentCombatStat(
    playerSkill: Int,
    npcSkill: Int,
): Int =
    when (this) {
        is Player -> getSkills().getCurrentLevel(playerSkill)
        is Npc -> stats.getCurrentLevel(npcSkill)
        else -> 0
    }

/**
 * Drain a combat stat on any pawn type, floored at 0. Spec stat-drains (DWH, BGS,
 * Statius, anchor…) matter most against NPCs — bosses — so this must not be
 * player-only.
 */
fun Pawn.drainCombatStat(
    playerSkill: Int,
    npcSkill: Int,
    amount: Int,
) {
    if (amount <= 0) return
    when (this) {
        is Player -> getSkills().alterCurrentLevel(playerSkill, -amount)
        is Npc -> stats.setCurrentLevel(npcSkill, maxOf(0, stats.getCurrentLevel(npcSkill) - amount))
        else -> {}
    }
}

/**
 * @author Tom <rspsmods@gmail.com>
 */

fun Pawn.isAttacking(): Boolean = attr[COMBAT_TARGET_FOCUS_ATTR]?.get() != null

fun Pawn.isBeingAttacked(): Boolean = timers.has(ACTIVE_COMBAT_TIMER)

fun Pawn.getCombatTarget(): Pawn? = attr[COMBAT_TARGET_FOCUS_ATTR]?.get()

fun Pawn.setCombatTarget(target: Pawn) = target.also { attr[COMBAT_TARGET_FOCUS_ATTR] = it as WeakReference<Pawn> }

fun Pawn.removeCombatTarget() = attr.remove(COMBAT_TARGET_FOCUS_ATTR)

fun Pawn.canEngageCombat(target: Pawn): Boolean = Combat.canEngage(this, target)

fun Pawn.canAttack(
    target: Pawn,
    combatClass: CombatClass,
): Boolean = Combat.canAttack(this, target, combatClass)

fun Pawn.isAttackDelayReady(): Boolean = Combat.isAttackDelayReady(this)

fun Pawn.combatRaycast(
    target: Pawn,
    distance: Int,
    projectile: Boolean,
): Boolean = Combat.raycast(this, target, distance, projectile)

suspend fun Pawn.canAttackMelee(
    it: QueueTask,
    target: Pawn,
    moveIfNeeded: Boolean,
): Boolean =
    Combat.areBordering(tile.x, tile.z, getSize(), getSize(), target.tile.x, target.tile.z, target.getSize(), target.getSize()) ||
        moveIfNeeded && moveToAttackRange(it, target, distance = 0, projectile = false)

fun Pawn.dealHit(
    target: Pawn,
    formula: CombatFormula,
    delay: Int,
    onHit: (PawnHit) -> Unit = {},
): PawnHit {
    val accuracy = formula.getAccuracy(this, target)
    val maxHit = formula.getMaxHit(this, target)
    val landHit = accuracy >= world.randomDouble()
    return dealHit(target, maxHit, landHit, delay, onHit)
}

fun Pawn.dealHit(
    target: Pawn,
    maxHit: Int,
    landHit: Boolean,
    delay: Int,
    onHit: (PawnHit) -> Unit = {},
): PawnHit {
    val hit =
        if (landHit) {
            val hit = world.random(maxHit)
            if (hit == maxHit && this@dealHit is Player) {
                target.hit(damage = hit, type = HitType.HIT_MAX, delay = delay, attackersIndex = this.index) // maxhit type
            } else {
                target.hit(damage = hit, delay = delay, attackersIndex = this.index)
            }
        } else {
            target.hit(damage = 0, type = HitType.BLOCK, delay = delay, attackersIndex = this.index)
        }

    val pawnHit = PawnHit(hit, landHit)

    // Overhead protection is evaluated on the tick the hit LANDS, against the class of
    // this attack — switching a protect prayer while a projectile is mid-flight works,
    // exactly as in OSRS (100% block vs NPC attackers, 40% reduction vs players).
    val combatClass = CombatConfigs.getCombatClass(this)
    hit.addDamageTransform { damage ->
        Math.floor(damage * Combat.protectionDamageMultiplier(this, target, combatClass)).toInt()
    }

    // No attacker-death cancel: OSRS lands projectiles that were launched before the
    // attacker died (Combat.postDamage guards against retaliating at the corpse).
    hit.addAction { onHit(pawnHit) }
    hit.addAction {
        val pawn = this@dealHit
        Combat.postDamage(pawn, target)
    }
    if (landHit) {
        hit.addAction {
            val pawn = this@dealHit
            target.damageMap.add(pawn, hit.hitmarks.sumOf { it.damage })
        }
        hit.addAction {
            val pawn = this@dealHit
            val damage = hit.hitmarks.sumOf { it.damage }
            // Smite: the attacker's overhead drains floor(damage / 4) prayer points from
            // a player target.
            if (damage > 0 && pawn is Player && target is Player && Prayers.isActive(pawn, Prayer.SMITE)) {
                target.getSkills().decrementCurrentLevel(Skills.PRAYER, damage / 4, capped = false)
            }
            // Redemption: when a hit leaves the target alive below 10% HP, all their prayer
            // points are consumed to heal floor(25% of their Prayer level).
            if (target is Player && !target.isDead() && Prayers.isActive(target, Prayer.REDEMPTION) &&
                target.getCurrentHp() > 0 && target.getCurrentHp() * 10 < target.getMaxHp() &&
                target.getSkills().getCurrentLevel(Skills.PRAYER) > 0
            ) {
                target.graphic(REDEMPTION_GFX)
                target.heal(Math.floor(target.getSkills().getBaseLevel(Skills.PRAYER) * 0.25).toInt())
                target.getSkills().setCurrentLevel(Skills.PRAYER, 0)
            }
        }
        // NPC poison/venom chances from the combat def, rolled when a hit lands.
        if (this is Npc) {
            val def = combatDef
            if (def.venomChance > 0.0 || def.poisonChance > 0.0) {
                hit.addAction {
                    if (!target.isDead()) {
                        when {
                            def.venomChance > 0.0 && world.randomDouble() < def.venomChance ->
                                Poison.venom(target)
                            def.poisonChance > 0.0 && world.randomDouble() < def.poisonChance ->
                                Poison.poison(target, initialDamage = 6)
                        }
                    }
                }
            }
        }
    }
    return pawnHit
}

/**
 * Deals a guaranteed hit of an exact, pre-computed [damage] value (no accuracy
 * roll, no 0..maxHit randomisation). Useful for specials whose damage is
 * calculated by the caller, e.g. the Voidwaker (guaranteed hit, 50%-150% of
 * max). Replicates the same damage-map and post-damage bookkeeping as [dealHit]
 * so PvP kill credit and on-hit effects still apply.
 */
fun Pawn.dealExactHit(
    target: Pawn,
    damage: Int,
    delay: Int,
    onHit: (PawnHit) -> Unit = {},
): PawnHit {
    val hit = target.hit(damage = damage, delay = delay, attackersIndex = this.index)
    val pawnHit = PawnHit(hit, landed = true)

    val combatClass = CombatConfigs.getCombatClass(this)
    hit.addDamageTransform { dmg ->
        Math.floor(dmg * Combat.protectionDamageMultiplier(this, target, combatClass)).toInt()
    }

    // No attacker-death cancel — see dealHit.
    hit.addAction { onHit(pawnHit) }
    hit.addAction {
        val pawn = this@dealExactHit
        Combat.postDamage(pawn, target)
    }
    hit.addAction {
        val pawn = this@dealExactHit
        target.damageMap.add(pawn, hit.hitmarks.sumOf { it.damage })
    }
    return pawnHit
}

suspend fun Pawn.moveToAttackRange(
    it: QueueTask,
    target: Pawn,
    distance: Int,
    projectile: Boolean,
): Boolean = Combat.moveToAttackRange(it, this, target, distance, projectile)

fun Pawn.postAttackLogic(target: Pawn) = Combat.postAttack(this, target)

/**
 * Fires the projectile that matches the player's currently-equipped ammo (or the
 * thrown weapon itself) at [target], mirroring the normal ranged-attack visuals.
 * Used by ranged special attacks so the spec shows a flying projectile. No-ops if
 * no matching projectile is configured for the ammo.
 */
fun Player.fireAmmoProjectile(target: Pawn) {
    val ammoSlot =
        if (hasWeaponType(WeaponType.THROWN) || hasWeaponType(WeaponType.CHINCHOMPA)) {
            EquipmentType.WEAPON
        } else {
            EquipmentType.AMMO
        }
    val ammo = getEquipment(ammoSlot) ?: return
    val ammoProjectile = RangedProjectile.values.firstOrNull { ammo.id in it.items } ?: return
    val projectile = createProjectile(target, ammoProjectile.gfx, ammoProjectile.type)
    ammoProjectile.drawback?.let { drawback -> graphic(drawback) }
    ammoProjectile.impact?.let { impact -> target.graphic(impact.id, impact.height, projectile.lifespan) }
    world.spawn(projectile)
}

fun Pawn.createProjectile(
    target: Tile,
    gfx: Int,
    type: ProjectileType,
    endHeight: Int = -1,
): Projectile {
    val builder =
        Projectile.Builder()
            .setTiles(start = tile, target = target)
            .setGfx(gfx = gfx)
            .setHeights(startHeight = type.startHeight, endHeight = if (endHeight != -1) endHeight else type.endHeight)
            .setSlope(angle = type.angle, steepness = type.steepness)
            .setTimes(delay = type.delay, lifespan = type.delay + Combat.getProjectileLifespan(this, target, type))

    return builder.build()
}

fun Pawn.createProjectile(
    target: Pawn,
    gfx: Int,
    type: ProjectileType,
    endHeight: Int = -1,
): Projectile {
    val builder =
        Projectile.Builder()
            .setTiles(start = tile, target = target)
            .setGfx(gfx = gfx)
            .setHeights(startHeight = type.startHeight, endHeight = if (endHeight != -1) endHeight else type.endHeight)
            .setSlope(angle = type.angle, steepness = type.steepness)
            .setTimes(delay = type.delay, lifespan = type.delay + Combat.getProjectileLifespan(this, target.tile, type))

    return builder.build()
}
