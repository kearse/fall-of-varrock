package org.alter.plugins.content.economy.gambling

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
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
 * The table itself is the client-drawn **Gambler's Table** window (`lofdice`): stake chips, the
 * odds printed on the felt, the die burning in the roll. Open pulse + result pulse share varp
 * [DiceMenu.OPEN_VARP]; the bet comes back as `::dice roll <amount>` → `diceclick`.
 *
 * Kept deliberately in-game-only on this localhost project. A commit/reveal provably-fair scheme
 * and player-vs-player dicing are noted as future hardening (the latter needs real population).
 */
object DiceMenu {
    /**
     * Overlay varp (docs/overlay-design-system.md §8), pulsed to 0 — two shapes share it:
     *   open   `v = 1`
     *   result `v = 1 | 1<<9 | roll<<1 | win<<8`  (bit9 marks a result; roll 1-100 in bits 1-7... roll
     *   uses bits 1-7 (≤127); win in bit 8)
     */
    const val OPEN_VARP = 4628

    fun open(p: Player) = pulse(p, 1)

    fun result(p: Player, roll: Int, win: Boolean) =
        pulse(p, 1 or (1 shl 9) or (roll.coerceIn(0, 127) shl 1) or (if (win) 1 shl 8 else 0))

    private fun pulse(p: Player, v: Int) {
        p.setVarp(OPEN_VARP, v)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}

class GamblingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")

    init {
        spawnNpc(HOST, 3224, 3215, 0, 0, Direction.WEST) // south end of the hub's east column
        // Bind EVERY vendor option (Talk-to AND Gamble/Trade) so none is a dead click on the host.
        if (!bindVendorOptions(HOST) { DiceMenu.open(player) }) {
            logger.warn { "gambling: '$HOST' has no click options; use ::gamble." }
        }
        onCommand("gamble", description = "Open the Gambler's Table") { DiceMenu.open(player) }

        // The window's bet channel ("::dice roll <amount>" → diceclick). Also testable directly.
        onCommand("diceclick", description = "Gambler's Table action (client overlay channel)") {
            val a = player.getCommandArgs()
            if (a.getOrNull(0)?.lowercase() == "roll") {
                roll(player, a.getOrNull(1)?.toIntOrNull() ?: 0)
            }
        }
    }

    private fun roll(player: Player, bet: Int) {
        if (bet <= 0) return
        // The token arrives from anywhere; the old dialogue's implicit invariant was that a bet
        // is placed AT the table. Also defuses "type ::dice roll 10m for free coins" trolling.
        if (!player.tile.isWithinRadius(HOST_TILE, TABLE_RADIUS)) {
            player.message("The house only takes bets at the table — find the host at the shop hub.")
            return
        }
        val held = player.inventory.getItemCount(coins)
        if (bet > held) {
            player.message("You don't have that many coins.")
            return
        }
        if (bet > MAX_BET) {
            player.message("House limit is ${"%,d".format(MAX_BET)} coins a roll.")
            return
        }
        // Take the stake up front.
        if (player.inventory.remove(item = coins, amount = bet).completed < bet) return

        val roll = world.random(99) + 1 // 1..100
        val win = roll >= WIN_THRESHOLD
        if (win) {
            val payout = (bet.toLong() * 2L * (1.0 - RAKE)).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            player.inventory.add(item = coins, amount = payout)
            player.message("You rolled <col=007f00>$roll</col>. Winner! The house pays ${"%,d".format(payout)} coins.")
        } else {
            player.message("You rolled <col=7f0000>$roll</col>. The house wins this one.")
        }
        DiceMenu.result(player, roll, win) // burn the number into the open window
        logger.info { "GAMBLE ${player.username} bet=$bet roll=$roll ${if (win) "WIN" else "LOSE"}" }
    }

    private companion object {
        const val HOST = "npc.bartender"
        val HOST_TILE = Tile(3224, 3215, 0) // keep in sync with the spawnNpc call above
        const val TABLE_RADIUS = 10
        const val WIN_THRESHOLD = 51 // 51..100 win = 50/50 odds; the rake is the edge
        const val RAKE = 0.05
        const val MAX_BET = 100_000_000
    }
}
