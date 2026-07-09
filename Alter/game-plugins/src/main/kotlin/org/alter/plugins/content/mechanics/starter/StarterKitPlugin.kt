package org.alter.plugins.content.mechanics.starter

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.NEW_ACCOUNT_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.game.action.EquipAction
import org.alter.rscm.RSCM.getRSCM

class StarterKitPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        onLogin {
            if (player.attr[NEW_ACCOUNT_ATTR] ?: return@onLogin) {
                // Combat-ready kit (master design brief §1A): the first impression should say
                // "fighter", not "lumberjack". The recruit can march to the frontier and cull
                // goblins immediately. A few starter tools remain so the supply skills are
                // reachable from minute one, but the weapon + food lead.
                with(player.inventory) {
                    add(getRSCM("item.bread"), 10)        // ~10 food to survive the first fights
                    add(getRSCM("item.shrimps"), 5)
                    add(getRSCM("item.bronze_scimitar"))  // backup weapon (one is auto-equipped below)
                    add(getRSCM("item.tinderbox"))        // starter skilling tools kept, de-emphasised
                    add(getRSCM("item.bronze_pickaxe"))
                    add(getRSCM("item.bronze_axe"))
                }
                // Auto-equip a bronze scimitar so the recruit spawns armed and ready.
                EquipAction.equip(player, Item(getRSCM("item.bronze_scimitar")))
            }
        }

    }
}
