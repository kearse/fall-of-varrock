package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.objs.OpObj
import org.alter.game.model.move.GroundItemRouteAction
import org.alter.game.message.MessageHandler
import org.alter.game.model.EntityType
import org.alter.game.model.Tile
import org.alter.game.model.attr.CLIENT_KEY_COMBINATION
import org.alter.game.model.attr.INTERACTING_GROUNDITEM_ATTR
import org.alter.game.model.attr.INTERACTING_OPT_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import java.lang.ref.WeakReference

/**
 * @author Tom <rspsmods@gmail.com>
 */
class OpObjHandler : MessageHandler<OpObj> {
    override fun consume(
        client: Client,
        message: OpObj,
    ) {
        /*
         * If tile is too far away, don't process it.
         */
        val tile = Tile(message.x, message.z, client.tile.height)
        if (!tile.viewableFrom(client.tile, Player.TILE_VIEW_DISTANCE)) {
            return
        }
        if (!client.lock.canGroundItemInteract()) {
            return
        }
        log(
            client,
            "Ground Item action %d: item=%d, x=%d, y=%d, movement=%b",
            message.op,
            message.id,
            message.x,
            message.z,
            message.controlKey,
        )
        /*
         * Get the region chunk that the object would belong to.
         */
        val chunk = client.world.chunks.getOrCreate(tile)
        val item =
            chunk.getEntities<GroundItem>(tile, EntityType.GROUND_ITEM).firstOrNull {
                it.item == message.id && it.canBeViewedBy(client)
            }
        if (item == null) {
            // The client interacted with a ground item the server has no live, viewable match
            // for at this tile: a client-side "phantom". These happen because the client tracks
            // ground objs purely by OBJ_ADD/OBJ_DEL, and an obj can be added to the client twice
            // (a cached obj-add is replayed when a chunk re-enters view even though the client
            // still holds the live copy). A single pickup then sends one OBJ_DEL and only clears
            // one copy, so the item still appears on the ground and looks pickable again.
            //
            // Tell the client to delete it so it can't be interacted with again. Non-stackable
            // ground objs (armour/weapons/most gear — what this bug was reported for) always have
            // a quantity of 1, which is the quantity the client is holding for the phantom, so an
            // OBJ_DEL with quantity 1 removes it. If the server really does have a (different)
            // item here it is untouched, since OBJ_DEL matches on both id and quantity.
            chunk.clearClientObj(client, message.id, amount = 1, tile = tile)
            if (client.world.devContext.debugItemActions) {
                val atTile = chunk.getEntities<GroundItem>(tile, EntityType.GROUND_ITEM)
                log(
                    client,
                    "Cleared phantom ground item %d at (%d,%d,h%d); %d real item(s) remain at tile",
                    message.id, message.x, message.z, tile.height, atTile.size,
                )
            }
            return
        }
        client.attr[CLIENT_KEY_COMBINATION] = if (message.controlKey) 2 else 0
        client.attr[INTERACTING_OPT_ATTR] = message.op
        client.attr[INTERACTING_GROUNDITEM_ATTR] = WeakReference(item)
        client.executePlugin(GroundItemRouteAction.walkPlugin)
    }
}
