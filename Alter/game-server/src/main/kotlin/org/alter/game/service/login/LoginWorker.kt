package org.alter.game.service.login

import io.github.oshai.kotlinlogging.KotlinLogging
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.loginprot.outgoing.LoginResponse
import net.rsprot.protocol.loginprot.outgoing.util.AuthenticatorResponse
import org.alter.game.message.DisconnectionHook
import org.alter.game.model.entity.Client
import org.alter.game.service.GameService
import org.alter.game.saving.PlayerLoadResult
import org.alter.game.saving.PlayerSaving
import org.alter.game.service.world.WorldVerificationService

/**
 * A worker for the [LoginService] that is responsible for handling the most
 * recent, non-handled [LoginServiceRequest] from its [boss].
 *
 * @author Tom <rspsmods@gmail.com>
 */
class LoginWorker(private val boss: LoginService, private val verificationService: WorldVerificationService) : Runnable {
    override fun run() {
        while (true) {
            val request = boss.requests.take()
            try {
                val world = request.world

                val client = Client.fromRequest(world, request.responseHandler, request.block)

                val loadResult: PlayerLoadResult = PlayerSaving.loadPlayer(client, request.block)

                if (loadResult == PlayerLoadResult.LOAD_ACCOUNT || loadResult == PlayerLoadResult.NEW_ACCOUNT) {
                    world.getService(GameService::class.java)?.submitGameThreadJob {
                        /*
                         * Permanent staff override: any login username listed under
                         * `owners` or `admins` in game.yml is forced to that rank on
                         * every login, regardless of what is stored in their save file.
                         * Owner wins when a name appears in both lists.
                         */
                        val context = world.gameContext
                        when {
                            context.isConfiguredOwner(client.loginUsername) ->
                                world.privileges.get("owner")?.let { client.privilege = it }

                            context.isConfiguredAdmin(client.loginUsername) ->
                                world.privileges.adminRank()?.let { client.privilege = it }
                        }
                        val interceptedLoginResult =
                            verificationService.interceptLoginResult(
                                world,
                                client.uid,
                                client.username,
                                client.loginUsername,
                            )

                        if (interceptedLoginResult != null) {
                            request.responseHandler.writeFailedResponse(interceptedLoginResult)
                            logger.info { "${"User '{}' login denied with code {}."} ${client.username} $interceptedLoginResult" }
                        } else if (client.register()) {
                            /*
                             * Everything below is the second half of login, and it all runs AFTER the
                             * player has been added to `world.players`. If any of it fails to complete
                             * — most commonly `writeSuccessfulResponse` returning null because the
                             * channel died between the login request and this game-thread job — the
                             * player is left registered with no session, no `DisconnectionHook` and no
                             * pending logout. Nothing can ever remove them again: the dead channel has
                             * no hook to fire, and the game loop only logs out players who asked to.
                             *
                             * That leftover holds the account's name in the world, so every later
                             * attempt is rejected as `Duplicate` ("already logged in") until the
                             * server is restarted. So: finish the sequence, or roll the registration
                             * back so the account stays loginable.
                             */
                            var initialised = false
                            try {
                                val session =
                                    request.responseHandler.writeSuccessfulResponse(
                                        LoginResponse.Ok(
                                            authenticatorResponse = AuthenticatorResponse.NoAuthenticator,
                                            staffModLevel = client.privilege.id,
                                            playerMod = true,
                                            index = client.index,
                                            member = true,
                                            accountHash = 0,
                                            userId = 0,
                                            userHash = 0,
                                        ),
                                        request.block,
                                    )
                                if (session == null) {
                                    logger.info { "User '${client.username}' lost its channel before the session was attached." }
                                } else {
                                    client.session = session
                                    client.playerInfo = client.world.network.playerInfoProtocol.alloc(client.index, OldSchoolClientType.DESKTOP)
                                    client.npcInfo = client.world.network.npcInfoProtocol.alloc(client.index, OldSchoolClientType.DESKTOP)
                                    client.worldEntityInfo =
                                        client.world.network.worldEntityInfoProtocol.alloc(
                                            client.index,
                                            OldSchoolClientType.DESKTOP,
                                        )
                                    session.setDisconnectionHook(DisconnectionHook(client))
                                    client.login()
                                    initialised = true
                                }
                            } catch (e: Exception) {
                                logger.error(e) { "Error finishing login for '${client.username}'." }
                            }

                            /*
                             * `Player.login` sets `initiated` before running the (individually
                             * isolated) login hooks, so a throw after that point still leaves a real,
                             * playable player with a live channel and a disconnection hook — keep
                             * them. Anything earlier never became a session at all: unregister it.
                             */
                            if (!initialised && !client.initiated) {
                                world.unregister(client)
                            }
                        } else {
                            request.responseHandler.writeFailedResponse(LoginResponse.InvalidSave)
                            logger.info { "${"User '{}' login denied with code {}."} ${client.username} ${LoginResponse.InvalidSave}" }
                        }
                    }
                } else {
                    val errorCode =
                        when (loadResult) {
                            PlayerLoadResult.INVALID_CREDENTIALS -> LoginResponse.InvalidUsernameOrPassword
                            PlayerLoadResult.INVALID_RECONNECTION -> LoginResponse.BadSessionId
                            PlayerLoadResult.MALFORMED -> LoginResponse.Locked
                            PlayerLoadResult.BANNED -> LoginResponse.Banned
                            PlayerLoadResult.NOT_WHITELISTED -> LoginResponse.ClosedBetaInvitedOnly
                            else -> LoginResponse.InvalidSave
                        }
                    request.responseHandler.writeFailedResponse(errorCode)
                    logger.info { "${"User '{}' login denied with code {}."} ${client.username} $loadResult" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error when handling request from ${request.block.username}." }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
