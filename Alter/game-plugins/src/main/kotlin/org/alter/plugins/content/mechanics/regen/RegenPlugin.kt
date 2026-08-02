package org.alter.plugins.content.mechanics.regen

import org.alter.api.Skills
import org.alter.api.ext.pawn
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers

/**
 * Natural stat regeneration, OSRS rates:
 *  - Hitpoints: +1 every 100 ticks (1 minute); Rapid Heal halves the period.
 *  - Drained stats: +1 toward base every 100 ticks; Rapid Restore halves the period.
 *  - Boosted stats: -1 toward base every 100 ticks; Preserve stretches it to 150.
 * Prayer never regenerates. NPCs do not passively regenerate.
 */
class RegenPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            player.timers[HP_REGEN_TIMER] = hpRegenPeriod(player)
            player.timers[STAT_RESTORE_TIMER] = statRestorePeriod(player)
            player.timers[BOOST_DECAY_TIMER] = boostDecayPeriod(player)
        }

        // The timers must ALWAYS re-arm — only the effect is gated on being alive. A timer
        // that fired mid-death-sequence previously vanished for the rest of the session
        // (no HP regen / stat restore / boost decay until relog).
        onTimer(HP_REGEN_TIMER) {
            val p = pawn
            if (p is Player) {
                if (!p.isDead()) {
                    // capped increment: heals only while below base, never lowers an overheal
                    p.getSkills().incrementCurrentLevel(Skills.HITPOINTS, 1, capped = true)
                }
                p.timers[HP_REGEN_TIMER] = hpRegenPeriod(p)
            }
        }

        onTimer(STAT_RESTORE_TIMER) {
            val p = pawn
            if (p is Player) {
                if (!p.isDead()) {
                    val skills = p.getSkills()
                    for (skill in 0 until skills.maxSkills) {
                        if (skill == Skills.HITPOINTS || skill == Skills.PRAYER) {
                            continue
                        }
                        if (skills.getCurrentLevel(skill) < skills.getBaseLevel(skill)) {
                            skills.incrementCurrentLevel(skill, 1, capped = true)
                        }
                    }
                }
                p.timers[STAT_RESTORE_TIMER] = statRestorePeriod(p)
            }
        }

        onTimer(BOOST_DECAY_TIMER) {
            val p = pawn
            if (p is Player) {
                if (!p.isDead()) {
                    val skills = p.getSkills()
                    for (skill in 0 until skills.maxSkills) {
                        if (skill == Skills.PRAYER) {
                            continue
                        }
                        val current = skills.getCurrentLevel(skill)
                        if (current > skills.getBaseLevel(skill)) {
                            skills.setCurrentLevel(skill, current - 1)
                        }
                    }
                }
                p.timers[BOOST_DECAY_TIMER] = boostDecayPeriod(p)
            }
        }
    }

    private fun hpRegenPeriod(p: Player): Int =
        if (Prayers.isActive(p, Prayer.RAPID_HEAL)) REGEN_PERIOD / 2 else REGEN_PERIOD

    private fun statRestorePeriod(p: Player): Int =
        if (Prayers.isActive(p, Prayer.RAPID_RESTORE)) REGEN_PERIOD / 2 else REGEN_PERIOD

    private fun boostDecayPeriod(p: Player): Int =
        if (Prayers.isActive(p, Prayer.PRESERVE)) PRESERVE_DECAY_PERIOD else REGEN_PERIOD

    companion object {
        val HP_REGEN_TIMER = TimerKey()
        val STAT_RESTORE_TIMER = TimerKey()
        val BOOST_DECAY_TIMER = TimerKey()

        const val REGEN_PERIOD = 100 // 1 minute
        const val PRESERVE_DECAY_PERIOD = 150 // boosts last 50% longer under Preserve
    }
}
