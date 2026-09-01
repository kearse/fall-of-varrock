package org.alter.plugins.content.mechanics.trading

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.trading.impl.TradeSession

/**
 * An attribute that represents a trade session between two players
 */
val TRADE_SESSION_ATTR = AttributeKey<TradeSession>(resetOnDeath = true)

/**
 * An attribute that represents if a player has accepted the trade
 */
val TRADE_ACCEPTED_ATTR = AttributeKey<Boolean>(resetOnDeath = true)

/**
 * The attribute holding the set of players who have recently requested a trade
 * with the player
 */
val TRADE_REQUESTS = AttributeKey<HashSet<Player>>()

/**
 * If the [Player] has a [TradeSession]
 */
fun Player.hasTradeSession() = this.attr.has(TRADE_SESSION_ATTR)

/**
 * Gets the [TradeSession] instance for a player
 */
fun Player.getTradeSession(): TradeSession? = this.attr[TRADE_SESSION_ATTR]

/**
 * If the [Player] has accepted a trade session
 */
fun Player.hasAcceptedTrade(): Boolean = this.attr[TRADE_ACCEPTED_ATTR] ?: false

/**
 * Removes the [TradeSession] instance from a [Player]
 */
fun Player.removeTradeSession() {
    this.attr.remove(TRADE_SESSION_ATTR)
    this.attr.remove(TRADE_ACCEPTED_ATTR)
}

/**
 * Gets the set of trade requests for a [Player], initialising it on first access. Must NOT use
 * `!!` — a clientless player (a [org.alter.plugins.content.bots.PkBot] / companion) never had the
 * attr seeded, and the old `!!` NPE'd the whole game cycle when someone walked into one to trade.
 */
fun Player.getTradeRequests(): HashSet<Player> =
    attr[TRADE_REQUESTS] ?: HashSet<Player>().also { attr[TRADE_REQUESTS] = it }
