package org.alter.plugins.content.economy.grandexchange

import org.alter.api.ext.getInteractingOption
import org.alter.api.ext.getInteractingSlot
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Grand Exchange — Phase 0 prototype (dev-only).**
 *
 * Confirms the native OSRS GE interface (cache group 465) renders server-driven on our custom client
 * and that the existing `::item` search feeds it, BEFORE the full offer engine / economy is built.
 * See [GrandExchangeInterface] for the driver + the component-discovery plan.
 *
 * Commands (both gated to [Privilege.DEV_POWER]):
 *  - `::geproto`  — open the GE board (interface 465).
 *  - `::gesearch` — run the `::item` GE search and report the picked item (proves the search → offer flow).
 *
 * The board's top-level components are wired to a discovery logger so, with `::debug` on, every slot /
 * button click prints `comp/op/slot` — the empirical map that becomes the real offer-engine constants.
 * Nothing here is player-facing yet; the Trading Post (`::market`) remains the live marketplace.
 */
class GrandExchangePrototypePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("geproto", Privilege.DEV_POWER, description = "Open the native GE interface (465) prototype") {
            GrandExchangeInterface.open(player)
        }

        onCommand("gesearch", Privilege.DEV_POWER, description = "Test the ::item GE search feeding an offer") {
            GrandExchangeInterface.searchForOffer(player, buy = true)
        }

        // Component-discovery harness: log clicks across the GE board's top-level components so the
        // rev-228 comp/op/slot ids can be mapped in-game (the method used to wire the Loot Chest at 742).
        val iface = GrandExchangeInterface.IFACE
        for (comp in 0..40) {
            onButton(iface, comp) {
                GrandExchangeInterface.debugClick(player, comp, player.getInteractingOption(), player.getInteractingSlot())
            }
        }
        onInterfaceClose(iface) { GrandExchangeInterface.onClosed(player) }
    }
}
