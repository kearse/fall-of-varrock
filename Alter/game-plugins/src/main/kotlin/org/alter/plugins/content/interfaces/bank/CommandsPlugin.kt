package org.alter.plugins.content.interfaces.bank

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*

class CommandsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onCommand("obank", Privilege.ADMIN_POWER) {
            player.openBank()
        }

        /**
         * Opens the bank anywhere. Available to all players.
         *
         * NOTE: the RSProx/RuneLite client hijacks the literal command
         * "::bank" client-side (it never reaches the server), so the
         * usable aliases below are what players should actually type.
         */
        onCommand("bank", description = "Open bank anywhere") {
            player.openBank()
        }
        onCommand("pbank", description = "Open bank anywhere") {
            player.openBank()
        }
        onCommand("storage", description = "Open bank anywhere") {
            player.openBank()
        }

        /**
         * Clears all bank tab varbits for the player, effectively
         * dumping all items back into the one main tab.
         */
        onCommand("tabreset") {
            for (tab in 1..9)
                player.setVarbit(BankTabs.BANK_TAB_ROOT_VARBIT + tab, 0)
            player.setVarbit(BankTabs.SELECTED_TAB_VARBIT, 0)
        }
    }
}
