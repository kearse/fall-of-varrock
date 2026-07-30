package org.alter.game.service.world

import io.github.oshai.kotlinlogging.KotlinLogging
import net.rsprot.protocol.loginprot.outgoing.LoginResponse
import org.alter.game.model.PlayerUID
import org.alter.game.model.World
import org.alter.game.model.entity.Client

/**
 * @author Tom <rspsmods@gmail.com>
 */
class SimpleWorldVerificationService : WorldVerificationService {
    override fun interceptLoginResult(
        world: World,
        uid: PlayerUID,
        displayName: String,
        loginName: String,
    ): LoginResponse? =
        when {
            world.rebootTimer != -1 && world.rebootTimer < World.REJECT_LOGIN_REBOOT_THRESHOLD -> LoginResponse.UpdateInProgress
            isNameHeldByLiveSession(world, displayName) -> LoginResponse.Duplicate
            world.players.count() >= world.players.capacity -> LoginResponse.IPLimit
            else -> null
        }

    /**
     * Whether [displayName] is taken by a player who is genuinely in the world.
     *
     * A [Client] with no [Client.session] is not: the session is attached in the same game-thread
     * job that registers the player, so the only way to observe one without a session is a login
     * that registered and then failed to finish (dead channel, or a throw during avatar allocation).
     * Such a leftover has no channel, no disconnection hook and no pending logout, so nothing in the
     * engine will ever remove it — it would hold the account's name until the next restart and
     * bounce every attempt with `Duplicate` ("already logged in"). It never reached
     * [org.alter.game.model.entity.Player.login], so no login hooks ran and no save was written
     * against it: dropping it here is a clean reclaim, and this login proceeds normally.
     *
     * Bots and companions also run without a session, but they are not [Client]s, so a name
     * collision with one still (correctly) reports as a duplicate.
     */
    private fun isNameHeldByLiveSession(
        world: World,
        displayName: String,
    ): Boolean {
        val existing = world.getPlayerForName(displayName) ?: return false
        if (existing is Client && existing.session == null) {
            logger.warn { "Reclaiming stale registration for '$displayName' (registered with no session)." }
            world.unregister(existing)
            return false
        }
        return true
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
