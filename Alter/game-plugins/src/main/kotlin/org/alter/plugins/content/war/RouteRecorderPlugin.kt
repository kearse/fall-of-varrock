package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Route recorder** (dev tool) — capture an admin's *walked* path so a hand-walked route can be used
 * verbatim as a campaign march route ([CampaignOp.route]), instead of guessing waypoints off the map.
 *
 *  - `::recroute start` — begin sampling your tile every game tick (deduped to one entry per tile).
 *  - `::recroute stop`  — stop, write the raw path to `../data/saves/mapdump/route_<name>.txt`, and
 *                         LOG a down-sampled waypoint list as paste-ready `Tile(x, z, 0),` lines.
 *  - `::recroute clear` — discard the current recording.
 *
 * The file lives under the **saves** directory on purpose: that is the one game-data path mounted to
 * writable, persistent storage in production (`../data/saves` → the host `saves` bind mount), so a
 * recording survives a container redeploy and can be read back off disk. Writing it cwd-relative
 * (`data/mapdump`) instead landed it in the container's throwaway layer under `bin/`.
 *
 * Walk the path you want the army to take (Lumbridge muster → over the bridge → into Varrock), then
 * read the `RECROUTE-WP` lines out of the server log and drop them into the op's `route`.
 */
class RouteRecorderPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val recording = HashSet<String>()                 // usernames currently recording
    private val tracks = HashMap<String, MutableList<Tile>>()  // username -> sampled tiles

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = 1 }
        onTimer(timer) {
            if (recording.isNotEmpty()) {
                world.players.forEach { p ->
                    if (p.index >= 0 && p.username in recording) {
                        val track = tracks.getOrPut(p.username) { ArrayList() }
                        val t = p.tile
                        val last = track.lastOrNull()
                        if (last == null || last.x != t.x || last.z != t.z || last.height != t.height) {
                            track += t
                            logger.info { "RECLIVE ${p.username} ${t.x},${t.z},${t.height}" } // live trail (tail-able)
                        }
                    }
                }
            }
            world.timers[timer] = 1
        }

        onCommand("recroute", Privilege.ADMIN_POWER, description = "Record your walked route: ::recroute <start|stop|clear>") {
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "start" -> {
                    tracks[player.username] = ArrayList()
                    recording += player.username
                    player.message("<col=4f9b4f>Route recording STARTED. Walk the path, then <col=ffae00>::recroute stop</col><col=4f9b4f>.</col>")
                }
                "stop" -> {
                    recording -= player.username
                    finish(player.username)?.let { (raw, wp) ->
                        player.message("<col=4f9b4f>Recording STOPPED: $raw tiles walked, $wp waypoints. See the server log (RECROUTE-WP lines).</col>")
                    } ?: player.message("<col=801700>No recording in progress — ::recroute start first.</col>")
                }
                "clear" -> {
                    recording -= player.username; tracks.remove(player.username)
                    player.message("Recording cleared.")
                }
                else -> player.message("Usage: ::recroute <start|stop|clear>")
            }
        }
    }

    /** Write the raw path + log a down-sampled waypoint list. Returns (rawCount, waypointCount) or null. */
    private fun finish(username: String): Pair<Int, Int>? {
        val raw = tracks[username]?.toList() ?: return null
        if (raw.isEmpty()) return 0 to 0

        // Down-sample: keep a waypoint every ~STEP tiles of travel, always including the final tile.
        val wp = ArrayList<Tile>()
        for (t in raw) if (wp.isEmpty() || cheby(wp.last(), t) >= STEP) wp += t
        if (cheby(wp.last(), raw.last()) > 0) wp += raw.last()

        logger.info { "RECROUTE $username: raw=${raw.size} tiles, waypoints=${wp.size} (step=$STEP)" }
        wp.forEach { logger.info { "RECROUTE-WP Tile(${it.x}, ${it.z}, 0)," } }

        runCatching {
            val sb = StringBuilder("# Walked route for $username: ${raw.size} raw tiles\n")
            raw.forEach { sb.append(it.x).append(',').append(it.z).append('\n') }
            // Write under ../data/saves — the only writable, host-mounted, redeploy-surviving
            // game-data path in prod (WORKDIR is bin/, so this resolves next to the save files).
            val f = File("../data/saves/mapdump/route_$username.txt")
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())
            logger.info { "RECROUTE wrote raw path to ${f.absolutePath}" }
        }.onFailure { logger.warn(it) { "RECROUTE: could not write route file (waypoints still logged)" } }

        return raw.size to wp.size
    }

    private fun cheby(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private companion object {
        const val STEP = 8 // tiles between down-sampled waypoints (short hops = reliable marching)
    }
}
