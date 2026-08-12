package org.alter.plugins.content.interfaces.gameframe.chat

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*

/**
 * @author CloudS3c
 */
class ChatBoxFilterPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {

        val PARENT_CHAT_BOX_INTERFACE = 162
        val GAME_BUTTON_COMPONENT = 7
        val PUBLIC_BUTTON_COMPONENT = 11
        val PRIVATE_BUTTON_COMPONENT = 15
        val CHANNEL_BUTTON_COMPONENT = 19
        val CLAN_BUTTON_COMPONENT = 23
        val TRADE_BUTTON_COMPONENT = 27

        onLogin {
            player.setVarbit(Varbit.CHATBOX_UNLOCKED, 1)
        }

        onButton(PARENT_CHAT_BOX_INTERFACE, GAME_BUTTON_COMPONENT) {
            when (player.getInteractingOption()) {
                1 -> {
                    player.toggleVarbit(26)
                }
                2 -> {
                    player.queue { dialog(player, this) }
                }
            }
        }

        listOf(PRIVATE_BUTTON_COMPONENT, CHANNEL_BUTTON_COMPONENT, CLAN_BUTTON_COMPONENT).forEach {
            onButton(PARENT_CHAT_BOX_INTERFACE, it) {
                player.setVarbit(
                    when (it) {
                        PRIVATE_BUTTON_COMPONENT -> 13674
                        CHANNEL_BUTTON_COMPONENT -> 928
                        CLAN_BUTTON_COMPONENT -> 929
                        else -> {
                            return@onButton
                        }
                    },
                    when (player.getInteractingOption()) {
                        // Option : Varbit Value
                        4 -> 2
                        3 -> 1
                        2 -> 0
                        else -> {
                            println("[$PARENT_CHAT_BOX_INTERFACE : $it] ${player.getInteractingOption()} Interacting Option is unknown.")
                            return@onButton
                        }
                    },
                )
            }
        }
        listOf(PUBLIC_BUTTON_COMPONENT, TRADE_BUTTON_COMPONENT).forEach {
            onButton(PARENT_CHAT_BOX_INTERFACE, it) {
                // Handled @TODO add setting to log these buttons that are being handled but should not do anything.
            }
        }

        // The Report button (component 31) is handled by
        // org.alter.plugins.content.mechanics.reports.ReportPlugin — the old
        // handler here opened interface 553, a stale report-abuse id that
        // renders the MTA Pizazz panel in our cache.
    }

    private suspend fun dialog(player: Player, it: QueueTask) {
        when (
            it.options(
                player, "Filter them." /* Filter or unfilter.*/,
                "Do not filter them.",
                title = "Boss kill-counts are not blocked by the spam filter.",
            )
        ) {
            1 -> {
                it.messageBox(player, "Boss kill-count messages that you receive in future will not be blocked by the spam filter.")
            }
            2 -> {
                it.messageBox(player, "CBA For now... Later.")
            }
        }
    }
}