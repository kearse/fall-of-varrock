package org.alter.game.task

import net.rsprot.crypto.xtea.XteaKey
import net.rsprot.protocol.game.outgoing.info.npcinfo.SetNpcUpdateOrigin
import net.rsprot.protocol.message.ConsumableMessage
import net.rsprot.protocol.game.outgoing.info.util.BuildArea
import net.rsprot.protocol.game.outgoing.map.RebuildNormal
import net.rsprot.protocol.game.outgoing.map.RebuildRegion
import net.rsprot.protocol.game.outgoing.map.util.RebuildRegionZone
import net.rsprot.protocol.game.outgoing.worldentity.SetActiveWorld
import org.alter.game.model.Coordinate
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.instance.InstancedChunkSet
import org.alter.game.model.region.Chunk
import org.alter.game.service.GameService
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A [GameTask] that is responsible for sending [org.alter.game.model.entity.Pawn]
 * data to [org.alter.game.model.entity.Pawn]s.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class SequentialSynchronizationTask : GameTask {
    override fun execute(
        world: World,
        service: GameService,
    ) {
        val worldPlayers = world.players
        val worldNpcs = world.npcs

        worldPlayers.forEach { p ->
            try {
                p.playerCoordCycleTask()
            } catch (e: Exception) {
                logger.error(e) { "Synchronization 'coordCycle' failed for '${p.username}' (skipped)." }
            }
        }

        world.network.worldEntityInfoProtocol.update()

        // First off, write the world entity info to the client - it must
        // be aware of the updates before receiving the rebuild world entity packets
        world.players.forEach {
            if (it.entityType.isHumanControlled && it.initiated) {
                it.write(it.worldEntityInfo.toPacket()) // try-catch it, this _can_ throw exceptions during .toPacket()
            }
        }

        world.players.forEach {
            if (it.entityType.isHumanControlled && it.initiated) {
                // If the player is not on a dynamic world entity, we can set the
                // origin point as the local player coordinate
                val (x, z, level) = it.tile
                it.npcInfo.updateCoord(-1, level, x, z)
                it.playerInfo.updateRenderCoord(-1, level, x, z)
            }
        }
        world.network.playerInfoProtocol.update()
        world.network.npcInfoProtocol.update()

        // Clientless players (PK bots, companions) have no channel, so the info packets the
        // protocols just computed for them are never written — and rsprot's next update() then
        // WARNs "…calculated but not sent out to the client" three times per bot per cycle,
        // drowning the log. Mark their packets consumed to discard them; the backing buffers
        // are recycled internally by rsprot on the next update.
        world.players.forEach {
            if (!it.entityType.isHumanControlled) {
                try {
                    it.worldEntityInfo.toPacket().consume()
                    it.playerInfo.toPacket().consume()
                    (it.npcInfo.toPacket(-1) as? ConsumableMessage)?.consume()
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to consume clientless info packet for '${it.username}'." }
                }
            }
        }

        world.players.forEach {
            /**
             * Non-human [org.alter.game.model.entity.Player]s do not need this
             * to send any synchronization data to their game-client as they do
             * not have one.
             */
            if (it.entityType.isHumanControlled && it.initiated) {
                it.write(SetActiveWorld(SetActiveWorld.RootWorldType(it.tile.height)))
                it.write(it.playerInfo.toPacket()) // try-catch it, this _can_ throw exceptions during .toPacket()
                it.write(
                    SetNpcUpdateOrigin(
                        it.tile.x - (it.buildArea.zoneX shl 3),
                        it.tile.z - (it.buildArea.zoneZ shl 3),
                    ),
                )
                it.write(it.npcInfo.toPacket(-1))
            }
        }

        for (n in worldNpcs.entries) {
            n?.npcPostSynchronizationTask()
        }
        worldPlayers.forEach { p ->
            try {
                p.postCycle()
            } catch (e: Exception) {
                logger.error(e) { "Synchronization 'postCycle' failed for '${p.username}' (skipped)." }
            }
        }
    }
}

fun Player.playerPreSynchronizationTask() {
    val pawn = this
    pawn.movementQueue.cycle()
    // Clientless players (e.g. PK bots) have no socket and never had their map/world-info avatars
    // initialised via the login rebuild, so they must NOT run the client map-rebuild path — calling
    // worldEntityInfo.updateBuildArea on them throws "World info details not allocated for world -1",
    // which aborts the whole PlayerCycleTask and stalls every other player's queues (combat, etc.).
    if (!pawn.entityType.isHumanControlled) return
    val last = pawn.lastKnownRegionBase
    val current = pawn.tile
    if (last == null || shouldRebuildRegion(last, current)) {
        val regionX = ((current.x shr 3) - (Chunk.MAX_VIEWPORT shr 4)) shl 3
        val regionZ = ((current.z shr 3) - (Chunk.MAX_VIEWPORT shr 4)) shl 3
        // @TODO UpdateZoneFullFollowsMessage
        pawn.lastKnownRegionBase = Coordinate(regionX, regionZ, current.height)
        val xteaService = pawn.world.xteaKeyService!!
        val instance = pawn.world.instanceAllocator.getMap(current)
        val rebuildMessage =
            when {
                instance != null -> {
                    // rsprot invokes the provider with ABSOLUTE zone coords (the 13x13 build area
                    // around the player's zone), but the InstancedChunkSet is keyed RELATIVE to the
                    // instance's SW corner (0,0 = bottom-left chunk). Translate before the lookup —
                    // without this every zone resolves null and the client renders pure void
                    // (black map/"freecam"), which is what instanced content used to hit.
                    val baseZoneX = instance.area.bottomLeftX shr 3
                    val baseZoneZ = instance.area.bottomLeftY shr 3
                    RebuildRegion(
                        current.x shr 3,
                        current.z shr 3,
                        true,
                        object : RebuildRegion.RebuildRegionZoneProvider {
                            override fun provide(
                                zoneX: Int,
                                zoneZ: Int,
                                level: Int,
                            ): RebuildRegionZone? {
                                val relX = zoneX - baseZoneX
                                val relZ = zoneZ - baseZoneZ
                                if (relX < 0 || relZ < 0) return null // outside the instance → void
                                val coord = InstancedChunkSet.getCoordinates(relX, relZ, level)
                                val chunk = instance.chunks.values[coord] ?: return null
                                return RebuildRegionZone(
                                    chunk.zoneX,
                                    chunk.zoneZ,
                                    chunk.height,
                                    chunk.rot,
                                    XteaKey.ZERO,
                                )
                            }
                        },
                    )
                }
                else -> RebuildNormal(current.x shr 3, current.z shr 3, -1, xteaService)
            }
        pawn.buildArea =
            BuildArea((current.x ushr 3) - 6, (current.z ushr 3) - 6).apply {
                pawn.playerInfo.updateBuildArea(-1, this)
                pawn.npcInfo.updateBuildArea(-1, this)
                pawn.worldEntityInfo.updateBuildArea(this)
            }
        pawn.write(rebuildMessage)
    }
}

private fun shouldRebuildRegion(
    old: Coordinate,
    new: Tile,
): Boolean {
    val dx = new.x - old.x
    val dz = new.z - old.z

    return dx <= Player.NORMAL_VIEW_DISTANCE || dx >= Chunk.MAX_VIEWPORT - Player.NORMAL_VIEW_DISTANCE - 1 ||
        dz <= Player.NORMAL_VIEW_DISTANCE || dz >= Chunk.MAX_VIEWPORT - Player.NORMAL_VIEW_DISTANCE - 1
}

fun Npc.npcPreSynchronizationTask() {
    val pawn = this
    pawn.movementQueue.cycle()
}

fun Npc.npcPostSynchronizationTask() {
    val pawn = this
    val oldTile = pawn.lastTile
    val moved = oldTile == null || !oldTile.sameAs(pawn.tile)

    if (moved) {
        pawn.lastTile = pawn.tile
    }
    pawn.moved = false
    pawn.steps = null
}

/**
 * Updates the coords for all players within the rsprot library. This is run after processing to properly account for
 * displacement effects [dspear, etc]
 */
fun Player.playerCoordCycleTask() {
    this.playerInfo.updateCoord(this.tile.height, this.tile.x, this.tile.z)
    this.npcInfo.updateCoord(-1, this.tile.height, this.tile.x, this.tile.z)
    this.worldEntityInfo.updateCoord(-1, this.tile.height, this.tile.x, this.tile.z)
}
