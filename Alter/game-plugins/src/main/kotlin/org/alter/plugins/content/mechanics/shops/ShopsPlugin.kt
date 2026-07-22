package org.alter.plugins.content.mechanics.shops

import org.alter.api.*
import org.alter.api.CommonClientScripts
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.CURRENT_SHOP_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.interfaces.bank.Bank.LAST_X_INPUT

class ShopsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        val SHOP_INTERFACE_ID = 300
        val INV_INTERFACE_ID = 301

        val BUY_OPTS = arrayOf(1, 5, 10, 50)
        val SELL_OPTS = arrayOf(1, 5, 10)

        // The 5th quantity slot ("50" in vanilla) now shows the last custom "X" the player entered,
        // defaulting to 50 until they type one — matching how the OSRS bank/shop last-quantity works.
        val DEFAULT_X = 50

        fun sellLastX(player: Player): Int =
            player.getVarbit(LAST_X_INPUT).let { if (it <= 0) DEFAULT_X else it }

        /**
         * (Re)build the inventory (interface 301) sell right-click options. Uses the "big" init script
         * so we can fit six options: Value / Sell 1 / Sell 5 / Sell 10 / Sell <lastX> / Sell X. Called
         * on open and again after a custom X is entered, so the 5th label reflects the latest amount.
         */
        fun initSellOptions(player: Player) {
            player.runClientScript(
                INTERFACE_INV_INIT_BIG,
                19726336,
                93,
                4,
                7,
                0,
                -1,
                "Value<col=ff9040>",
                "Sell ${SELL_OPTS[0]}<col=ff9040>",
                "Sell ${SELL_OPTS[1]}<col=ff9040>",
                "Sell ${SELL_OPTS[2]}<col=ff9040>",
                "Sell ${sellLastX(player)}<col=ff9040>",
                "Sell X<col=ff9040>",
            )
        }

        onInterfaceOpen(interfaceId = SHOP_INTERFACE_ID) {
            player.attr[CURRENT_SHOP_ATTR]?.let { shop ->
                initSellOptions(player)
                shop.viewers.add(player.uid)
            }
        }

        onInterfaceClose(interfaceId = SHOP_INTERFACE_ID) {
            player.attr[CURRENT_SHOP_ATTR]?.let { shop ->
                shop.viewers.remove(player.uid)
                player.closeInterface(interfaceId = INV_INTERFACE_ID)
            }
        }

        onButton(interfaceId = SHOP_INTERFACE_ID, component = 16) {
            player.attr[CURRENT_SHOP_ATTR]?.let { shop ->
                val opt = player.getInteractingOption()
                val slot = player.getInteractingSlot() - 1
                val shopItem = shop.items[slot] ?: return@onButton

                when (opt) {
                    1 -> shop.currency.onSellValueMessage(player, shopItem)
                    10 -> world.sendExamine(player, shopItem.item, ExamineEntityType.ITEM)
                    else -> {
                        val amount =
                            when (opt) {
                                2 -> BUY_OPTS[0]
                                3 -> BUY_OPTS[1]
                                4 -> BUY_OPTS[2]
                                5 -> BUY_OPTS[3]
                                else -> return@onButton
                            }
                        shop.currency.sellToPlayer(player, shop, slot, amount)
                    }
                }
            }
        }

        onButton(interfaceId = INV_INTERFACE_ID, component = 0) {
            player.attr[CURRENT_SHOP_ATTR]?.let { shop ->
                val opt = player.getInteractingOption()
                val slot = player.getInteractingSlot()
                val item = player.inventory[slot] ?: return@onButton

                when (opt) {
                    1 -> shop.currency.onBuyValueMessage(player, shop, item.id)
                    10 -> world.sendExamine(player, item.id, ExamineEntityType.ITEM)
                    6 -> {
                        // "Sell X" — prompt for a custom amount, remember it, then refresh the menu so
                        // the entered value shows in the 5th slot (where "50" used to be).
                        player.queue(TaskPriority.WEAK) {
                            val amount = inputInt(player, "Sell how many?")
                            if (amount > 0) {
                                player.setVarbit(LAST_X_INPUT, amount)
                                shop.currency.buyFromPlayer(player, shop, slot, amount)
                                initSellOptions(player)
                            }
                        }
                    }
                    else -> {
                        val amount =
                            when (opt) {
                                2 -> SELL_OPTS[0]
                                3 -> SELL_OPTS[1]
                                4 -> SELL_OPTS[2]
                                5 -> sellLastX(player)
                                else -> return@onButton
                            }
                        shop.currency.buyFromPlayer(player, shop, slot, amount)
                    }
                }
            }
        }
    }
}
