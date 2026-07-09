package org.alter.plugins.content.bosses

import dev.openrune.cache.CacheManager.getNpc
import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.model.*
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.plugins.content.combat.createProjectile
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.max

/**
 * Reusable **boss attack primitives** — the KBD/Barrows hit pattern factored out so every
 * boss combat plugin shares one implementation (protection-prayer handling via
 * [isProtectedFrom], the accuracy roll via [MeleeCombatFormula], projectile travel delay via
 * [RangedCombatStrategy]). Each returns whether the hit **landed** so the caller can gate
 * on-hit effects (freeze, venom, prayer-sap). Damage uses a fixed [maxHit] (OSRS-exact per
 * boss) rather than the npc's derived strength, so tuning a boss is one number.
 */

fun Npc.bossMelee(
    target: Pawn,
    maxHit: Int,
    style: CombatStyle = CombatStyle.CRUSH,
    ignoresPrayer: Boolean = false,
): Boolean {
    prepareAttack(CombatClass.MELEE, style, AttackStyle.AGGRESSIVE)
    if (!ignoresPrayer && target.isProtectedFrom(CombatClass.MELEE)) {
        target.hit(damage = 0, type = HitType.BLOCK, delay = 1)
        return false
    }
    val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
    target.hit(damage = if (landed) world.random(maxHit) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = 1)
    return landed
}

fun Npc.bossProjectile(
    target: Pawn,
    combatClass: CombatClass,
    maxHit: Int,
    gfx: Int,
    ignoresPrayer: Boolean = false,
): Boolean {
    val style = if (combatClass == CombatClass.MAGIC) CombatStyle.MAGIC else CombatStyle.RANGED
    prepareAttack(combatClass, style, AttackStyle.ACCURATE)
    world.spawn(createProjectile(target, gfx = gfx, startHeight = 43, endHeight = 31, delay = 41, angle = 15, steepness = 20))
    val delay = max(1, RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1)
    if (!ignoresPrayer && target.isProtectedFrom(combatClass)) {
        target.hit(damage = 0, type = HitType.BLOCK, delay = delay)
        return false
    }
    // Formula must match the style: MeleeCombatFormula THROWS on RANGED/MAGIC (only knows
    // stab/slash/crush), which used to kill the npc's combat queue on every unprotected hit.
    val formula = if (combatClass == CombatClass.MAGIC) MagicCombatFormula else RangedCombatFormula
    val landed = formula.getAccuracy(this, target) >= world.randomDouble()
    target.hit(damage = if (landed) world.random(maxHit) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = delay)
    return landed
}

/**
 * A safe **death animation** for a boss combat def. The `setCombatDef` DSL *requires* a death
 * anim, but OSRS npc defs don't store one (death is script-driven) — omitting it throws at
 * registration and silently drops the plugin. Fall back to the npc's own stand animation
 * (model-appropriate, guaranteed to exist), else a generic death anim.
 */
fun deathAnimFor(npcKey: String): Int {
    val id = runCatching { getRSCM(npcKey) }.getOrNull() ?: return 836
    val stand = runCatching { getNpc(id).standAnim }.getOrNull() ?: -1
    return if (stand > 0) stand else 836
}

/** Spawn [count] short-lived [key] minions clustered around this npc; returns them for tracking. */
fun Npc.bossSummon(key: String, count: Int, spread: Int = 2): MutableList<Npc> {
    val out = mutableListOf<Npc>()
    repeat(count) {
        val t = Tile(tile.x + world.random(spread) - spread / 2, tile.z + world.random(spread) - spread / 2, tile.height)
        val n = Npc(getRSCM(key), t, world)
        n.respawns = false
        world.spawn(n)
        n.setActive(true)
        out += n
    }
    return out
}
