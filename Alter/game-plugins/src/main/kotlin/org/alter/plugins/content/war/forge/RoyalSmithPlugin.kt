package org.alter.plugins.content.war.forge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
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
        // The Royal Smith stands by the castle wall, west of the courtyard fountain. TUNE.
        spawnNpc(smith, x = 3207, z = 3224, height = 0, walkRadius = 2, direction = Direction.EAST)
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
        when (options(p, "Melee (Bandos → Torva)", "Ranged (Armadyl → Masori)", "Magic (Ahrim's → Ancestral)", "What is war-forging?", "Farewell.")) {
            1 -> pickPiece(p, "Melee")
            2 -> pickPiece(p, "Ranged")
            3 -> pickPiece(p, "Magic")
            4 -> explain(p)
            5 -> chatPlayer(p, "Farewell.")
        }
    }

    private suspend fun QueueTask.explain(p: Player) {
        chatNpc(p, "Every forged piece takes the war itself: the base armour — win it or buy it — <col=801700>${WarForge.COMMENDATION_COST} Commendations</col> earned marching with the knights, <col=801700>${WarForge.RUNITE_BAR_COST} runite bars</col> from the realm's smelters, and a ${"%,d".format(WarForge.COIN_COST)} coin fee.", npc = smithId, title = "Royal Smith")
        chatNpc(p, "Commendations are yours alone — no coin buys another soldier's service record. The finished piece, though? Sell it if you like. Somebody marched for it either way.", npc = smithId, title = "Royal Smith")
    }

    private suspend fun QueueTask.pickPiece(p: Player, style: String) {
        val recipes = WarForge.recipesFor(style)
        val labels = recipes.map { it.display }.toTypedArray()
        val choice = options(p, *labels, "Back.")
        if (choice !in 1..recipes.size) return
        confirmForge(p, recipes[choice - 1])
    }

    private suspend fun QueueTask.confirmForge(p: Player, r: WarForge.Recipe) {
        chatNpc(p, "A <col=801700>${r.display}</col>. The price: ${WarForge.costLine(r)}. All of it in your pack, mind.", npc = smithId, title = "Royal Smith")
        val missing = WarForge.missingFor(p, r)
        if (missing != null) {
            chatNpc(p, "You're short: <col=801700>$missing</col>. Come back when you have it all.", npc = smithId, title = "Royal Smith")
            return
        }
        when (options(p, "Forge the ${r.display}.", "Not yet.")) {
            1 -> {
                if (WarForge.forge(p, r)) {
                    chatNpc(p, "Stand back... <col=801700>Done.</col> Wear it well, ${p.address} — the realm's fire is in it.", npc = smithId, title = "Royal Smith")
                    Announce.broadcast(world, "<col=ffcc00>${p.username} has war-forged a ${r.display} at the Royal Smith!</col>")
                } else {
                    chatNpc(p, "Your pack changed while I readied the forge. Bring the full price and we'll try again.", npc = smithId, title = "Royal Smith")
                }
            }
            else -> chatPlayer(p, "Not yet.")
        }
    }
}
