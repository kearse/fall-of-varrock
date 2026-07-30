package org.alter.plugins.content.areas.lumbridge.objs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
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
        logger.info { "Home bank: ${Z_END - Z_START + 1} booths along x=$WALL_X (z $Z_START..$Z_END)." }
    }

    private companion object {
        const val OBJ_TYPE = 10   // standard scenery loc shape (matches how skills spawn booths/benches)
        const val ROT = 0
        const val WALL_X = 3206   // just inside the castle's west wall
        const val Z_START = 3214
        const val Z_END = 3222    // 9 booths
    }
}
