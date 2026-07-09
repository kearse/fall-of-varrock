package org.alter.plugins.content.skills

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.discord.DiscordBridge
import org.alter.game.model.World
import org.alter.game.model.attr.*
import org.alter.game.model.skill.SkillSet
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Handles the notification shown to a player when they advance a skill level.
 *
 * [org.alter.game.model.entity.Player.addXp] already detects the level-up and
 * fires [PluginRepository.executeSkillLevelUp], but until now nothing was bound
 * to that hook, so players received no feedback at all.
 *
 * IMPORTANT: the notification is intentionally **non-blocking**. We do NOT use
 * `player.queue { levelUpMessageBox(...) }`, because that enters the single-lane
 * queue and suspends until the player clicks "continue" — which would freeze an
 * in-progress action (e.g. woodcutting pauses mid-chop until you dismiss the box).
 * Instead we open the box directly with [openLevelUpBox] (which never touches the
 * queue) and auto-dismiss it with a short timer, so the player keeps chopping /
 * fighting exactly as in real OSRS.
 */
class LevelUpPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    companion object {
        /** Ticks the level-up box stays on screen before auto-closing (~5s). */
        private const val BOX_LIFETIME_TICKS = 8

        private val LEVEL_UP_BOX_CLOSE = TimerKey()

        /** Chatbox interface ids used by the level-up box (233 = regular, 193 = hunter). */
        private const val LEVEL_UP_INTERFACE = 233
        private const val LEVEL_UP_HUNTER_INTERFACE = 193
    }

    init {
        setLevelUpLogic {
            val skill = player.attr[LEVEL_UP_SKILL_ID] ?: return@setLevelUpLogic
            val increment = player.attr[LEVEL_UP_INCREMENT] ?: 1

            // A combat skill level-up may raise the player's combat level.
            if (Skills.isCombat(skill)) {
                player.calculateAndSetCombatLevel()
                player.sendCombatLevelText()
            }

            // Play the skill's level-up jingle, if one is defined.
            getSkillJingle(skill)?.let { player.playJingle(it.JingleID) }

            // Show the "Congratulations" box without interrupting the player's
            // current action, then schedule it to close itself.
            player.openLevelUpBox(skill, increment)
            player.timers[LEVEL_UP_BOX_CLOSE] = BOX_LIFETIME_TICKS

            // Announce a freshly-earned 99 to the Discord #achievements feed.
            // getBaseLevel is XP-derived, so it ignores temporary boosts.
            if (player.getSkills().getBaseLevel(skill) == 99) {
                DiscordBridge.event(
                    kind = "level99",
                    title = "${player.username} just reached level 99 ${SkillSet.getSkillName(skill)}!",
                    player = player.username,
                )
            }
        }

        onTimer(LEVEL_UP_BOX_CLOSE) {
            player.closeInterface(LEVEL_UP_INTERFACE)
            player.closeInterface(LEVEL_UP_HUNTER_INTERFACE)
        }
    }
}
