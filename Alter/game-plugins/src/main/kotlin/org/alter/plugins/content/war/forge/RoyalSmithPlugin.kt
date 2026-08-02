package org.alter.plugins.content.war.forge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **Royal Smith** (story-and-grind-design §6) — the war-forging vendor. Spawned in the
 * Lumbridge castle courtyard (tile TUNE), he upgrades elite bases into their best-in-slot
 * successors for the four-pillar recipe in [WarForge]. Forging is gated at rank
 * **Knight** and the finished pieces are Lord-tier armour to WEAR — the ladder stays king.
 * A successful forging is a realm-wide headline.
 */
class RoyalSmithPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val smith = "npc.thurgo"
    private val smithId = getRSCM(smith)

    init {
        // The Royal Smith mans the GE hub's desk ring — west slot of the south face, facing
        // his south desks. TUNE.
        spawnNpc(smith, x = 3221, z = 3209, height = 0, walkRadius = 0, direction = Direction.SOUTH)
        bindSmith()
    }

    /** Bind Talk-to defensively (cache verb may differ) — mirrors the Sergeant's binding. */
    private fun bindSmith() {
        val actions = runCatching { dev.openrune.cache.CacheManager.getNpc(smithId).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        val talk = actions.firstOrNull { it.equals("Talk-to", true) }
        if (talk == null) {
            logger.warn { "Royal Smith '$smith' has no Talk-to option in cache; war-forging unreachable by dialogue." }
            return
        }
        onNpcOption(smith, option = talk) { player.queue { smithDialog(player) } }
    }

    private suspend fun QueueTask.smithDialog(p: Player) {
        chatNpc(p, "The realm's war-forge, ${p.address}. Bring me an elite piece, your Commendations from the field, runite bars and the fee — I'll hammer it into something the enemy will learn to fear.", npc = smithId, title = "Royal Smith")
        if (p.title.ordinal < Title.KNIGHT.ordinal) {
            chatNpc(p, "But my forge serves the realm's proven, ${p.address} — earn the rank of <col=801700>Knight</col> and we'll talk work.", npc = smithId, title = "Royal Smith")
            return
        }
        // The recipes themselves are the client-drawn War Forge window (lofforge): base → result
        // rows with the material checklist drawn, not spoken.
        ForgeMenu.open(p)
    }

    /** The window's forge channel ("::forge make <i>" → forgeclick). Also testable directly. */
    private fun forgeClick(p: Player, index: Int?) {
        // The token arrives from anywhere; forging consumes a BIS base + untradeable
        // Commendations, so keep the old dialogue's invariant: it happens AT the forge.
        if (!p.tile.isWithinRadius(SMITH_TILE, FORGE_RADIUS)) {
            p.message("The war-forge burns beside the castle wall — bring your materials to the Royal Smith.")
            return
        }
        if (p.title.ordinal < Title.KNIGHT.ordinal) {
            p.message("The forge serves the realm's proven — earn the rank of Knight first.")
            return
        }
        val r = index?.let { WarForge.RECIPES.getOrNull(it) } ?: return
        val missing = WarForge.missingFor(p, r)
        if (missing != null) {
            p.message("You're short: <col=801700>$missing</col>.")
            return
        }
        if (WarForge.forge(p, r)) {
            p.message("<col=ffcc00>Stand back... Done.</col> The realm's fire is in your ${r.display}.")
            Announce.broadcast(world, "<col=ffcc00>${p.username} has war-forged a ${r.display} at the Royal Smith!</col>")
            ForgeMenu.open(p) // refresh the open window's checklist
        } else {
            p.message("Your pack changed while the forge was readied. Bring the full price and try again.")
        }
    }

    init {
        onCommand("forgeclick", description = "War Forge window action (client overlay channel)") {
            val a = player.getCommandArgs()
            if (a.getOrNull(0)?.lowercase() == "make") forgeClick(player, a.getOrNull(1)?.toIntOrNull())
        }
    }

    private companion object {
        val SMITH_TILE = Tile(3221, 3209, 0) // keep in sync with the spawnNpc call above
        const val FORGE_RADIUS = 10
    }
}
