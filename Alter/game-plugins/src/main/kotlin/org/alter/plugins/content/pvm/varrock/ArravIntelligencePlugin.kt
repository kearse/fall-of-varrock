package org.alter.plugins.content.pvm.varrock

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Captain Rovin — the Varrock palace guard captain who survived the Fall — runs the
 * **Arrav Intelligence** board from the west bank pocket (a PvP carve-out, so the counter is
 * safe to stand at). Talk-to → take / report / explain.
 */
class ArravIntelligencePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            runCatching {
                val npc = Npc(getRSCM(ROVIN_KEY), world.snapToWalkable(ROVIN_TILE, maxRadius = 3), world)
                npc.walkRadius = 0
                world.spawn(npc)
                npc.respawns = false
                npc.setActive(true)
                WarNpcNames.rename(npc, "Captain Rovin (Arrav Intelligence)")
            }.onFailure { logger.warn { "arrav-intelligence: could not post Captain Rovin: ${it.message}" } }
        }
        onNpcOption(npc = ROVIN_KEY, option = "talk-to") { player.queue { talk(player) } }
        onCommand("intel", description = "Your Arrav Intelligence assignment") {
            val t = ArravIntelligence.current(player)
            player.message(if (t == null) "You have no Arrav Intelligence assignment. Captain Rovin at the Varrock west bank has work." else "Assignment: ${t.describe()}.")
        }
    }

    private suspend fun QueueTask.talk(p: Player) {
        val id = getRSCM(ROVIN_KEY)
        if (!ArravIntelligence.canUse(p)) {
            chatNpc(p, "Rovin's Varrock guard. What's left of it. I coordinate what<br>intelligence we still gather from the ruins — but I hand<br>that work to knights of the realm, not passers-by.", npc = id, title = "Captain Rovin")
            chatNpc(p, "Earn your spurs and come back.", npc = id, title = "Captain Rovin")
            return
        }
        val current = ArravIntelligence.current(p)
        when (options(p, "Take an assignment", "Report on my assignment", "What is Arrav Intelligence?", "Nothing for now", title = "Captain Rovin")) {
            1 -> {
                if (current != null) {
                    chatNpc(p, "You already have work: ${current.describe()}.<br>Finish it before you take more.", npc = id, title = "Captain Rovin")
                } else {
                    val t = ArravIntelligence.assign(p)
                    chatNpc(p, "Here's what the scouts brought back. ${t.describe().replaceFirstChar { it.uppercase() }}.<br>Bring me results and I'll see the Realm remembers it.", npc = id, title = "Captain Rovin")
                }
            }
            2 -> {
                if (current == null) {
                    chatNpc(p, "Nothing on the board for you. Assignments completed: ${ArravIntelligence.completed(p)}.", npc = id, title = "Captain Rovin")
                } else {
                    chatNpc(p, "Still open: ${current.describe()}. ${current.left} to go.", npc = id, title = "Captain Rovin")
                }
            }
            3 -> {
                chatNpc(p, "When the city fell, the guard who lived kept watching it.<br>Every patrol, every salvage run, every captain we mark —<br>that's intelligence. Arrav's name is on it because the<br>trail always leads back to him.", npc = id, title = "Captain Rovin")
                chatNpc(p, "Purge the elite dead, recover salvage for the Royal Smith,<br>hunt the Hollow when he walks, fell the Warden in the<br>palace. War Effort, Commendations and relics for the work.", npc = id, title = "Captain Rovin")
            }
            else -> chatNpc(p, "Keep your head down out there.", npc = id, title = "Captain Rovin")
        }
    }

    companion object {
        const val ROVIN_KEY = "npc.captain_rovin"
        /** Inside the Varrock west bank safe pocket (PvpZones carve-out 3178-3196 × 3432-3453). */
        val ROVIN_TILE = Tile(3187, 3440, 0)
    }
}
