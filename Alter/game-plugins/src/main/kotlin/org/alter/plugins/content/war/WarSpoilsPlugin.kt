package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.item.Item
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * The **war-spoils claim** commands — the player side of [WarSpoils].
 *
 *  - `::claim` opens the custom **Spoils of War** window (client `lofwarspoils`) if the commander has
 *    anything owed.
 *  - `::spoils <action> [index]` is the overlay's click channel: the client posts it as public chat,
 *    [org.alter.game.message.handler.MessagePublicHandler] routes it here as `spoilsclick`, and the
 *    window refreshes (or closes) after the claim. Actions: `bankall`, `takeall`, `take <i>`, `bank <i>`.
 *  - A commander who logs in with unclaimed spoils gets a one-line reminder.
 */
class WarSpoilsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("claim", description = "Claim your unclaimed war spoils") {
            if (WarSpoils.isEmpty(player)) {
                player.message("You have no war spoils to claim. Win a campaign or conquest as its commander.")
                return@onCommand
            }
            WarSpoils.open(player)
        }

        // Client overlay channel — "::spoils <action> [index]" → here (see MessagePublicHandler).
        onCommand("spoilsclick", description = "War spoils window action (client overlay channel)") {
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "bankall" -> WarSpoils.claimAll(player, toBank = true)
                "takeall" -> WarSpoils.claimAll(player, toBank = false)
                "take" -> a.getOrNull(1)?.toIntOrNull()?.let { WarSpoils.claimOne(player, it, toBank = false) } ?: return@onCommand
                "bank" -> a.getOrNull(1)?.toIntOrNull()?.let { WarSpoils.claimOne(player, it, toBank = true) } ?: return@onCommand
                else -> return@onCommand
            }
            WarSpoils.refresh(player)
        }

        onLogin {
            if (!WarSpoils.isEmpty(player)) {
                player.message("<col=ffd700>You have unclaimed war spoils — type <col=0000ff>::claim</col><col=ffd700> to collect them.</col>")
            }
        }

        // Dev harness: seed a sample claim (coins + a random relic) and open the window — no war needed.
        onCommand("spoilstest", Privilege.DEV_POWER, description = "Seed test war spoils and open the claim window") {
            val grant = mutableListOf(Item(getRSCM("item.coins_995"), 5_000_000))
            val relic = ThirdAge.randomPieceId()
            if (relic >= 0) grant.add(Item(relic, 1))
            WarSpoils.add(player, grant)
            WarSpoils.open(player)
        }
    }
}
