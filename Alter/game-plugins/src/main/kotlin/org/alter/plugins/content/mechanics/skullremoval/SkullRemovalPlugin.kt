package org.alter.plugins.content.mechanics.skullremoval

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
import org.alter.game.model.timer.SKULL_ICON_DURATION_TIMER
import org.alter.game.plugin.*
import org.alter.plugins.content.economy.pk.LootKeys

class SkullRemovalPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onTimer(SKULL_ICON_DURATION_TIMER) {
            // Loot keys keep their own overhead (the keyed skull) alive past the PK skull timer —
            // syncOverhead clears the icon when keyless, matching the old behaviour.
            LootKeys.syncOverhead(player)
        }

        // OSRS: dying clears your skull. This is a POST-death hook, so the death-drop
        // calculation has already used the skulled state (keep-0 applies to THIS death,
        // the skull is gone for the next one). syncOverhead re-derives the icon from the
        // now-removed timer and the (confiscated) loot keys.
        onPlayerDeath {
            player.timers.remove(SKULL_ICON_DURATION_TIMER)
            LootKeys.syncOverhead(player)
        }
    }
}
