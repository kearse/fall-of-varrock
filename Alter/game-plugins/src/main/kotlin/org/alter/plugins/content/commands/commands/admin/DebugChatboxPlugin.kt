package org.alter.plugins.content.commands.commands.admin

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DEBUG_CHATBOX_ATTR
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * `::debug [on|off]` — toggles the developer **chatbox debug diagnostics** for the calling admin,
 * and only for them. While on, the player sees the "Unhandled item/button/object action",
 * "Undefined spell", examine-id suffixes and similar messages that help identify unwired
 * content; while off (the default for everyone) those messages are suppressed.
 *
 * The toggle is session-only ([DEBUG_CHATBOX_ATTR] has no persistence key), so it clears on logout
 * and can never leak to ordinary players. With no argument the flag flips; `on`/`off` set it
 * explicitly.
 */
class DebugChatboxPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("debug", Privilege.ADMIN_POWER, description = "Toggle chatbox debug messages for yourself") {
            val args = player.getCommandArgs()
            val enable = when (args.firstOrNull()?.lowercase()) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                else -> !(player.attr[DEBUG_CHATBOX_ATTR] ?: false)
            }
            player.attr[DEBUG_CHATBOX_ATTR] = enable
            player.message(
                "Chatbox debug messages: <col=${if (enable) "4f9b4f" else "801700"}>" +
                    "${if (enable) "ON" else "OFF"}</col>.",
            )
        }
    }
}
