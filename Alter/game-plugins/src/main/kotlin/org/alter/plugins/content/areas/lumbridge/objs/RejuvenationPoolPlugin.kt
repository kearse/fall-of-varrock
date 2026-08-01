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
 * ground floor (home hangout space), centred on the requested tile (3217,3217,0). "Drink"
 * restores the player completely: Hitpoints, Prayer, run energy, special attack energy, and
 * cures poison/venom — the Ferox/ornate-pool treatment, free at home.
 *
 * Placement: POH pools are 3x3, so the SW spawn corner sits at (3216,3216) to centre the basin
 * on the requested tile. The footprint (3216..3218 square) covers the old (3218,3218) tile, so
 * the onboarding stage tile ([org.alter.plugins.content.mechanics.onboarding.FirstLoginFlow])
 * and General Zo's post ([org.alter.plugins.content.war.Sieges]) both stepped east out of the
 * water. If the pool renders smaller than 3x3 in-game, nudge [SW_X]/[SW_Z] back toward the
 * requested tile — cosmetic TUNE, same as every other home-hub placement.
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
            world.spawn(DynamicObject(id = getRSCM(POOL), type = OBJ_TYPE, rot = ROT, tile = Tile(SW_X, SW_Z, 0)))
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

        val basePrayer = skills.getBaseLevel(Skills.PRAYER)
        if (skills.getCurrentLevel(Skills.PRAYER) < basePrayer) {
            skills.setCurrentLevel(Skills.PRAYER, basePrayer)
        }

        player.runEnergy = MAX_RUN_ENERGY
        player.sendRunEnergy(player.runEnergy.toInt())

        AttackTab.setEnergy(player, 100)

        // Cure active poison/venom, but leave a running immunity (negative counter) untouched.
        if ((player.attr[POISON_TICKS_LEFT_ATTR] ?: 0) > 0) {
            player.attr[POISON_TICKS_LEFT_ATTR] = 0
            Poison.setHpOrb(player, Poison.OrbState.NONE)
        }

        player.animate(DRINK_ANIM)
        player.message("You drink from the pool and feel completely rejuvenated.")
    }

    private companion object {
        const val POOL = "object.pool_of_rejuvenation" // 29239; fancy/ornate/frozen variants are one-word swaps
        val VERBS = listOf("Drink", "Use")

        const val OBJ_TYPE = 10 // standard scenery loc shape (same as the home bank booths)
        const val ROT = 0
        const val SW_X = 3216   // SW corner of the 3x3 basin -> centred on the requested (3217,3217)
        const val SW_Z = 3216

        const val DRINK_ANIM = 827 // bend down and scoop
        const val MAX_RUN_ENERGY = 10000.0
    }
}
