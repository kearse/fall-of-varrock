package org.alter.plugins.content.combat.specialattack.weapons.nightmarestaff

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.cfg.Animation
import org.alter.api.cfg.Graphic
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.specialattack.CombatContext
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.MagicCombatStrategy
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The nightmare staves' special attacks (OSRS Wiki) — both 55% energy, +50% accuracy, no runes,
 * scaling off the caster's Magic level with the gear magic-damage multiplier applied on top:
 *
 *  - **Volatile nightmare staff — Immolate**: one hit, base max `min(floor(58 × lvl / 99) + 1, 58)`.
 *  - **Eldritch nightmare staff — Invocate**: one hit, base max `min(floor(44 × lvl / 99) + 1, 44)`,
 *    and restores Prayer by half the damage dealt, up to 120 points.
 *
 * Neither existed before 2026-09-03 ("nightmare staff + staff with orb missing spec"). Two
 * engine points these lean on: `MagicCombatFormula.gearDamageMultiplier` (the max hit can't come
 * from `getMaxHit`, which derives from an armed spell — none is armed with a nightmare staff),
 * and `dealHit(combatClass = MAGIC)` so Protect from MAGIC, not Melee, judges the hit.
 */
class NightmareStaffPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        VOLATILE_KEYS.forEach { key ->
            register(key, cast = Graphic.VOLATILE_NIGHTMARE_STAFF_SPECIAL_CAST, hit = Graphic.VOLATILE_NIGHTMARE_STAFF_SPECIAL_HIT, baseCap = VOLATILE_BASE, prayer = false)
        }
        register(ELDRITCH_KEY, cast = Graphic.ELDRITCH_NIGHTMARE_STAFF_SPECIAL_CAST, hit = Graphic.ELDRITCH_NIGHTMARE_STAFF_SPECIAL_HIT, baseCap = ELDRITCH_BASE, prayer = true)
    }

    private fun register(key: String, cast: Int, hit: Int, baseCap: Int, prayer: Boolean) {
        val id = runCatching { getRSCM(key) }.getOrNull()
        if (id == null) {
            logger.info { "nightmare-staff: '$key' not in this cache; skipped." }
            return
        }
        SpecialAttacks.register(id, ENERGY) { spec(this, cast, hit, baseCap, prayer) }
    }

    private fun spec(ctx: CombatContext, castGfx: Int, hitGfx: Int, baseCap: Int, restoresPrayer: Boolean) {
        val player = ctx.player
        val target = ctx.target
        player.animate(Animation.NIGHTMARE_STAFF_SPECIAL)
        player.graphic(castGfx, 92)

        val magic = player.getSkills().getCurrentLevel(Skills.MAGIC)
        val base = minOf(baseCap, baseCap * magic / 99 + 1)
        val maxHit = Math.floor(base * MagicCombatFormula.gearDamageMultiplier(player)).toInt()
        val accuracy = MagicCombatFormula.getAccuracy(player, target, specialAttackMultiplier = ACCURACY_MULTIPLIER)
        val landed = accuracy >= ctx.world.randomDouble()
        val delay = MagicCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
        target.graphic(hitGfx, 124, delay * 30)

        val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landed, delay = delay, combatClass = CombatClass.MAGIC) { hit ->
            WeaponEffects.applyOnHit(player, target, hit, combatClass = CombatClass.MAGIC)
        }
        pawnHit.hit.addAction {
            val damage = hitmarks.sumOf { it.damage }
            if (damage > 0) {
                player.addXp(Skills.MAGIC, damage * 2.0 * Combat.COMBAT_XP_MULTIPLIER)
                player.addXp(Skills.HITPOINTS, damage * 1.33 * Combat.COMBAT_XP_MULTIPLIER)
                if (restoresPrayer) restorePrayer(player, damage / 2)
            }
        }
    }

    /** Invocate: +[points] Prayer, boosting past the base level up to [PRAYER_CAP]. */
    private fun restorePrayer(player: Player, points: Int) {
        if (points <= 0) return
        val skills = player.getSkills()
        val cur = skills.getCurrentLevel(Skills.PRAYER)
        val cap = maxOf(skills.getBaseLevel(Skills.PRAYER), PRAYER_CAP)
        if (cur >= cap) return
        skills.setCurrentLevel(Skills.PRAYER, minOf(cap, cur + points))
    }

    private companion object {
        const val ENERGY = 55
        const val ACCURACY_MULTIPLIER = 1.5
        const val VOLATILE_BASE = 58
        const val ELDRITCH_BASE = 44
        const val PRAYER_CAP = 120

        val VOLATILE_KEYS = listOf(
            "item.volatile_nightmare_staff",
            "item.volatile_nightmare_staff_25517",
            "item.corrupted_volatile_nightmare_staff",
            "item.volatile_nightmare_staff_deadman",
        )
        const val ELDRITCH_KEY = "item.eldritch_nightmare_staff"
    }
}
