package org.alter.plugins.content.commands.commands.all

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
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.game.saving.PlayerModeration

/**
 * @author Fritz <frikkipafi@gmail.com>
 */
class YellPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        private val YELL_COOLDOWN = TimerKey()

        /** ~9 seconds between yells (600ms ticks) for non-staff. */
        private const val COOLDOWN_TICKS = 15
    }

    init {
        onCommand("yell", description = "Yell to everyone") {
            val loginName = (player as? Client)?.loginUsername ?: player.username
            if (PlayerModeration.isMuted(loginName)) {
                player.message("You are muted and cannot yell.")
                return@onCommand
            }

            val isStaff = player.privilege.powers.contains(Privilege.MOD_POWER)
            if (!isStaff && player.timers.has(YELL_COOLDOWN)) {
                player.message("Please wait a few seconds between yells.")
                return@onCommand
            }

            val text = player.getCommandArgs().joinToString(" ").trim()
            if (text.isEmpty()) {
                player.message("Usage: ::yell <message>")
                return@onCommand
            }

            // Rank tag/colour follow the privilege set in game.yml rather than
            // hardcoded ids, so labels stay right if the ladder is reordered.
            val (rank, color) = when (player.privilege.name.lowercase()) {
                "moderator" -> "<img=0>Moderator" to "<shad=16711680>"
                "administrator", "admin" -> "<img=1>Admin" to "<shad=65280>"
                "developer" -> "<img=21>Developer" to "<shad=53247>"
                "owner" -> "<img=1>Owner" to "<shad=16777215>"
                "donator", "donor" -> "<img=8>Donator" to "<shad=16711680>"
                else -> "Player" to ""
            }

            if (!isStaff) {
                player.timers[YELL_COOLDOWN] = COOLDOWN_TICKS
            }

            val name = player.username
            player.world.players.forEach {
                it.message("$color[$rank]$name: $text", ChatMessageType.ENGINE)
            }
        }
    }
}
