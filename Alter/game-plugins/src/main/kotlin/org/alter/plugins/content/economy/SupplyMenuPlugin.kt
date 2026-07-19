package org.alter.plugins.content.economy

import org.alter.api.ChatMessageType
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.RealmSupply
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.rscm.RSCM.getRSCM

/**
 * Server half of the client-drawn **Supply Depot** window (`lofsupply`): the Quartermaster as a
 * sell-to-the-war store — every accepted item with its War Effort price, per-item or per-category
 * hand-ins, and the rotating Supply Drive bonus front and centre.
 *
 * Channels (docs/overlay-design-system.md §8):
 *  - open/refresh: [OPEN_VARP] pulse + a `~LOFSUP~` CONSOLE manifest (chunked — long chat lines
 *    are dropped by the client):
 *      header  `~LOFSUP~H|<nCats>|<driveCatIdx>|<mult>|<meter>|<meterMax>|<warEffort>`
 *      items   `~LOFSUP~C|<catIdx>|<label>|<chunkIdx>|<last>|itemId:weEach:carried;...`
 *  - actions: `::sup cat <i>` / `::sup item <id> <qty|all>` / `::sup all` → `supclick`.
 */
object SupplyMenu {
    /** Overlay-open varp — pulsed to 0 so it never persists/re-fires on login. */
    const val OPEN_VARP = 4624

    private const val PREFIX = "~LOFSUP~"
    private const val CHUNK = 8

    /** Push the manifest and pulse the open signal. */
    fun open(p: Player) {
        pushManifest(p)
        p.setVarp(OPEN_VARP, 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }

    /** Re-push after a deposit so the open window's counts and totals update in place. */
    fun refresh(p: Player) = open(p)

    private fun pushManifest(p: Player) {
        val cats = SupplyDepot.CATEGORIES
        val driveIdx = SupplyDrive.active?.ordinal ?: -1
        p.message(
            "${PREFIX}H|${cats.size}|$driveIdx|${SupplyDrive.MULTIPLIER}|${RealmSupply.meter()}|${RealmSupply.max()}|${p.points(PointKind.WAR_EFFORT)}",
            ChatMessageType.CONSOLE,
        )
        cats.forEachIndexed { ci, cat ->
            val entries = cat.wares.mapNotNull { (key, we) ->
                runCatching { getRSCM(key) }.getOrNull()?.let { id -> Triple(id, we, p.inventory.getItemCount(id)) }
            }
            val chunks = if (entries.isEmpty()) listOf(emptyList()) else entries.chunked(CHUNK)
            chunks.forEachIndexed { chunkIdx, chunk ->
                val last = if (chunkIdx == chunks.lastIndex) 1 else 0
                val sb = StringBuilder(PREFIX)
                    .append("C|").append(ci).append('|').append(cat.label).append('|')
                    .append(chunkIdx).append('|').append(last).append('|')
                chunk.forEach { (id, we, carried) -> sb.append(id).append(':').append(we).append(':').append(carried).append(';') }
                p.message(sb.toString(), ChatMessageType.CONSOLE)
            }
        }
    }
}

class SupplyMenuPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // NB: named ::depot, NOT ::supply — CampaignCommandPlugin already owns ::supply (the
        // realm-stores readout) and PluginRepository throws on duplicate command binds.
        onCommand("depot", description = "Open the Supply Depot window") {
            SupplyMenu.open(player)
        }
        // The overlay's action channel ("::sup ..." → supclick). Also testable directly.
        onCommand("supclick", description = "Supply Depot window action (client overlay channel)") {
            // The token arrives from anywhere — keep the old menu's invariants: deposits happen
            // AT a Quartermaster post, and never before the intro-quest dagger hand-in.
            if (QUARTERMASTER_POSTS.none { player.tile.isWithinRadius(it, POST_RADIUS) }) {
                player.message("The Quartermaster takes supplies at his post — find him at the shop hub or in The Mire.")
                return@onCommand
            }
            if (RecruitTrials.step(player) == RecruitTrials.Step.DELIVER) {
                player.message("Hand the Quartermaster your forged bronze dagger first — that's the lesson.")
                return@onCommand
            }
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "cat" -> a.getOrNull(1)?.toIntOrNull()?.let { i ->
                    SupplyDepot.CATEGORIES.getOrNull(i)?.let { cat ->
                        report(player, SupplyDepot.deposit(player, cat.wares), cat.label.lowercase())
                    }
                }
                "item" -> {
                    val id = a.getOrNull(1)?.toIntOrNull()
                    // Strict qty parse: only an explicit "all" means sell-everything; a malformed
                    // or non-positive amount is REJECTED, never silently widened to the full stack.
                    val rawQty = a.getOrNull(2) ?: "all"
                    val qty = if (rawQty.equals("all", true)) 0 else rawQty.toIntOrNull()
                    if (id != null && qty != null && (qty > 0 || qty == 0 && rawQty.equals("all", true))) {
                        report(player, SupplyDepot.depositItem(player, id, qty), "supplies")
                    }
                }
                "all" -> report(player, SupplyDepot.deposit(player, SupplyDepot.ALL), "supplies")
            }
        }
    }

    private companion object {
        /** The Quartermaster's two posts: the shop hub and the tutorial post in The Mire crypt. */
        val QUARTERMASTER_POSTS = listOf(Tile(3219, 3216, 0), Tile(3248, 3193, 0))
        const val POST_RADIUS = 10
    }

    private fun report(p: Player, result: Pair<Int, Int>, label: String) {
        val (items, we) = result
        if (items <= 0) {
            p.message("You've no $label the war can use.")
            return
        }
        p.message("$items $label for the front — that's <col=801700>$we War Effort</col>.")
        SupplyMenu.refresh(p) // update the open window's counts in place
    }
}
