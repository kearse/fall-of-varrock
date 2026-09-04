package org.alter.plugins.content.areas.lumbridge.objs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Home Rejuvenation pool** — a POH-style Pool of Rejuvenation on the opened Lumbridge castle
 * ground floor (home hangout space); [POOL_SW_TILES] takes more if wanted. "Drink" restores the
 * player completely: Hitpoints, Prayer, run energy, special attack energy, and cures
 * poison/venom — the Ferox/ornate-pool treatment, free at home.
 *
 * Placement: POH pools are 3x3, so each SW spawn corner in [POOL_SW_TILES] sits one tile SW of
 * its requested centre. The pool is centred on (3224,3224,0), the open floor toward the
 * courtyard's NE. (An earlier second pool centred on (3217,3217) was removed; the onboarding
 * stage tile is back on its original (3218,3218) spot now that nothing stands there.) If a pool
 * renders smaller than 3x3 in-game, nudge its SW tile back toward its centre — cosmetic TUNE,
 * same as every other home-hub placement.
 *
 * The cache can't be read at authoring time, so the click verb is bound defensively like
 * [org.alter.plugins.content.mechanics.prayer.PrayerAltarPlugin]'s altars: a missing verb logs
 * a warning instead of dropping the plugin.
 */
class RejuvenationPoolPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        onWorldInit {
            val pool = getRSCM(POOL)
            POOL_SW_TILES.forEach { tile ->
                world.spawn(DynamicObject(id = pool, type = OBJ_TYPE, rot = ROT, tile = tile))
            }
        }

        var bound = false
        VERBS.forEach { verb ->
            try {
                onObjOption(obj = POOL, option = verb) { rejuvenate(player) }
                bound = true
            } catch (e: Exception) { /* this pool variant lacks this verb in the cache */ }
        }
        if (!bound) {
            logger.warn { "rejuvenation pool: '$POOL' has none of $VERBS in the cache — the pool will be unclickable." }
        }
    }

    private fun rejuvenate(player: Player) {
        val skills = player.getSkills()

        player.heal(player.getMaxHp()) // capValue 0 -> up to base, never strips an overheal

        // Restore EVERY drained stat to its base level — spec-weapon drains, Sara brew's
        // attack/strength hit, skilling drains, smite'd prayer, all of it. Only lifts stats
        // that sit BELOW base: active boosts (potions, the HP overheal above) are kept, which
        // is the ornate-pool behaviour ("restores reduced stats") rather than a hard reset.
        for (skill in 0 until skills.maxSkills) {
            val base = skills.getBaseLevel(skill)
            if (skills.getCurrentLevel(skill) < base) {
                skills.setCurrentLevel(skill, base)
            }
        }

        player.runEnergy = MAX_RUN_ENERGY
        player.sendRunEnergy(player.runEnergy.toInt())

        AttackTab.setEnergy(player, 100)

        // Cure active poison/venom, but leave a running immunity (negative counter) untouched.
        if ((player.attr[POISON_TICKS_LEFT_ATTR] ?: 0) > 0) {
            player.attr[POISON_TICKS_LEFT_ATTR] = 0
            Poison.refreshHpOrb(player)
        }

        player.animate(DRINK_ANIM)
        player.message("You drink from the pool and feel completely rejuvenated.")
    }

    private companion object {
        const val POOL = "object.pool_of_rejuvenation" // 29239; fancy/ornate/frozen variants are one-word swaps
        val VERBS = listOf("Drink", "Use")

        const val OBJ_TYPE = 10 // standard scenery loc shape (same as the home bank booths)
        const val ROT = 0

        // SW corner of each 3x3 basin -> centred on the requested tiles.
        val POOL_SW_TILES = listOf(
            Tile(3223, 3223, 0), // centred on (3224,3224)
        )

        const val DRINK_ANIM = 827 // bend down and scoop
        const val MAX_RUN_ENERGY = 10000.0
    }
}
