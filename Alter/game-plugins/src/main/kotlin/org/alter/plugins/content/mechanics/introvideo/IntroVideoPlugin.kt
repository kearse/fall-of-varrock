package org.alter.plugins.content.mechanics.introvideo

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.NEW_ACCOUNT_ATTR
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * First-login intro video trigger.
 *
 * On a brand-new account's FIRST ever login (and never again), sends the magic
 * BROADCAST line the custom client's lofintro plugin listens for; the client then
 * plays the intro video full-screen and unskippable. Existing accounts never see
 * it (they don't carry NEW_ACCOUNT_ATTR), and INTRO_SEEN_ATTR persists as a
 * belt-and-braces guard should new-account detection ever fire twice.
 *
 * The video file itself lives at https://fallofvarrock.com/client/intro.mp4
 * (VPS: /opt/kol/client/intro.mp4) — swap that file to change the intro; neither
 * this server nor the client needs a rebuild. See docs/intro-video.md.
 *
 * Testing: ::introtest replays the trigger for yourself on demand.
 */
class IntroVideoPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    companion object {
        val INTRO_SEEN_ATTR = AttributeKey<Boolean>(persistenceKey = "intro_video_seen")

        /** Must match LofIntroPlugin.TRIGGER_PREFIX + "play" in the client fork. */
        const val TRIGGER_MESSAGE = "FOV_INTRO:play"
    }

    init {
        onLogin {
            if (player.attr[NEW_ACCOUNT_ATTR] == true && player.attr[INTRO_SEEN_ATTR] != true) {
                player.attr[INTRO_SEEN_ATTR] = true
                // Don't send during the login handshake — a chat line fired that early is
                // dropped before the client is listening (::introtest worked, first-login
                // didn't). A couple of ticks in, the client is fully in-game.
                val p = player
                p.queue {
                    wait(3)
                    p.message(TRIGGER_MESSAGE, ChatMessageType.BROADCAST)
                }
            }
        }

        onCommand("introtest", Privilege.ADMIN_POWER, description = "Replay the first-login intro video") {
            player.message(TRIGGER_MESSAGE, ChatMessageType.BROADCAST)
        }
    }
}
