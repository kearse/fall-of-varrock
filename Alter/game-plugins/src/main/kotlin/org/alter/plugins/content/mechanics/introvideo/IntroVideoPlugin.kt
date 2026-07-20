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
 * On a brand-new account's first login, sends the magic BROADCAST line the custom
 * client's lofintro plugin listens for; the client then plays the intro video
 * full-screen and unskippable. Existing accounts never see it (they don't carry
 * NEW_ACCOUNT_ATTR, so they are never marked pending).
 *
 * Why a persistent "pending" flag instead of firing straight off NEW_ACCOUNT_ATTR:
 * that attribute is session-only and is true on the SINGLE login where the account
 * is created, so the old code got exactly one shot at delivery and committed
 * INTRO_SEEN_ATTR to disk before the client had confirmed anything. If that one
 * login didn't land the video — the fresh client wasn't listening yet when the
 * broadcast arrived, or the player dropped during the short wait — the account was
 * branded "seen" forever and never got another chance ("the intro was skipped").
 *
 * Now a brand-new account is marked INTRO_PENDING_ATTR (persistent). The trigger is
 * (re)sent on EVERY login while pending-and-not-seen, and INTRO_SEEN_ATTR is only
 * set once the broadcast has actually gone out to an online client. So a login that
 * ends before delivery simply leaves the account still owed the intro, and the next
 * login delivers it. Pre-existing accounts are never marked pending, so they never
 * retroactively see it.
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
        /** Terminal flag: the intro has been delivered; never send it again. */
        val INTRO_SEEN_ATTR = AttributeKey<Boolean>(persistenceKey = "intro_video_seen")

        /**
         * "This account is owed the intro." Persisted, and set only for brand-new
         * accounts, so the trigger survives a first login that fails to deliver and
         * retries on subsequent logins — without ever reaching pre-intro accounts.
         */
        val INTRO_PENDING_ATTR = AttributeKey<Boolean>(persistenceKey = "intro_video_pending")

        /** Must match LofIntroPlugin.TRIGGER_PREFIX + "play" in the client fork. */
        const val TRIGGER_MESSAGE = "FOV_INTRO:play"

        /**
         * Cycles to wait before firing the trigger, to clear the login handshake — a
         * chat line fired inside onLogin is sent before the fresh client is listening.
         * Kept generous so the intro reliably lands on a brand-new account's very first
         * login, when the client is still finishing its initial scene load.
         */
        private const val TRIGGER_DELAY = 5
    }

    init {
        onLogin {
            // Brand-new account: record that it is owed the intro. Persistent, so if this
            // first login never manages to deliver (client not listening yet, early drop),
            // the flag outlives the session and a later login still delivers it — instead
            // of burning the single NEW_ACCOUNT_ATTR chance.
            if (player.attr[NEW_ACCOUNT_ATTR] == true && player.attr[INTRO_SEEN_ATTR] != true) {
                player.attr[INTRO_PENDING_ATTR] = true
            }

            // Owed the intro and not yet seen: (re)send the client trigger. Do it on a
            // WORLD queue — a player queue would be terminated by the next plugin that
            // queues on this player (the recruit sergeant dialogue does, on this exact
            // login). Only mark it seen once the broadcast has actually gone out to an
            // online client, so a login that ends before delivery stays pending and
            // retries next time.
            if (player.attr[INTRO_PENDING_ATTR] == true && player.attr[INTRO_SEEN_ATTR] != true) {
                val p = player
                world.queue {
                    wait(TRIGGER_DELAY)
                    if (p.isOnline) {
                        p.message(TRIGGER_MESSAGE, ChatMessageType.BROADCAST)
                        p.attr[INTRO_SEEN_ATTR] = true
                        p.attr.remove(INTRO_PENDING_ATTR)
                    }
                }
            }
        }

        onCommand("introtest", Privilege.ADMIN_POWER, description = "Replay the first-login intro video") {
            player.message(TRIGGER_MESSAGE, ChatMessageType.BROADCAST)
        }
    }
}
