package org.alter.plugins.content.interfaces.gameframe.tabs.emotes

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
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.interfaces.emotes.Emote
import org.alter.plugins.content.interfaces.emotes.EmotesTab.COMPONENT_ID
import org.alter.plugins.content.interfaces.emotes.EmotesTab.performEmote

class EmotesTabPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onLogin {
            player.setInterfaceEvents(
                interfaceId = COMPONENT_ID,
                component = 2,
                range = 0..51,
                setting = arrayOf(InterfaceEvent.ClickOp1, InterfaceEvent.ClickOp2),
            )
            // Goblin bow/salute are free here (their OSRS unlock, The Lost Tribe, doesn't exist on
            // this server). The server no longer checks the varbit (Emote.kt), but the stock emote-tab
            // clientscript still greys the two icons until it reads 7 — so pin it on login.
            if (player.getVarbit(Varbit.GOBLIN_EMOTES_VARBIT) != 7) {
                player.setVarbit(Varbit.GOBLIN_EMOTES_VARBIT, 7)
            }
        }

        onButton(interfaceId = COMPONENT_ID, component = 2) p@{
            val slot = player.getInteractingSlot()
            val emote = Emote.values.firstOrNull { e -> e.slot == slot } ?: return@p
            performEmote(player, emote)
        }
    }
}
