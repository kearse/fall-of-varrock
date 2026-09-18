package org.alter.plugins.content.items.pets

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getInteractingNpc
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_ITEM_SLOT
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [Pets] — the follower a pet becomes when you drop it.
 *
 * Three bindings:
 *  - **Drop** (inventory option 7) on every bound pet item: intercepted, so the item becomes a
 *    follower instead of a ground item. `InventoryPlugin` only spawns the floor item when no
 *    plugin claims the option, so claiming it here is the whole switch.
 *  - **Pick-up** on every follower npc: the pet goes back into the pack.
 *  - login / logout / a per-tick brain: the pet is re-spawned at your side, stored on logout, and
 *    follows you in between.
 *
 * `::pet` is the escape hatch — it recalls the follower into your inventory from anywhere, so a
 * pet that ends up somewhere you can't click it is never lost.
 */
class PetsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Resolving reads the npc cache, so it happens at construction (the cache is loaded well
        // before plugins) and the bindings are made from what actually resolved.
        Pets.resolve()

        Pets.itemIds().forEach { itemId ->
            // Option 7 is the client's inventory DROP (see InventoryPlugin's dispatch), and it is
            // passed through raw — not offset by one like the named item options. A bind clash
            // (another plugin already owns this item's drop) must never stop the server booting.
            runCatching {
                r.bindItem(itemId, DROP_OPTION) {
                    val slot = player.attr[INTERACTING_ITEM_SLOT] ?: return@bindItem
                    if (!Pets.drop(player.world, player, itemId, slot)) {
                        player.message("<col=801700>You can't drop that right now.</col>")
                    }
                }
            }.onFailure { logger.error(it) { "[pets] could not bind Drop on item $itemId" } }
        }

        Pets.followerPickUpSlots().forEach { (npcId, optionIndex) ->
            // Same +1 convention as KotlinPlugin.onNpcOption: the client sends option index + 1.
            runCatching {
                r.bindNpc(npcId, optionIndex + 1) {
                    val npc = runCatching { player.getInteractingNpc() }.getOrNull() ?: return@bindNpc
                    if (!Pets.ownedBy(npc, player)) {
                        player.message("That isn't your follower.")
                        return@bindNpc
                    }
                    Pets.pickUp(player.world, player)
                }
            }.onFailure { logger.error(it) { "[pets] could not bind Pick-up on npc $npcId" } }
        }

        onLogin { Pets.onLogin(world, player) }
        onLogout { Pets.onLogout(world, player) }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = 1 }
        onTimer(timer) {
            runCatching { Pets.tick(world) }.onFailure { logger.error(it) { "[pets] follow tick failed" } }
            world.timers[timer] = 1
        }

        onCommand("pet", description = "Call your follower back into your inventory") { recall(player) }
    }

    private fun recall(p: Player) {
        if (Pets.petOf(p) == null) {
            p.message("You don't have a follower. <col=801700>Drop</col> a pet from your inventory to field one.")
            return
        }
        Pets.pickUp(p.world, p)
    }

    private companion object {
        /** The inventory "Drop" option id the client sends (InventoryPlugin's `option == 7` branch). */
        const val DROP_OPTION = 7
    }
}
