package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Rank capes ([RankCapes]): the equip gate and the `::cape` reclaim command. The grant
 * itself lives in [RankPurchase.buy] so both purchase surfaces (Duke dialogue and the
 * rank window) hand the cape out.
 *
 * The gate is a MINIMUM-rank check, matching the armour-gate philosophy: a cape proves
 * its wearer holds at least that rank (nobody below Knight can ever wear the Knight's
 * cape; a King wearing one is just dressing down — the stat ladder makes that a real
 * cost). Checked BEFORE the item equips via canEquipItem, unlike the armour gate's
 * equip-then-strip, because rank capes are a known handful of item ids.
 */
class RankCapesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        RankCapes.CAPES.forEach { (rank, itemKey) ->
            canEquipItem(itemKey) {
                if (player.title.ordinal >= rank.ordinal) {
                    true
                } else {
                    player.message("Only a ${rank.display} of the realm may wear the ${RankCapes.capeName(rank)}.")
                    false
                }
            }
        }

        // ::cape — free reclaim of your current rank's cape (untradeable, so this can't
        // be farmed). Covers players who ranked up before capes existed, and losses.
        onCommand("cape", description = "Claim your rank's cape if you don't have it") {
            val rank = player.title
            if (RankCapes.itemFor(rank) == null) {
                player.message("Rank capes are granted from Squire and up — buy a rank from Duke Horacio.")
                return@onCommand
            }
            if (RankCapes.owns(player, rank)) {
                player.message("You already have your ${RankCapes.capeName(rank)} — check your bank if it isn't with you.")
            } else {
                RankCapes.grant(player, rank)
            }
        }
    }
}
