package org.alter.plugins.content.mechanics.shops

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Roat-style tabbed storefronts: an NPC that owns several shops opens ONE shop window with a
 * tab strip along its bottom edge (drawn by the custom client's `lofshoptabs` plugin), instead
 * of funnelling the player through dialogue `options()` menus.
 *
 * ### How it reaches the client
 * Same transport as the command window (`FOV_CMDS:`): plain [ChatMessageType.GAME_MESSAGE]
 * lines with a machine prefix the client parses on arrival and hides from the chat box.
 * A vendor's tab set fits ONE line (labels are short), so no open/row/end batching is needed:
 *
 *   `FOV_SHOP:tabs|<sel>|<label0>~<iconItemId0>|<label1>~<iconItemId1>|...`   (tabbed store opened)
 *   `FOV_SHOP:clear`                                        (sent by every [openShop] — see PlayerExt)
 *
 * Each tab carries a REAL item id (`icon` rscm key, resolved defensively to -1 if missing); the
 * client draws the item sprite as the tab button and shows the label only as a hover tooltip.
 *
 * Clicking a tab client-side sends `::shoptab <index>` as public chat, which
 * `MessagePublicHandler` routes to the `shoptabclick` command → [switch] → re-open on the
 * chosen shop + a fresh `tabs|` line. The tab list lives in a NON-persistent player attr, so
 * a tab click can never open a store the vendor didn't just offer.
 */
object ShopTabs {
    /** One tab: hover label + icon item (rscm key, drawn as the tab button) + what opening it
     *  does (usually `openShop(name)`, but a tab can resolve dynamically — e.g. Horvik's
     *  rank-gated armoury tier). */
    class Tab(val label: String, val icon: String? = null, val open: (Player) -> Unit) {
        constructor(label: String, shop: String, icon: String? = null) :
            this(label, icon, { p -> p.openShop(shop) })
    }

    /** The tab set (+ optional vendor guard) backing the storefront currently on screen. */
    class Store(val tabs: List<Tab>, val guard: ((Player) -> Boolean)?)

    /** In-memory only — deliberately not persisted; it's meaningless outside the open window. */
    val SHOP_TABS_ATTR = AttributeKey<Store>()

    /** Machine prefix for storefront lines (must match the client's LofShopTabsPlugin). */
    const val PREFIX = "FOV_SHOP:"

    /**
     * Open [tabs]' storefront on tab [index]. [guard] (e.g. "shops closed during a siege") is
     * re-checked on every tab switch and should do its own player messaging; return false to veto.
     */
    fun open(player: Player, tabs: List<Tab>, index: Int = 0, guard: ((Player) -> Boolean)? = null) {
        if (tabs.isEmpty()) return
        if (guard != null && !guard(player)) return
        val i = index.coerceIn(0, tabs.size - 1)
        player.attr[SHOP_TABS_ATTR] = Store(tabs, guard)
        // openShop sends "FOV_SHOP:clear" first, so the tabs line below always lands on a clean strip.
        tabs[i].open(player)
        if (tabs.size > 1) {
            val line = buildString {
                append(PREFIX).append("tabs|").append(i)
                tabs.forEach {
                    val iconId = it.icon?.let { key -> runCatching { getRSCM(key) }.getOrNull() } ?: -1
                    append('|').append(it.label).append('~').append(iconId)
                }
            }
            player.message(line, ChatMessageType.GAME_MESSAGE)
        }
    }

    /** Tab click from the client overlay (`::shoptab <index>`): re-open on that tab. */
    fun switch(player: Player, index: Int) {
        val store = player.attr[SHOP_TABS_ATTR] ?: return
        if (index !in store.tabs.indices) return
        open(player, store.tabs, index, store.guard)
    }
}
