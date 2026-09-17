package org.alter.plugins.content.items.mythicalcape

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.magic.TeleportType
import org.alter.plugins.content.magic.canTeleport
import org.alter.plugins.content.magic.teleport
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Mythical cape** — the Myths' Guild teleport (player report 2026-09-13: "mythical cape teleport
 * doesnt work"). The cape was in the cache with its teleport verb intact and simply had no
 * binding: no plugin in the tree referenced item 21913 or its 22114 twin, so the option did
 * nothing at all.
 *
 * Both cache ids are bound — 21913 is the cape, 22114 the identical row the cache also carries —
 * so a player can't end up holding the "wrong" one and find it inert. Unlimited uses, no charges
 * (OSRS), on the [TeleportType.MODERN] rules: level-20 Wilderness ceiling, refused under Tele
 * Block or in a staked duel, all handled by [canTeleport].
 */
class MythicalCapePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        var bound = 0
        for (cape in CAPES) {
            if (runCatching { getRSCM(cape) }.isFailure) continue
            // The equipment menu's verb wording isn't guaranteed; take the first that binds.
            val ok = TELEPORT_VERBS.any { verb ->
                runCatching {
                    onEquipmentOption(item = cape, option = verb) {
                        player.queue(TaskPriority.STRONG) {
                            if (player.canTeleport(TeleportType.MODERN)) {
                                player.message("You are teleported to the Myths' Guild.")
                                player.teleport(GUILD, TeleportType.MODERN)
                            }
                        }
                    }
                }.isSuccess
            }
            if (ok) bound++ else logger.warn { "mythical-cape: '$cape' has no teleport verb in the cache." }
        }
        if (bound == 0) logger.warn { "mythical-cape: no cape id bound; the teleport stays dead." }
    }

    private companion object {
        /** The cape (21913) and the duplicate cache row for the same item (22114). */
        val CAPES = listOf("item.mythical_cape", "item.mythical_cape_22114")

        /** Equipment-menu verbs an OSRS teleport cape might carry, best first. */
        val TELEPORT_VERBS = listOf("Teleport", "Myths' Guild", "Myth's Guild", "Teleport to guild")

        /** Ground floor of the Myths' Guild, just inside the door (Corsair Cove). */
        val GUILD = Tile(2458, 2845, 0)
    }
}
