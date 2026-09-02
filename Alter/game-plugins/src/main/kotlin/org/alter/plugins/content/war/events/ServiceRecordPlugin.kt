package org.alter.plugins.content.war.events

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/** `::service` — the player's [ServiceRecord] readout. The write hooks live with the systems that
 *  earn service (the campaign engine's finish + the Supply Depot). */
class ServiceRecordPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("service", description = "Show your service record: wars fought, supplies handed in, lifetime War Effort") {
            ServiceRecords.statusLines(player).forEach { player.message(it) }
        }
    }
}
