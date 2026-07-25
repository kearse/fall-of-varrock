package org.alter.plugins.content.areas.lumbridge.npcs

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

class CookPlugin (
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        // Home-hub declutter: the Cook was removed from the open castle ground floor. His talk-to
        // binding is kept so re-adding a spawn (or relocating him) restores Cook's Assistant with
        // no other changes. NB: while unspawned, Cook's Assistant has no Cook to talk to.
        // spawnNpc("npc.cook_4626", x = 3209, z = 3215, direction = Direction.SOUTH)

        onNpcOption("npc.cook_4626", option = "talk-to") {
            player.queue { dialog(player) }
        }
    }

    suspend fun QueueTask.dialog(player: Player) {
        chatPlayer(player, "Hello there, cook!")
        chatPlayer(player, "Do you have anything for me?")
        chatNpc(player, "Sorry, not yet.")
    }
}