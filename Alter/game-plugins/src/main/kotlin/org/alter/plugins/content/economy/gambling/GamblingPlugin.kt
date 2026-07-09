package org.alter.plugins.content.economy.gambling

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **House gambling — percentile dice** (Phase 6 economy: a gp sink + social texture).
 *
 * The player bets coins against the *house* (so it works at any population): a percentile die
 * (1–100) is rolled; [WIN_THRESHOLD]+ pays out double the stake minus the house [RAKE], below it
 * the house keeps the stake. The rake is the gp **sink** — long-run expected value is negative by
 * exactly the rake. Every roll is shown to the player and logged (the "recorded" requirement).
 *
 * Kept deliberately in-game-only on this localhost project. A commit/reveal provably-fair scheme
 * and player-vs-player dicing are noted as future hardening (the latter needs real population).
 */
class GamblingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")
    private val hostId = getRSCM(HOST)

    init {
        spawnNpc(HOST, 3224, 3215, 0, 0, Direction.WEST) // south end of the hub's east column
        bindHost(HOST)
        onCommand("gamble", description = "Gamble coins on a dice roll") { player.queue { gamble(player) } }
    }

    private suspend fun QueueTask.gamble(player: Player) {
        chatNpc(player,
            "Care to test your luck? I roll a percentile die — $WIN_THRESHOLD or higher and I pay " +
                "double your stake, minus my ${(RAKE * 100).toInt()}% cut. Lower than that, I keep it.",
            npc = hostId)
        if (options(player, "Place a bet.", "Not right now.") != 1) return

        val held = player.inventory.getItemCount(coins)
        if (held <= 0) {
            chatNpc(player, "Come back when you've got some coins to wager.", npc = hostId)
            return
        }

        val bet = inputInt(player, "Enter your bet (coins):")
        if (bet <= 0) return
        if (bet > held) {
            chatNpc(player, "You don't have that many coins.", npc = hostId)
            return
        }
        if (bet > MAX_BET) {
            chatNpc(player, "House limit is ${"%,d".format(MAX_BET)} coins a roll.", npc = hostId)
            return
        }

        // Take the stake up front.
        if (player.inventory.remove(item = coins, amount = bet).completed < bet) return

        val roll = world.random(99) + 1 // 1..100
        val win = roll >= WIN_THRESHOLD
        if (win) {
            val payout = (bet.toLong() * 2L * (1.0 - RAKE)).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            player.inventory.add(item = coins, amount = payout)
            chatNpc(player, "You rolled <col=007f00>$roll</col>. Winner! Here's ${"%,d".format(payout)} coins.", npc = hostId)
        } else {
            chatNpc(player, "You rolled <col=7f0000>$roll</col>. The house wins this one.", npc = hostId)
        }
        logger.info { "GAMBLE ${player.username} bet=$bet roll=$roll ${if (win) "WIN" else "LOSE"}" }
    }

    /** Bind EVERY vendor option (Talk-to AND Gamble/Trade) so none is a dead click on the host. */
    private fun bindHost(npc: String) {
        if (!bindVendorOptions(npc) { player.queue { gamble(player) } }) {
            logger.warn { "gambling: '$npc' has no click options; use ::gamble." }
        }
    }

    private companion object {
        const val HOST = "npc.bartender"
        const val WIN_THRESHOLD = 51 // 51..100 win = 50/50 odds; the rake is the edge
        const val RAKE = 0.05
        const val MAX_BET = 100_000_000
    }
}
