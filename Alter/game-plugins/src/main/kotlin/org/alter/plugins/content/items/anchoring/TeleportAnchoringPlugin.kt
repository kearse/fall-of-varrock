package org.alter.plugins.content.items.anchoring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.messageBox
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Binds the **teleport anchoring scroll** (player report 2026-09-13: "teleport anchoring scroll
 * doesnt work" — it had no binding of any kind). Reading it spends the scroll for the permanent
 * anchor described on [TeleportAnchoring].
 *
 * The cache's option name for the scroll is not guaranteed, so every plausible verb is tried and
 * the first that binds wins; if the item carries none, that is logged at boot rather than throwing
 * (`onItemOption` asserts the option exists — an unguarded call would take the whole plugin down).
 */
class TeleportAnchoringPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val scroll = runCatching { getRSCM(TeleportAnchoring.SCROLL) }.getOrNull()
        if (scroll == null) {
            logger.warn { "anchoring: '${TeleportAnchoring.SCROLL}' not in cache; scroll not bound." }
        } else {
            val bound = READ_VERBS.any { verb ->
                runCatching {
                    onItemOption(item = TeleportAnchoring.SCROLL, option = verb) {
                        player.queue { readScroll(this, player, scroll) }
                    }
                }.isSuccess
            }
            if (!bound) {
                logger.warn {
                    "anchoring: teleport anchoring scroll has no readable verb in the cache " +
                        "(tried $READ_VERBS); the scroll stays inert."
                }
            }
        }
    }

    /**
     * Spend one scroll for the anchor. Already-anchored players keep the scroll — it is a tradeable
     * drop, and silently eating a second one for no effect would be a straight loss of value.
     */
    private suspend fun readScroll(
        task: org.alter.game.model.queue.QueueTask,
        p: Player,
        scroll: Int,
    ) {
        if (TeleportAnchoring.isAnchored(p)) {
            p.message("You are already anchored against unwanted teleportation.")
            return
        }
        if (p.inventory.remove(item = scroll, amount = 1).completed == 0) return
        p.attr[TeleportAnchoring.ANCHORED_ATTR] = true
        task.messageBox(
            p,
            "You read the scroll and the words settle into you.<br><br>" +
                "You are now <col=7f007f>anchored</col>: nothing can teleport you against your will.",
        )
        logger.info { "anchoring: ${p.username} unlocked teleport anchoring." }
    }

    private companion object {
        /** Verbs an OSRS scroll might carry, best first. */
        val READ_VERBS = listOf("Read", "Activate", "Use", "Study")
    }
}
