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
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.createProjectile
import org.alter.plugins.content.combat.dealHit
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
    // Routed through dealHit so boss damage carries the FULL hit pipeline: protection
    // prayers evaluated at hit-application (mid-flight overhead switches work), damage-map
    // kill credit, Redemption/Vengeance/recoil, and post-damage retaliation. The return
    // value is now accuracy-only — a prayer-blocked hit still "lands" for effect-gating,
    // which matches OSRS (overheads block damage, not on-hit effects).
    val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
    dealHit(target = target, maxHit = maxHit, landHit = landed, delay = 0, respectsProtection = !ignoresPrayer)
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
    // Formula must match the style: MeleeCombatFormula THROWS on RANGED/MAGIC (only knows
    // stab/slash/crush), which used to kill the npc's combat queue on every unprotected hit.
    val formula = if (combatClass == CombatClass.MAGIC) MagicCombatFormula else RangedCombatFormula
    val landed = formula.getAccuracy(this, target) >= world.randomDouble()
    dealHit(target = target, maxHit = maxHit, landHit = landed, delay = delay, respectsProtection = !ignoresPrayer)
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
