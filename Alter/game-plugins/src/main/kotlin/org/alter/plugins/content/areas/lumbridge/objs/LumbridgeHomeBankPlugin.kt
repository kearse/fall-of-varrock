package org.alter.plugins.content.areas.lumbridge.objs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Home bank** — a line of bank booths along the west wall of the opened Lumbridge castle ground
 * floor (station 3's back wall). Booths are spawned as dynamic objects at world init; the generic
 * [org.alter.plugins.content.objects.bankbooth.BankBoothsPlugin] already binds the "Bank" option
 * onto `object.bank_booth_10355`, so no extra wiring is needed here — they're immediately usable.
 *
 * Rotation/type are the standard scenery values (type 10). If a booth ever renders facing the
 * wrong way it's a cosmetic [ROT] tweak; the "Bank" option is rotation-independent.
 */
class LumbridgeHomeBankPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {
    init {
        val booth = getRSCM("object.bank_booth_10355")
        onWorldInit {
            for (z in Z_START..Z_END) {
                world.spawn(DynamicObject(id = booth, type = OBJ_TYPE, rot = ROT, tile = Tile(WALL_X, z, 0)))
            }
        }
        // A teller behind each booth: one tile west (against the wall), facing east across the
        // counter. [BankerPlugin] already binds these ids to talk-to / bank / collect, so the
        // tellers open the bank on click as well as looking the part. Alternated for visual variety.
        var i = 0
        for (z in Z_START..Z_END) {
            spawnNpc(TELLERS[i++ % TELLERS.size], TELLER_X, z, 0, 0, Direction.EAST)
        }
        logger.info { "Home bank: ${Z_END - Z_START + 1} booths + tellers along x=$WALL_X (z $Z_START..$Z_END)." }
    }

    private companion object {
        const val OBJ_TYPE = 10   // standard scenery loc shape (matches how skills spawn booths/benches)
        const val ROT = 1         // face east-west (across the aisle), not along the row
        const val WALL_X = 3206   // just inside the castle's west wall
        const val TELLER_X = 3205 // one tile west of the booths — behind the counter, against the wall
        const val Z_START = 3218
        const val Z_END = 3226    // 9 booths
        val TELLERS = listOf("npc.banker_1479", "npc.banker_1480") // bound by BankerPlugin
    }
}
