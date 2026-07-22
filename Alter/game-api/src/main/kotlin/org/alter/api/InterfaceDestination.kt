package org.alter.api

import org.alter.game.model.interf.DisplayMode

/**
 * Where a sub-interface lives inside each gameframe root: 548 (fixed), 161 (resizable - classic
 * layout), 164 (resizable - modern layout), 165 (fullscreen).
 *
 * The [resizeListChildId] column used to hold ids from a different revision; every one of them
 * pointed at the wrong component of 164 and several landed on type-5 sprites, which the client
 * cannot host a sub-interface in — switching to the modern layout killed the client outright.
 * The values below are read out of OUR cache: 161 and 164 share the same layout skeleton, so each
 * modern id is the 164 component that mirrors the (already correct) 161 one, e.g. the 14 side
 * panes are 548:80-93 / 161:76-89 / 164:73-86.
 */
enum class InterfaceDestination(
    val interfaceId: Int,
    val fixedChildId: Int,
    val resizeChildId: Int,
    val resizeListChildId: Int,
    val fullscreenChildId: Int = -1,
    val clickThrough: Boolean = true,
) {
    CHAT_BOX(interfaceId = 162, fixedChildId = 10, resizeChildId = 96, resizeListChildId = 93, fullscreenChildId = 1,),
    XP_COUNTER(interfaceId = 122, fixedChildId = 33, resizeChildId = 9, resizeListChildId = 9, fullscreenChildId = 11, clickThrough = true,),
    ATTACK(interfaceId = 593, fixedChildId = 80, resizeChildId = 76, resizeListChildId = 73, fullscreenChildId = 15),
    SKILLS(interfaceId = 320, fixedChildId = 81, resizeChildId = 77, resizeListChildId = 74, fullscreenChildId = 16),
    QUEST_ROOT(interfaceId = 629, fixedChildId = 82, resizeChildId = 78, resizeListChildId = 75, fullscreenChildId = 17),
    INVENTORY(interfaceId = 149, fixedChildId = 83, resizeChildId = 79, resizeListChildId = 76, fullscreenChildId = 18),
    EQUIPMENT(interfaceId = 387, fixedChildId = 84, resizeChildId = 80, resizeListChildId = 77, fullscreenChildId = 19),
    PRAYER(interfaceId = 541, fixedChildId = 85, resizeChildId = 81, resizeListChildId = 78, fullscreenChildId = 20),
    MAGIC(interfaceId = 218, fixedChildId = 86, resizeChildId = 82, resizeListChildId = 79, fullscreenChildId = 21),
    CLAN_CHAT(interfaceId = 707, fixedChildId = 87, resizeChildId = 83, resizeListChildId = 80, fullscreenChildId = 22),
    ACCOUNT_MANAGEMENT(interfaceId = 109, fixedChildId = 88, resizeChildId = 84, resizeListChildId = 81, fullscreenChildId = 23),
    SOCIAL(interfaceId = 429, fixedChildId = 89, resizeChildId = 85, resizeListChildId = 82, fullscreenChildId = 24),
    LOG_OUT(interfaceId = 182, fixedChildId = 90, resizeChildId = 86, resizeListChildId = 83, fullscreenChildId = 25),
    SETTINGS(interfaceId = 116, fixedChildId = 91, resizeChildId = 87, resizeListChildId = 84, fullscreenChildId = 26),
    EMOTES(interfaceId = 216, fixedChildId = 92, resizeChildId = 88, resizeListChildId = 85, fullscreenChildId = 27),
    MUSIC(interfaceId = 239, fixedChildId = 93, resizeChildId = 89, resizeListChildId = 86, fullscreenChildId = 28),
    PRIVATE_CHAT(interfaceId = 163, fixedChildId = 35, resizeChildId = 91, resizeListChildId = 88, fullscreenChildId = 30), // Fixed @TODO
    MINI_MAP(interfaceId = 160, fixedChildId = 24, resizeChildId = 33, resizeListChildId = 33, fullscreenChildId = 31),
    MAIN_SCREEN(interfaceId = -1, fixedChildId = 9, resizeChildId = 16, resizeListChildId = 16, fullscreenChildId = 13, clickThrough = false,),
    TAB_AREA(interfaceId = -1, fixedChildId = 77, resizeChildId = 74, resizeListChildId = 71, clickThrough = false), // @TODO
    WALKABLE(interfaceId = -1, fixedChildId = 9, resizeChildId = 3, resizeListChildId = 3),
    WORLD_MAP(interfaceId = -1, fixedChildId = 42, resizeChildId = 18, resizeListChildId = 18, fullscreenChildId = 36),
    WORLD_MAP_FULL(interfaceId = -1, fixedChildId = 27, resizeChildId = 21, resizeListChildId = 21, fullscreenChildId = 27, clickThrough = false), // @TODO
    OVERLAY(interfaceId = 651, fixedChildId = 43, resizeChildId = 6, resizeListChildId = 6, fullscreenChildId = 29),
    ;

    /**
     * gg.rsmod.game.message.impl.RebuildNormalMessage
     * gg.rsmod.game.message.impl.UpdateZonePartialEnclosedMessage
     */
    fun isSwitchable(): Boolean =
        when (this) {
            CHAT_BOX, MAIN_SCREEN, WALKABLE, TAB_AREA,
            ATTACK, SKILLS, QUEST_ROOT, INVENTORY, EQUIPMENT,
            PRAYER, MAGIC, CLAN_CHAT, ACCOUNT_MANAGEMENT,
            SOCIAL, LOG_OUT, SETTINGS, EMOTES, MUSIC, OVERLAY,
            PRIVATE_CHAT, MINI_MAP, XP_COUNTER, WORLD_MAP,
            -> true
            else -> false
        }

    companion object {
        val values = enumValues<InterfaceDestination>()

        fun getModals() = values.filter { pane -> pane.interfaceId != -1 }
    }
}

fun getDisplayComponentId(displayMode: DisplayMode) =
    when (displayMode) {
        DisplayMode.FIXED -> 548
        DisplayMode.RESIZABLE_NORMAL -> 161
        DisplayMode.RESIZABLE_LIST -> 164
        DisplayMode.FULLSCREEN -> 165
        else -> throw RuntimeException("Unhandled display mode.")
    }

fun getChildId(
    pane: InterfaceDestination,
    displayMode: DisplayMode,
): Int =
    when (displayMode) {
        DisplayMode.FIXED -> pane.fixedChildId
        DisplayMode.RESIZABLE_NORMAL -> pane.resizeChildId
        DisplayMode.RESIZABLE_LIST -> pane.resizeListChildId
        DisplayMode.FULLSCREEN -> pane.fullscreenChildId
        else -> throw RuntimeException("Unhandled display mode.")
    }
