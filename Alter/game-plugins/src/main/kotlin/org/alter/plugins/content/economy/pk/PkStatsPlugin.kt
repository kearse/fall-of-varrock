package org.alter.plugins.content.economy.pk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.PlayerSaving
import org.alter.plugins.content.bots.PkBot
import org.bson.Document
import kotlin.math.pow
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/** Persistent lifetime PK kills (real-vs-real only). */
val PK_KILLS_ATTR = AttributeKey<Int>(persistenceKey = "pk_kills")
/** Persistent lifetime PK deaths (real-vs-real only). */
val PK_DEATHS_ATTR = AttributeKey<Int>(persistenceKey = "pk_deaths")
/** Persistent Elo rating; everyone starts at [PkStatsPlugin.DEFAULT_ELO]. */
val PK_ELO_ATTR = AttributeKey<Int>(persistenceKey = "pk_elo")

/**
 * **PK / KD hiscores** — drives the roak-style top-left info box on our custom client
 * (`net.runelite.client.plugins.lofpkstats`).
 *
 * Tracks lifetime kills, deaths and an Elo rating per account, persisted as player attributes.
 * Only **real-player-vs-real-player** kills count — slaying a [PkBot] (the server's practice
 * targets) is ignored, as is dying to one, so the ladder reflects genuine PvP.
 *
 * The four display values are published into varps the client overlay reads:
 *   [VARP_KILLS] kills, [VARP_DEATHS] deaths, [VARP_ELO] elo, [VARP_RANK] = "TOP x.x%" in tenths
 *   of a percent (e.g. 782 = top 78.2%).
 *
 * The TOP% percentile is computed against **every saved account** (offline included) on a slow
 * timer, via [PlayerSaving]'s format-agnostic `loadAll()`, with online players' live Elo merged in.
 */
class PkStatsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Cached Elo of every known account (offline + online), refreshed on [BOARD_REFRESH_TICKS]. */
    private var board: IntArray = IntArray(0)

    init {
        // Push fresh varps shortly after login (opening/refreshing during the login sequence is fine
        // for varps; we just want the overlay populated). Defaults applied lazily on read.
        onLogin { player.publishStats() }

        val publishTimer = TimerKey()
        val boardTimer = TimerKey()
        onWorldInit {
            world.timers[publishTimer] = PUBLISH_TICKS
            world.timers[boardTimer] = 20 // first board build ~12s after boot
        }

        // Fast publish loop: keep the overlay current as kills/deaths/elo/rank change.
        onTimer(publishTimer) {
            world.players.forEach { if (it.entityType.isHumanControlled) it.publishStats() }
            world.timers[publishTimer] = PUBLISH_TICKS
        }

        // Slow board rebuild: scan all saves for the percentile distribution.
        onTimer(boardTimer) {
            rebuildBoard()
            world.timers[boardTimer] = BOARD_REFRESH_TICKS
        }

        // The ladder mover: award/deduct on a real PvP kill (additive with PkRewardsPlugin's hook).
        onPlayerPreDeath {
            val victim = player
            val killer = victim.attr[KILLER_ATTR]?.get() as? Player ?: return@onPlayerPreDeath
            if (killer === victim) return@onPlayerPreDeath
            // Same per-death verdict PkRewardsPlugin pays on ([PkKillGuard]): a kill the fair-play
            // rules refuse moves no Elo / kill count either (elo/kill-count stays real players only).
            val verdict = PkKillGuard.verdictFor(world, victim) ?: return@onPlayerPreDeath

            // PK QoL: a legitimate PvP kill — or a bot practice kill — refills the killer's special
            // attack. A denied human kill (alt, repeat victim, no risk...) does NOT.
            if (killer !is PkBot && (verdict.ok || verdict.rule == PkKillGuard.Rule.BOT_VICTIM)) {
                org.alter.plugins.content.interfaces.attack.AttackTab.setEnergy(killer, 100)
                killer.message("<col=801700>Your special attack energy has been restored.</col>")
            }

            if (!verdict.ok) return@onPlayerPreDeath

            val ke = killer.elo()
            val ve = victim.elo()
            killer.setKills(killer.kills() + 1)
            victim.setDeaths(victim.deaths() + 1)
            killer.setElo(ke + eloDelta(ke, ve, won = true))
            victim.setElo(ve + eloDelta(ve, ke, won = false))

            killer.message("<col=801700>PK:</col> you defeated ${victim.username}. (${killer.kills()} kills, Elo ${killer.elo()})")
            victim.message("<col=801700>PK:</col> you were defeated by ${killer.username}. (Elo ${victim.elo()})")
            killer.publishStats()
            victim.publishStats()
        }

        onCommand("pkstats", description = "Show your PK stats") {
            val k = player.kills(); val d = player.deaths()
            val kd = if (d == 0) k.toDouble() else k.toDouble() / d
            player.message("--- PK stats ---")
            player.message("Kills: <col=801700>$k</col>  Deaths: <col=801700>$d</col>  K/D: <col=801700>${"%.3f".format(kd)}</col>")
            player.message("Elo: <col=801700>${player.elo()}</col>  Rank: <col=801700>TOP ${"%.1f".format(percentileTenths(player.elo()) / 10.0)}%</col>")
        }
    }

    // --- persistent accessors (lazy defaults) ---
    private fun Player.kills() = attr[PK_KILLS_ATTR] ?: 0
    private fun Player.deaths() = attr[PK_DEATHS_ATTR] ?: 0
    private fun Player.elo() = attr[PK_ELO_ATTR] ?: DEFAULT_ELO
    private fun Player.setKills(v: Int) { attr[PK_KILLS_ATTR] = v.coerceAtLeast(0) }
    private fun Player.setDeaths(v: Int) { attr[PK_DEATHS_ATTR] = v.coerceAtLeast(0) }
    private fun Player.setElo(v: Int) { attr[PK_ELO_ATTR] = v.coerceAtLeast(0) }

    /** Standard Elo adjustment for [a] facing [b]; [won]=true → a won. K = [ELO_K]. */
    private fun eloDelta(a: Int, b: Int, won: Boolean): Int {
        val expected = 1.0 / (1.0 + 10.0.pow((b - a) / 400.0))
        val score = if (won) 1.0 else 0.0
        return (ELO_K * (score - expected)).roundToInt()
    }

    /** Pack the four display values into varps; only send the ones that actually changed. */
    private fun Player.publishStats() {
        setVarpIfChanged(VARP_KILLS, kills())
        setVarpIfChanged(VARP_DEATHS, deaths())
        setVarpIfChanged(VARP_ELO, elo())
        setVarpIfChanged(VARP_RANK, percentileTenths(elo()))
    }

    private fun Player.setVarpIfChanged(varp: Int, value: Int) {
        if (getVarp(varp) != value) setVarp(varp, value)
    }

    /**
     * "TOP x.x%" in tenths of a percent: the share of accounts ranked at or above you
     * (so #1 of 100 ≈ top 1.0%; matches the roak presentation). 0 when no data.
     */
    private fun percentileTenths(elo: Int): Int {
        val total = board.size
        if (total <= 0) return 1000 // alone → top 100.0%
        val higher = board.count { it > elo }
        val topPct = (higher + 1).toDouble() / total * 100.0
        return (topPct * 10.0).roundToInt().coerceIn(0, 1000)
    }

    /** Rebuild the Elo distribution from all saves (offline) merged with online players' live Elo. */
    private fun rebuildBoard() {
        try {
            val byUser = HashMap<String, Int>()
            PlayerSaving.serialization.loadAll().forEach { (user, doc) ->
                byUser[user.lowercase()] = eloOf(doc)
            }
            world.players.forEach { p ->
                val name = (p as? Client)?.loginUsername ?: p.username
                byUser[name.lowercase()] = p.elo()
            }
            board = byUser.values.toIntArray()
        } catch (e: Exception) {
            logger.warn(e) { "pk-stats: failed to rebuild Elo board (keeping previous)" }
        }
    }

    /** Read `attributes.attribute.pk_elo` out of a saved document, defaulting to [DEFAULT_ELO]. */
    private fun eloOf(doc: Document): Int = try {
        val attrs = doc.get("attributes", Document::class.java)?.get("attribute", Document::class.java)
        (attrs?.get("pk_elo") as? Number)?.toInt() ?: DEFAULT_ELO
    } catch (e: Exception) {
        DEFAULT_ELO
    }

    companion object {
        const val DEFAULT_ELO = 1000
        private const val ELO_K = 32.0
        private const val PUBLISH_TICKS = 5         // ~3s
        private const val BOARD_REFRESH_TICKS = 150 // ~90s

        // Varps the client overlay reads (4601 freed when the siege HUD was retired).
        private const val VARP_KILLS = 4602
        private const val VARP_DEATHS = 4603
        private const val VARP_ELO = 4604
        private const val VARP_RANK = 4605
    }
}
