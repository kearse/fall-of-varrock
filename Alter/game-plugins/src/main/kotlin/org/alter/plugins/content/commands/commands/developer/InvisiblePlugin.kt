package org.alter.plugins.content.commands.commands.developer

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.info.PlayerInfo
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

class InvisiblePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onCommand("invisible", Privilege.DEV_POWER, description = "Become a ghost?") {
            player.invisible = !player.invisible
            // Protocol-level hide: the avatar stops being sent to other clients
            // entirely. The appearance flag alone isn't enough — RuneLite-based
            // clients (i.e. our custom client) can still render appearance-hidden
            // players, and J-Mod ranked observers see them regardless.
            player.avatar.hidden = player.invisible
            // The appearance hidden flag only reaches other clients through an
            // appearance sync — without this, the toggle does nothing until the
            // next unrelated appearance change (e.g. equipping or relogging).
            PlayerInfo(player).syncAppearance()
            player.message("Invisible: ${if (!player.invisible) "<col=801700>false</col>" else "<col=178000>true</col>"}")
        }
    }
}
