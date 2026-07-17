package org.alter.game.model.entity

import org.alter.game.model.attr.DEBUG_CHATBOX_ATTR

/**
 * Whether *this player* should be shown the developer **chatbox debug diagnostics** — the
 * "Unhandled item/button/object action", "Undefined spell", examine-id suffixes and similar
 * messages emitted when an interaction has no registered handler.
 *
 * These are OFF for everyone by default so ordinary players never see them. A player opts in for
 * their own session with the admin `::debug` command (sets [DEBUG_CHATBOX_ATTR]). The server-wide
 * `dev-settings` flag ([global]) still forces the category on globally when set, preserving the
 * old dev-server behaviour — production leaves those flags false.
 *
 * @param global the matching server-wide [org.alter.game.DevContext] flag for this diagnostic
 *               category (e.g. `world.devContext.debugItemActions`).
 */
fun Player.showChatboxDebug(global: Boolean): Boolean =
    global || attr[DEBUG_CHATBOX_ATTR] == true

/** Item-action diagnostics ("Unhandled item action …"). */
val Player.debugItemActions: Boolean
    get() = showChatboxDebug(world.devContext.debugItemActions)

/** Button/component diagnostics ("Unhandled button action …"). */
val Player.debugButtons: Boolean
    get() = showChatboxDebug(world.devContext.debugButtons)

/** Object-action diagnostics ("Unhandled object action …"). */
val Player.debugObjects: Boolean
    get() = showChatboxDebug(world.devContext.debugObjects)

/** Magic-spell diagnostics ("Undefined combat spell …"). */
val Player.debugMagicSpells: Boolean
    get() = showChatboxDebug(world.devContext.debugMagicSpells)

/** Examine-id suffixes (the " (id)" appended to examine text). */
val Player.debugExamines: Boolean
    get() = showChatboxDebug(world.devContext.debugExamines)
