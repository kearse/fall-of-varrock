package org.alter.plugins.content.discord

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.discord.DiscordBridge
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.PvpZones
import org.alter.rscm.RSCM.getRSCM
import java.io.File
import java.security.MessageDigest

/**
 * Bridges in-game events to the Discord bot via [DiscordBridge] (which writes to
 * the shared Mongo `discord_events` / `online` collections — see that class and
 * Alter/discord-bot/README.md).
 *
 * Wired here:
 *  - boot               → clears stale presence + announces data/update-notes.md
 *                         to the news channel IF its content changed since the
 *                         last announcement (so restarts/redeploys without new
 *                         notes stay silent instead of spamming "server online")
 *  - login / logout     → live presence for the bot's /online command
 *  - wilderness PK kills → #pk-highlights feed
 *  - boss kills          → #boss-and-war feed (for ids you list in [BOSSES])
 *
 * Level-99 announcements are emitted from [org.alter.plugins.content.skills.LevelUpPlugin]
 * instead, because the skill level-up hook allows only one handler.
 */
class DiscordIntegrationPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    companion object {
        /**
         * EDIT ME — bosses to announce. Map an RSCM npc name to the display name
         * shown in Discord. Example:
         *   "npc.king_black_dragon" to "King Black Dragon",
         *   "npc.kraken"            to "Kraken",
         */
        private val BOSSES: List<Pair<String, String>> = listOf()
    }

    init {
        // Resolve RSCM names to runtime npc ids once at load.
        val bossById: Map<Int, String> = BOSSES.associate { getRSCM(it.first) to it.second }

        onWorldInit {
            DiscordBridge.clearOnline()
            announceUpdateNotes()
        }

        onLogin {
            val c = player as? Client ?: return@onLogin
            DiscordBridge.setOnline(c.loginUsername, c.username)
        }

        onLogout {
            val c = player as? Client ?: return@onLogout
            DiscordBridge.setOffline(c.loginUsername)
        }

        onPlayerDeath {
            val killer = player.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerDeath
            if (killer == player) return@onPlayerDeath
            if (!PvpZones.isWilderness(player.tile)) return@onPlayerDeath
            DiscordBridge.event(
                kind = "pk",
                title = "${killer.username} defeated ${player.username} in the Wilderness!",
                player = killer.username,
            )
        }

        onAnyNpcDeath {
            val name = bossById[npc.id] ?: return@onAnyNpcDeath
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player
            DiscordBridge.event(
                kind = "boss",
                title = if (killer != null) "${killer.username} slew $name!" else "$name has been defeated!",
                player = killer?.username,
            )
        }
    }

    /**
     * Post `data/update-notes.md` to Discord as an update announcement, once per
     * distinct file content (dedupe lives in [DiscordBridge.shouldAnnounceUpdate]).
     * First line = title, rest = body; HTML comments are stripped. Missing or
     * empty file = no announcement.
     */
    private fun announceUpdateNotes() {
        val file = sequenceOf("../data/update-notes.md", "data/update-notes.md")
            .map(::File)
            .firstOrNull { it.isFile } ?: return
        val text = file.readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        if (text.isEmpty()) return

        val hash = MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (!DiscordBridge.shouldAnnounceUpdate(hash)) return

        val lines = text.lines()
        val title = lines.first().trimStart('#', ' ').trim()
        val body = lines.drop(1).joinToString("\n").trim()
        DiscordBridge.event(
            kind = "update",
            title = title,
            description = body.take(4000).ifEmpty { null },
        )
    }
}
