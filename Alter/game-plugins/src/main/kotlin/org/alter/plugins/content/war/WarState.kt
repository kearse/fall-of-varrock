package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persistent, server-wide state for **The War** (the offensive war: marches, operations,
 * campaigns and conquests).
 *
 * The alter engine persists nothing globally — [org.alter.game.model.World.attr]
 * is runtime-only and there is no shutdown hook — so this object owns the war's
 * server-wide state and its own JSON persistence.
 *
 * ### What it stores
 * - the realm's **Realm Supplies** meter (skillers fill it, commanders spend it),
 * - the launched-march counter (every Nth march is a GRAND MARCH),
 * - the store's patron-funded march queue.
 *
 * Schema history: v1 = the retired 0–100 siege-pressure model; v2 = per-front knight pools +
 * city-fallen timers for the retired defensive siege (+ the retired Falador district-pressure
 * meter); **v3** = offensive war only (the `fronts` and `districts` sections are ignored on load
 * and no longer written).
 *
 * @see WarStatePlugin for the lifecycle wiring.
 */
object WarState {
    private val logger = KotlinLogging.logger {}

    /** Bumped when the on-disk schema changes (1 = pressure model, 2 = defensive siege, 3 = offensive war). */
    private const val SCHEMA_VERSION = 3

    /** Realm Supplies cap — skilling the Mire fills it, campaigns/conquests drain it. TUNABLE. */
    private const val SUPPLY_METER_MAX = 3000

    private val saveFile: Path = Paths.get("../data/saves/world/war_state.json")
    private val prettyPrint: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    @Volatile
    private var dirty = false

    /** True once [load] has run (file read, or confirmed absent) — the shutdown flush's guard. */
    @Volatile
    private var loaded = false
    val isLoaded: Boolean get() = loaded

    // --- Realm Supplies meter (single realm-wide value; the Mire fills it, campaigns drain it) ---
    private var supplyMeter = 0

    fun supplyMeterMax(): Int = SUPPLY_METER_MAX
    fun getSupplyMeter(): Int = supplyMeter.coerceIn(0, SUPPLY_METER_MAX)
    fun setSupplyMeter(value: Int) {
        val clamped = value.coerceIn(0, SUPPLY_METER_MAX)
        if (clamped != supplyMeter) { supplyMeter = clamped; dirty = true }
    }
    /** Add [delta] (may be negative) to the realm supply meter; returns the new clamped value. */
    fun addSupplyMeter(delta: Int): Int { setSupplyMeter(supplyMeter + delta); return getSupplyMeter() }

    // --- march counter (every Nth launched march is a GRAND MARCH — see MarchPlugin) ---
    private var marchCount = 0

    fun getMarchCount(): Int = marchCount

    fun incMarchCount(): Int {
        marchCount++
        dirty = true
        return marchCount
    }

    // --- patron-funded marches (store — story-and-grind-design §7): queued by the store's
    //     RewardDeliveryPlugin on purchase, consumed by MarchPlugin at the next muster call.
    //     Entries are "name|1" (grand) / "name|0". Persisted so a purchase survives a restart.
    private val patronQueue = ArrayList<String>()

    fun queuePatronMarch(name: String, grand: Boolean) {
        patronQueue.add("$name|${if (grand) 1 else 0}")
        dirty = true
    }

    /** Pop the next funded march as (patronName, isGrand), or null if none waits. */
    fun popPatronMarch(): Pair<String, Boolean>? {
        if (patronQueue.isEmpty()) return null
        val e = patronQueue.removeAt(0)
        dirty = true
        return e.substringBeforeLast('|') to e.endsWith("|1")
    }

    /**
     * Load war state from disk. Safe when no file exists (starts fresh) and never
     * throws — a corrupt file is logged and treated as "start fresh". Older saves are
     * read leniently: the v2 `fronts` section (knight pools / city-fallen timers of the
     * retired defensive siege) is simply ignored.
     */
    fun load() {
        try {
            if (!Files.exists(saveFile)) {
                logger.info { "No war state file at $saveFile; starting fresh." }
                loaded = true
                return
            }
            val doc = Document.parse(saveFile.readText().trimStart('﻿'))
            val version = (doc.get("version") as? Number)?.toInt() ?: 0
            val legacy = listOf("fronts", "districts").filter { doc.containsKey(it) }
            if (legacy.isNotEmpty()) {
                logger.info { "War state v$version: dropping retired section(s) ${legacy.joinToString()} (defensive siege / district pressure)." }
            }
            (doc.get("supplyMeter") as? Number)?.let { supplyMeter = it.toInt().coerceIn(0, SUPPLY_METER_MAX) }
            (doc.get("marchCount") as? Number)?.let { marchCount = it.toInt().coerceAtLeast(0) }
            patronQueue.clear()
            (doc.get("patronMarches") as? List<*>)?.forEach { (it as? String)?.let(patronQueue::add) }
            dirty = version != SCHEMA_VERSION || legacy.isNotEmpty() // rewrite an older shape on the next save tick
            loaded = true
            logger.info { "Loaded war state (v$version): supplies $supplyMeter/$SUPPLY_METER_MAX, marches $marchCount, patron queue ${patronQueue.size}." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load war state from $saveFile; starting fresh." }
        }
    }

    /** Write war state to disk. No-op when nothing changed unless [force]. Never throws. */
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        try {
            Files.createDirectories(saveFile.parent)
            val doc = Document()
                .append("version", SCHEMA_VERSION)
                .append("supplyMeter", supplyMeter)
                .append("marchCount", marchCount)
                .append("patronMarches", patronQueue.toList())
            saveFile.writeText(doc.toJson(prettyPrint))
            dirty = false
            logger.info { "Saved war state to $saveFile." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save war state to $saveFile; will retry." }
        }
    }
}
