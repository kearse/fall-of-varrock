package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * The **battle datasheet** — opt-in, per-tick coordinate logging of every unit + player to an
 * NDJSON file (one JSON object per line). This is the literal "coordinate datasheet tied to
 * the grid": a replay/heatmap substrate for debugging the spatial AI and (later) training the
 * learning layer. OFF by default (toggle with `::warlog`) because it writes on the game thread;
 * lines are buffered and flushed in batches to keep the cost low.
 *
 * Line shape: `{"t":<tick>,"g":[[x,z],...],"k":[[x,z],...],"p":[[x,z,lvl],...]}`
 * (g=goblins, k=knights, p=players). Feed the file to any external grid viewer.
 */
object WarRecorder {
    private val writers = HashMap<String, BufferedWriter>()
    private var pending = 0

    fun isEnabled(frontId: String): Boolean = writers.containsKey(frontId)

    /** Toggle recording for [frontId]; returns the new enabled state. */
    fun toggle(frontId: String): Boolean {
        if (isEnabled(frontId)) { disable(frontId); return false }
        enable(frontId); return isEnabled(frontId)
    }

    fun enable(frontId: String) {
        if (isEnabled(frontId)) return
        try {
            val path = Paths.get("../data/saves/world/war_replay_$frontId.ndjson")
            Files.createDirectories(path.parent)
            writers[frontId] = Files.newBufferedWriter(
                path, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
            logger.info { "War replay recording ON for '$frontId' -> $path" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to open war replay for '$frontId'" }
        }
    }

    fun disable(frontId: String) {
        writers.remove(frontId)?.let {
            try { it.flush(); it.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun record(frontId: String, tick: Int, goblins: List<Npc>, knights: List<Npc>, players: List<Player>) {
        val w = writers[frontId] ?: return
        try {
            val sb = StringBuilder(64 + (goblins.size + knights.size + players.size) * 8)
            sb.append("{\"t\":").append(tick).append(",\"g\":[")
            goblins.forEachIndexed { i, n -> if (i > 0) sb.append(','); sb.append('[').append(n.tile.x).append(',').append(n.tile.z).append(']') }
            sb.append("],\"k\":[")
            knights.forEachIndexed { i, n -> if (i > 0) sb.append(','); sb.append('[').append(n.tile.x).append(',').append(n.tile.z).append(']') }
            sb.append("],\"p\":[")
            players.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[').append(p.tile.x).append(',').append(p.tile.z).append(',').append(p.combatLevel).append(']') }
            sb.append("]}")
            w.write(sb.toString()); w.newLine()
            if (++pending >= FLUSH_EVERY) { pending = 0; w.flush() }
        } catch (e: Exception) {
            logger.error(e) { "War replay write failed for '$frontId'; disabling." }
            disable(frontId)
        }
    }

    private const val FLUSH_EVERY = 20
}
