package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Server half of the client-drawn **Feudal Ranks** window (`lofranks` in the custom client —
 * the CoD-style progression screen: current emblem, coin bar, next-rank showcase, emblem track).
 *
 * Same thin pattern as [org.alter.plugins.content.teleport.TeleportClientMenu]:
 *  - [RankMenu.open] pulses [RankMenu.OPEN_VARP] carrying the player's rank; the client opens
 *    on the rising edge and reads carried coins from the inventory itself.
 *  - the purchase comes back as the public-chat token `::rank buy <ordinal>` which
 *    `MessagePublicHandler` routes here as the `rankclick` command; the shared [RankPurchase]
 *    does the real transaction and a fresh pulse updates the open window in place.
 *
 * Duke Horacio's dialogue opens this for every non-quest visit ([DukeHoracioPlugin]); `::ranks`
 * is the direct opener.
 */
object RankMenu {
    /**
     * Overlay-open varp (docs/overlay-design-system.md §8). Pulsed value = `title.ordinal + 1`
     * (1 = Peasant … 8 = King) so the client knows the current rank; pulse-to-0 so a persisted
     * varp can't re-open the window on login.
     */
    const val OPEN_VARP = 4618

    /** Pulse the open signal (also called after a purchase so the open window refreshes). */
    fun open(p: Player) {
        p.setVarp(OPEN_VARP, p.title.ordinal + 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}

class RankMenuPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("ranks", description = "Open the Feudal Ranks window") {
            RankMenu.open(player)
        }
        // The overlay's purchase channel ("::rank buy <ordinal>" → rankclick). Also testable
        // directly by typing ::rankclick buy <ordinal>.
        onCommand("rankclick", description = "Feudal Ranks window action (client overlay channel)") {
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "buy" -> buy(player, a.getOrNull(1)?.toIntOrNull())
            }
        }
    }

    private fun buy(p: Player, ordinal: Int?) {
        // The client says which rung it showed as NEXT; missing/garbled falls back to the real
        // next rung. RankPurchase re-validates either way — the ladder can't be skipped.
        val target = ordinal?.let { Title.values().getOrNull(it) } ?: p.nextTitle ?: return
        when (val r = RankPurchase.buy(p, target)) {
            is RankPurchase.Result.Success -> {
                val now = p.title
                p.message("<col=e8c15a>Arise, ${now.display}!</col> You may now wear ${now.maxTier.display} armour.")
                val prev = Title.values()[now.ordinal - 1]
                if (now.companions > prev.companions) {
                    p.message("Your new station entitles you to ${now.companions} soldier companion${if (now.companions > 1) "s" else ""} — General Zo will muster them.")
                }
                RankMenu.open(p) // re-pulse so the open window advances the ladder in place
            }
            is RankPurchase.Result.Insufficient ->
                p.message("The rank of ${target.display} costs ${fmt(r.cost)} coins — you carry ${fmt(r.have)}.")
            is RankPurchase.Result.Blocked -> {
                p.message("<col=801700>Not yet — ${target.display} is earned standing. Still owed: ${RankEligibility.describeAll(r.unmet)}.</col>")
                RankMenu.open(p) // keep the window in step
            }
            is RankPurchase.Result.NotNext ->
                RankMenu.open(p) // client was stale; refresh it to the real ladder state
            is RankPurchase.Result.Maxed ->
                p.message("You already hold the highest rank in the land.")
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)
}
