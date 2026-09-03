package org.alter.game.model.entity

import gg.rsmod.util.toStringHelper
import net.rsprot.protocol.api.login.GameLoginResponseHandler
import net.rsprot.protocol.game.outgoing.map.RebuildLogin
import net.rsprot.protocol.game.outgoing.map.RebuildNormal
import net.rsprot.protocol.loginprot.incoming.util.LoginBlock
import net.rsprot.protocol.message.OutgoingGameMessage
import org.alter.game.model.EntityType
import org.alter.game.model.World
import org.alter.game.saving.PlayerSaving

/**
 * A [Player] that is controlled by a human. A [Client] is responsible for
 * handling any network related job.
 *
 * Anything other than network logic should be added to [Player] instead.
 *
 * @param world
 * The [World] that this client is registered to.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class Client(world: World) : Player(world) {
    /**
     * The username that was used to register the [Player]. This username should
     * never be changed through the player's end.
     */
    lateinit var loginUsername: String

    /**
     * The encrypted password.
     */
    lateinit var passwordHash: String

    /** True once [passwordHash] has been assigned (the lateinit backing field is set). */
    fun hasPasswordHash(): Boolean = ::passwordHash.isInitialized

    /**
     * The client's UUID.
     */
    lateinit var uuid: String

    /**
     * The address this session logged in from (dotted quad / IPv6 text), captured from the login
     * channel; null if it could not be read. Session-only, never persisted. Households, CGNAT and
     * a reverse proxy all put many real players behind ONE address, so this is only ever used to
     * DENY a payout (PK Blood Money / Elo between two accounts on the same address) — never to
     * block a login or a fight.
     */
    var remoteIp: String? = null

    /**
     * The xteas for the current log-in session.
     */
    lateinit var currentXteaKeys: IntArray

    /**
     * Is the applet focused on the player's computer?
     */
    var appletFocused = true

    /**
     * The applet's current width.
     */
    var clientWidth = 765

    /**
     * The applet's current height.
     */
    var clientHeight = 503

    /**
     * The pitch of the camera in the client's game UI.
     */
    var cameraPitch = 0

    /**
     * The yaw of the camera in the client's game UI.
     */
    var cameraYaw = 0

    /**
     * Whether this client's incoming packets ([org.alter.game.message.Message]s)
     * are echoed to their chatbox as developer diagnostics (e.g. "Continue dialog…",
     * "Click map…").
     *
     * This follows the same debug gate as the other chatbox diagnostics
     * ([showChatboxDebug]): OFF for everyone by default so ordinary players only see
     * real chat, and turned back on per-session by a dev via the `::debug` command or
     * server-wide via the `debug-packets` dev property.
     */
    val logPackets: Boolean
        get() = showChatboxDebug(world.devContext.debugPackets)

    override val entityType: EntityType = EntityType.CLIENT

    override fun handleLogout() {
        super.handleLogout()
        PlayerSaving.savePlayer(this)
    }

    override fun handleMessages() {
        session?.processIncomingPackets(this)
    }

    /**
     * A client can only be written to once [session] has been attached (which happens just
     * before login). Every write below funnels through `session?.queue`, so a write made before
     * that point is discarded without a trace — and callers that clear a dirty flag on write
     * (the varp/skill flush in [Player.cycle]) would never retry it.
     */
    override val canReceiveMessages: Boolean get() = session != null

    private var rebuildNormalMessageWritten = false
    private val pendingMessages = mutableListOf<OutgoingGameMessage>()

    private fun onRebuildNormalMessageWritten() {
        pendingMessages.forEach { message ->
            session?.queue(message)
        }
        pendingMessages.clear()
    }

    override fun write(vararg messages: OutgoingGameMessage) {
        messages.forEach { message ->
            if (!rebuildNormalMessageWritten && (message is RebuildNormal || message is RebuildLogin)) {
                session?.queue(message)
                rebuildNormalMessageWritten = true
                onRebuildNormalMessageWritten()
            } else if (rebuildNormalMessageWritten) {
                session?.queue(message)
            } else {
                pendingMessages.add(message)
            }
        }
    }

    override fun channelFlush() {
        session?.flush()
    }

    override fun channelClose() {
        world.network.playerInfoProtocol.dealloc(info = playerInfo)
    }

    override fun toString(): String =
        toStringHelper()
            .add("login_username", loginUsername)
            .add("username", username)
            .toString()

    companion object {
        /**
         * Constructs a [Client] based on the [LoginRequest].
         */
        fun fromRequest(
            world: World,
            request: GameLoginResponseHandler<Client>,
            block: LoginBlock<*>,
        ): Client {
            val client = Client(world)
            client.clientWidth = block.width
            client.clientHeight = block.height
            // Normalize the login key so it matches website-created accounts exactly
            // (lowercase, spaces -> underscores). Display name stays as-typed.
            client.loginUsername = normalizeLogin(block.username)
            client.username = block.username
            client.uuid = block.uuid.toString()
            client.currentXteaKeys = block.seed
            client.remoteIp = runCatching {
                (request.ctx.channel().remoteAddress() as? java.net.InetSocketAddress)?.address?.hostAddress
            }.getOrNull()
            return client
        }

        /**
         * Canonical account key shared with the website (`normalizeLogin` in the web
         * app): lowercased, with spaces folded to underscores. Keeps one credential
         * usable on both the site and in-game regardless of how it was typed.
         */
        fun normalizeLogin(name: String): String =
            name.trim().lowercase().replace(' ', '_')
    }
}
