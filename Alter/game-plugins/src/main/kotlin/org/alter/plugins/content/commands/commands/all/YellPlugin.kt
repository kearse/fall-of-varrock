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

            // Rank tag/colour follow the privilege set in game.yml rather than hardcoded ids, so
            // labels stay right if the ladder is reordered. Colours are `<col=hex>` — the old
            // `<shad=decimal>` tags were meant as RGB shadow colours but the tag takes hex, so
            // every yell rendered as plain text wearing a garbled/white shadow ("weird" yells).
            // Dark hues: the chatbox is opaque tan, where light colours vanish (see the ::warprep
            // hint fix, same day). Icon sits BEFORE the tag, like a mod crown in normal chat.
            val (rank, icon, color) = when (player.privilege.name.lowercase()) {
                "moderator" -> Triple("Moderator", "<img=0>", "1c5aa5")
                "administrator", "admin" -> Triple("Admin", "<img=1>", "b01c1c")
                "developer" -> Triple("Developer", "<img=21>", "0e6b7a")
                "owner" -> Triple("Owner", "<img=1>", "7d5a00")
                "donator", "donor" -> Triple("Donator", "<img=8>", "6a1b9a")
                else -> Triple("Player", "", null)
            }

            if (!isStaff) {
                player.timers[YELL_COOLDOWN] = COOLDOWN_TICKS
            }

            val name = player.username
            val header = if (color != null) "$icon<col=$color>[$rank] $name:</col>" else "[$rank] $name:"
            player.world.players.forEach {
                it.message("$header $text", ChatMessageType.ENGINE)
            }
        }
    }
}
