package org.alter.game.message

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Client

/**
 * Fired on the netty thread when a client's channel goes inactive (connection dropped, client killed,
 * network died) without the player ever asking to log out.
 *
 * It must NOT unregister the player itself. `World.unregister` only removes the entity: it skips
 * `Player.handleLogout`, and with it BOTH the character save (`Client.handleLogout` →
 * `PlayerSaving.savePlayer`) and every `onLogout` plugin hook — companions stored and despawned, duel
 * stakes returned, trades declined, minigame/raid sessions cleaned up. A dropped connection therefore
 * lost everything since the last ~60s autosave and left that state dangling.
 *
 * Instead it requests the logout and lets the game loop do the work on the game thread
 * (`Player.cycleBody` → `handleLogout`), which is what `pendingLogout` exists for — the engine's own
 * comment there describes exactly this case. That also means the normal rules apply: a player being
 * attacked by another player stays in the world until combat lapses or the ~2.5 min
 * `FORCE_DISCONNECTION_TIMER` runs out, so pulling the plug is no longer an instant escape. The cost
 * is that an immediate reconnect can be told the account is still logged in for that window.
 */
class DisconnectionHook(var client: Client) : Runnable {
    override fun run() {
        // Only ever act on THIS session's player. The hook can fire long after the account has logged
        // back in on a fresh channel, and a uid lookup would then hand us the LIVE session — logging
        // out the player who just reconnected.
        val player = client.world.getPlayerForUid(client.uid) ?: return
        if (player !== client) return
        if (client.getPendingLogout()) return
        logger.info { "Channel `${client.username}` disconnected " }
        // Volatile flags read by the game loop — the safe hand-off from this thread.
        client.requestLogout()
    }

    companion object {
        private val logger = KotlinLogging.logger {}

    }
}
