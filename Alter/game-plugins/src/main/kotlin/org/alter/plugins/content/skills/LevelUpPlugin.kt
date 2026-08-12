package org.alter.plugins.content.skills

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.discord.DiscordBridge
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.*
import org.alter.game.model.entity.Player
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
 * queue), so the player keeps chopping / fighting exactly as in real OSRS.
 *
 * The box stays open until the player either clicks "Click here to continue"
 * (delivered by ResumePauseButtonHandler, which dismisses chatbox dialogs no
 * queued task is waiting on) or takes their next action. Actions have no single
 * hook in the engine, so a 1-tick watch timer closes the box as soon as the
 * player moves tiles or gains any XP after the level-up — walking, the next bar
 * smelted, a combat hit, etc.
 */
class LevelUpPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    companion object {
        private val LEVEL_UP_BOX_WATCH = TimerKey()

        /** Baselines captured when the box opens; any change means "next action". */
        private val LEVEL_UP_BOX_TILE = AttributeKey<Tile>()
        private val LEVEL_UP_BOX_XP = AttributeKey<Double>()

        /** Chatbox interface ids used by the level-up box (233 = regular, 193 = hunter). */
        private const val LEVEL_UP_INTERFACE = 233
        private const val LEVEL_UP_HUNTER_INTERFACE = 193

        private fun clearWatch(player: Player) {
            player.attr.remove(LEVEL_UP_BOX_TILE)
            player.attr.remove(LEVEL_UP_BOX_XP)
        }
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
            // current action, then watch for the action that should dismiss it.
            // addXp applies the triggering XP before firing this hook, so the
            // baselines below only change on the player's NEXT move / XP drop.
            player.openLevelUpBox(skill, increment)
            player.attr[LEVEL_UP_BOX_TILE] = player.tile
            player.attr[LEVEL_UP_BOX_XP] = player.getSkills().calculateTotalXp
            player.timers[LEVEL_UP_BOX_WATCH] = 1

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

        onTimer(LEVEL_UP_BOX_WATCH) {
            val open = player.interfaces.isVisible(LEVEL_UP_INTERFACE) ||
                player.interfaces.isVisible(LEVEL_UP_HUNTER_INTERFACE)
            val baseTile = player.attr[LEVEL_UP_BOX_TILE]
            val baseXp = player.attr[LEVEL_UP_BOX_XP]
            when {
                // Already dismissed (continue click or another dialog replaced it).
                !open -> clearWatch(player)
                baseTile == null || baseXp == null ||
                    !player.tile.sameAs(baseTile) ||
                    player.getSkills().calculateTotalXp != baseXp -> {
                    if (player.interfaces.isVisible(LEVEL_UP_INTERFACE)) {
                        player.closeInterface(LEVEL_UP_INTERFACE)
                    }
                    if (player.interfaces.isVisible(LEVEL_UP_HUNTER_INTERFACE)) {
                        player.closeInterface(LEVEL_UP_HUNTER_INTERFACE)
                    }
                    clearWatch(player)
                }
                else -> player.timers[LEVEL_UP_BOX_WATCH] = 1
            }
        }
    }
}
