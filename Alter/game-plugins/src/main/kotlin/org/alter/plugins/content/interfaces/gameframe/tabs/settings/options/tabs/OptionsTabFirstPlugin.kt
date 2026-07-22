package org.alter.plugins.content.interfaces.gameframe.tabs.settings.options.tabs

import org.alter.api.*
import org.alter.api.ClientScript
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.DISPLAY_MODE_CHANGE_ATTR
import org.alter.game.model.attr.INTERACTING_SLOT_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.interf.DisplayMode
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.interfaces.options.OptionsTab
import org.alter.plugins.content.interfaces.options.Settings

class OptionsTabFirstPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {

        val SKULL_PROTECTION_BUTTON = 5
        val PLAYER_ATTACK_OPTION = 38
        val NPC_ATTACK_OPTION = 39
        val BRIGHTNES_BAR = 23
        val ZOOM_TOGGLE_BUTTON = 44
        val DISPLAY_MODE = 41

        /*
         * The three sub-tabs across the top of the settings panel (children of 116:1), and the
         * body each one reveals: 116:4 (controls), 116:9 (audio), 116:10 (display). The buttons
         * carry their ops in the cache, so a click always reaches us -- the tab you land on is
         * purely whatever we write to SETTINGS_TAB_FOCUS.
         */
        val CONTROLS_TAB_BUTTON = 59   // cog icon, left
        val AUDIO_TAB_BUTTON = 67      // ear icon, centre
        val DISPLAY_TAB_BUTTON = 68    // monitor icon, right

        /*
         * Audio tab: four volume rows (master, music, sound effects, area sounds), each a mute
         * toggle plus a slider track.
         */
        val MUTE_MASTER_SOUND = 85
        val MUTE_MASTER_SOUND_BAR = 96
        val MUTE_MUSIC = 99
        val MUSIC_BAR = 110
        val MUTE_SOUND = 113
        val SOUND_BAR = 124
        val MUTE_AREA_SOUND = 128
        val AREA_SOUND_BAR = 139
        val MUSIC_UNLOCK_MESSAGE = 127

        val ACCEPT_AID_BUTTON = 29
        val RUN_MODE_BUTTON = 30
        val HOUSE_OPT_BUTTON = 31
        val BOND_BUTTON = 33
        val ALL_SETTINGS_BUTTON = 32

        val AUDIO_MUSIC_VOLUME = AttributeKey<Int>()
        val SOUND_EFFECT_VOLUME = AttributeKey<Int>()
        val AREA_SOUND_VOLUME = AttributeKey<Int>()
        val MASTER_SOUND_VOLUME = AttributeKey<Int>()

        onLogin {
            player.setInterfaceEvents(
                interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB,
                component = BRIGHTNES_BAR,
                0..21,
                setting = InterfaceEvent.ClickOp1,
            )
            player.setInterfaceEvents(
                interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB,
                component = PLAYER_ATTACK_OPTION,
                1..5,
                setting = InterfaceEvent.ClickOp1,
            )
            player.setInterfaceEvents(
                interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB,
                component = NPC_ATTACK_OPTION,
                1..4,
                setting = InterfaceEvent.ClickOp1,
            )
            player.setInterfaceEvents(
                interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB,
                component = DISPLAY_MODE,
                1..3,
                setting = InterfaceEvent.ClickOp1,
            )
            listOf(MUTE_MASTER_SOUND_BAR, MUSIC_BAR, SOUND_BAR, AREA_SOUND_BAR).forEach { bar ->
                player.setInterfaceEvents(
                    interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB,
                    component = bar,
                    0..21,
                    setting = InterfaceEvent.ClickOp1,
                )
            }
        }

        /**
         * Changing display modes (fixed, resizable).
         */
        setWindowStatusLogic {
            val change = player.attr[DISPLAY_MODE_CHANGE_ATTR]
            val mode =
                when (change) {
                    2 ->
                        if (player.getVarbit(
                                Varbit.SIDESTONES_ARRAGEMENT_VARBIT,
                            ) == 1
                        ) {
                            DisplayMode.RESIZABLE_LIST
                        } else {
                            DisplayMode.RESIZABLE_NORMAL
                        }
                    else -> DisplayMode.FIXED
                }
            player.toggleDisplayInterface(mode)
        }

        bind_setting(child = DISPLAY_MODE) {
            val slot = player.attr[INTERACTING_SLOT_ATTR]!!
            val mode =
                when (slot) {
                    2 -> {
                        player.setVarbit(Varbit.SIDESTONES_ARRAGEMENT_VARBIT, 0)
                        DisplayMode.RESIZABLE_NORMAL
                    }
                    3 -> {
                        player.setVarbit(Varbit.SIDESTONES_ARRAGEMENT_VARBIT, 1)
                        DisplayMode.RESIZABLE_LIST
                    }
                    else -> DisplayMode.FIXED
                }
            if (!(mode.isResizable() && player.interfaces.displayMode.isResizable())) {
                player.runClientScript(ClientScript("settings_client_mode"), slot - 1)
            }
            player.toggleDisplayInterface(mode)
        }

        bind_setting(child = PLAYER_ATTACK_OPTION) {
            val slot = player.attr[INTERACTING_SLOT_ATTR]!!.toInt() - 1
            player.setVarp(Varp.PLAYER_ATTACK_PRIORITY_VARP, slot)
        }

        bind_setting(child = NPC_ATTACK_OPTION) {
            val slot = player.attr[INTERACTING_SLOT_ATTR]!!.toInt() - 1
            player.setVarp(Varp.NPC_ATTACK_PRIORITY_VARP, slot)
        }

        bind_setting(child = RUN_MODE_BUTTON) {
            player.toggleVarp(Varp.RUN_MODE_VARP)
        }

        bind_setting(child = ACCEPT_AID_BUTTON) {
            player.toggleVarp(Varp.ACCEPT_AID_VARP)
        }
        bind_setting(child = SKULL_PROTECTION_BUTTON) {
            player.toggleVarbit(Varbit.PK_PREVENT_SKULL)
        }

        bind_setting(CONTROLS_TAB_BUTTON) {
            player.setVarbit(Varbit.SETTINGS_TAB_FOCUS, 0)
        }
        bind_setting(AUDIO_TAB_BUTTON) {
            player.setVarbit(Varbit.SETTINGS_TAB_FOCUS, 1)
        }
        bind_setting(DISPLAY_TAB_BUTTON) {
            player.setVarbit(Varbit.SETTINGS_TAB_FOCUS, 2)
        }
        bind_setting(MUSIC_BAR) {
            player.setVarp(Varp.AUDIO_MUSIC_VOLUME, player.getInteractingSlot() * 5)
        }
        bind_setting(SOUND_BAR) {
            player.setVarp(Varp.AUDIO_SOUND_EFFECT_VOLUME, player.getInteractingSlot() * 5)
        }
        bind_setting(AREA_SOUND_BAR) {
            player.setVarp(Varp.AUDIO_AREA_SOUND_VOLUME, player.getInteractingSlot() * 5)
        }

        bind_setting(MUTE_MASTER_SOUND_BAR) {
            player.setVarp(Varp.MASTER_SOUND_VOLUME, player.getInteractingSlot() * 5)
        }
        bind_setting(MUTE_MUSIC) {
            if (player.getVarp(Varp.AUDIO_MUSIC_VOLUME) == 0) {
                player.setVarp(Varp.AUDIO_MUSIC_VOLUME, player.attr[AUDIO_MUSIC_VOLUME] ?: 100)
            } else {
                player.attr[AUDIO_MUSIC_VOLUME] = player.getVarp(Varp.AUDIO_MUSIC_VOLUME)
                player.setVarp(Varp.AUDIO_MUSIC_VOLUME, 0)
            }
        }


        bind_setting(ZOOM_TOGGLE_BUTTON) {
            player.toggleVarbit(Varbit.DISABLE_ZOOM)
        }

        bind_setting(MUTE_MASTER_SOUND) {
            if (player.getVarp(Varp.MASTER_SOUND_VOLUME) == 0) {
                player.setVarp(Varp.MASTER_SOUND_VOLUME, player.attr[MASTER_SOUND_VOLUME] ?: 100)
            } else {
                player.attr[MASTER_SOUND_VOLUME] = player.getVarp(Varp.MASTER_SOUND_VOLUME)
                player.setVarp(Varp.MASTER_SOUND_VOLUME, 0)
            }
        }

        bind_setting(MUTE_SOUND) {
            if (player.getVarp(Varp.AUDIO_SOUND_EFFECT_VOLUME) == 0) {
                player.setVarp(Varp.AUDIO_SOUND_EFFECT_VOLUME, player.attr[SOUND_EFFECT_VOLUME] ?: 100)
            } else {
                player.attr[SOUND_EFFECT_VOLUME] = player.getVarp(Varp.AUDIO_SOUND_EFFECT_VOLUME)
                player.setVarp(Varp.AUDIO_SOUND_EFFECT_VOLUME, 0)
            }
        }

        bind_setting(MUTE_AREA_SOUND) {
            if (player.getVarp(Varp.AUDIO_AREA_SOUND_VOLUME) == 0) {
                player.setVarp(Varp.AUDIO_AREA_SOUND_VOLUME, player.attr[AREA_SOUND_VOLUME] ?: 100)
            } else {
                player.attr[AREA_SOUND_VOLUME] = player.getVarp(Varp.AUDIO_AREA_SOUND_VOLUME)
                player.setVarp(Varp.AUDIO_AREA_SOUND_VOLUME, 0)
            }
        }

        bind_setting(ALL_SETTINGS_BUTTON) {
            player.openInterface(parent = 161, child = 18, interfaceId = 134, isModal = true)
            player.setInterfaceEvents(interfaceId = 134, component = 23, range = 0..9, setting = InterfaceEvent.ClickOp1)
            player.setInterfaceEvents(interfaceId = 134, component = 19, range = 0..449, setting = InterfaceEvent.ClickOp1)
            player.setInterfaceEvents(interfaceId = 134, component = 28, range = 0..41, setting = InterfaceEvent.ClickOp1)
            player.setInterfaceEvents(interfaceId = 134, component = 21, range = 0..219, setting = InterfaceEvent.ClickOp1)
        }

        /**
         * Close button ('x') on the all-settings interface.
         */
        onButton(interfaceId = OptionsTab.ALL_SETTINGS_INTERFACE_ID, component = Settings.SETTINGS_CLOSE_BUTTON_ID) {
            player.closeInterface(OptionsTab.ALL_SETTINGS_INTERFACE_ID)
        }

        /**
         * The settings search box takes over the client's text-input layer. Closing the
         * interface alone doesn't hand typing back to the chatbox, so the player is left
         * unable to chat until they re-log. Abort the input dialog on every close path
         * ('x' button and esc alike).
         */
        onInterfaceClose(OptionsTab.ALL_SETTINGS_INTERFACE_ID) {
            player.closeInputDialog()
        }
    }

fun bind_setting(
    child: Int,
    plugin: Plugin.() -> Unit,
) {
    onButton(interfaceId = OptionsTab.SETTINGS_INTERFACE_TAB, component = child) {
        plugin(this)
    }
}
}
