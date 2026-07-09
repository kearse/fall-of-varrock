package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The **learning layer** — persisted memory of how players defend, so the AI adapts over
 * raids and days instead of resetting every fight. For each front+field it keeps an
 * exponential moving average of the player presence seen there. The goblin commander then
 * **feints toward the field players historically neglect** — "the horde learned you camp the
 * east bridge, so it probes east and commits west."
 *
 * Coarse (per-field) on purpose for v1 — cheap, legible, persistent.
 * `// WAR BRAIN ROADMAP`: the deep version is a per-tile heatmap (mined from [WarRecorder])
 * so the AI learns exact kill-zones, retreat lanes, and choke habits.
 */
object WarMemory {
    private val logger = KotlinLogging.logger {}
    private const val SCHEMA_VERSION = 1
    private val saveFile = Paths.get("../data/saves/world/war_memory.json")
    private val prettyPrint: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    /** frontId -> (fieldName -> EMA of players seen). Stored ×100 to keep it integral. */
    private val presence = HashMap<String, HashMap<String, Int>>()

    @Volatile private var dirty = false

    private const val ALPHA = 5   // EMA weight numerator /100 (5% new sample)
    private const val SCALE = 100

    /** Fold a fresh observation of [players] in [fieldName] into the moving average. */
    fun observe(frontId: String, fieldName: String, players: Int) {
        val map = presence.getOrPut(frontId) { HashMap() }
        val prev = map[fieldName] ?: (players * SCALE)
        val next = (prev * (100 - ALPHA) + players * SCALE * ALPHA) / 100
        map[fieldName] = next
        dirty = true
    }

    /** Learned average player presence (×100) for a field; 0 if never seen. */
    fun presenceScore(frontId: String, fieldName: String): Int = presence[frontId]?.get(fieldName) ?: 0

    /** The field index the goblins have learned is LEAST defended (the feint target), or -1. */
    fun softestFieldIndex(frontId: String, fieldNames: List<String>): Int {
        if (fieldNames.isEmpty()) return -1
        return fieldNames.indices.minByOrNull { presenceScore(frontId, fieldNames[it]) } ?: -1
    }

    fun load() {
        try {
            if (!Files.exists(saveFile)) return
            val doc = Document.parse(saveFile.readText().trimStart('﻿'))
            val fronts = doc.get("fronts", Document::class.java) ?: Document()
            presence.clear()
            for ((frontId, value) in fronts) {
                val sub = value as? Document ?: continue
                val map = HashMap<String, Int>()
                for ((field, v) in sub) if (v is Number) map[field] = v.toInt()
                presence[frontId] = map
            }
            dirty = false
            logger.info { "Loaded war memory: ${presence.size} front(s)." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load war memory; starting fresh." }
        }
    }

    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        try {
            Files.createDirectories(saveFile.parent)
            val fronts = Document()
            presence.forEach { (frontId, map) ->
                val sub = Document()
                map.forEach { (field, v) -> sub.append(field, v) }
                fronts.append(frontId, sub)
            }
            saveFile.writeText(Document().append("version", SCHEMA_VERSION).append("fronts", fronts).toJson(prettyPrint))
            dirty = false
        } catch (e: Exception) {
            logger.error(e) { "Failed to save war memory; will retry." }
        }
    }
}
